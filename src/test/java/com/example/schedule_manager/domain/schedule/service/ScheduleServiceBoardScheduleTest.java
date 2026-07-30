package com.example.schedule_manager.domain.schedule.service;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.schedule.dto.PageResponseDto;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.example.schedule_manager.domain.schedule.repository.ScheduleRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScheduleService.getBoardSchedules() - 보드 뷰 "더보기" 전용 페이징 조회.
 * getSchedules()와 같은 권한 규칙(USER는 본인 id 강제, ADMIN은 요청 userId 그대로)을 따르는지,
 * 그리고 조회 범위가 하루(date 파라미터, 없으면 오늘) 00:00 ~ 다음날 00:00으로 좁혀지는지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleServiceBoardScheduleTest {

    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ScheduleCacheQueryService scheduleCacheQueryService;
    @Mock
    private ScheduleEventPublisher scheduleEventPublisher;
    @Mock
    private ScheduleEmbeddingService scheduleEmbeddingService;

    @InjectMocks
    private ScheduleService scheduleService;

    private User user;
    private User admin;
    private Category category;

    @BeforeEach
    void setUp() {
        user = User.builder().id(1L).username("tester").email("tester@example.com").userType(UserType.USER).build();
        admin = User.builder().id(2L).username("admin").email("admin@example.com").userType(UserType.ADMIN).build();
        category = Category.builder().id(10L).name("업무").build();
    }

    @Test
    @DisplayName("USER는 요청 userId와 무관하게 본인 id로 조회한다")
    void userForcesOwnId() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        Pageable pageable = PageRequest.of(0, 5);
        ScheduleResponseDto dto = new ScheduleResponseDto(100L, "회의", "", LocalDateTime.now(), null,
                ScheduleStatus.PENDING, user.getUsername(), category.getName());
        Page<ScheduleResponseDto> page = new PageImpl<>(List.of(dto), pageable, 1);
        when(scheduleRepository.searchBoardSchedules(eq(user.getId()), any(), eq(ScheduleStatus.PENDING),
                any(), any(), eq(pageable))).thenReturn(page);

        // 다른 유저(999L)의 id를 요청 파라미터로 넘겨도 USER는 무시되고 본인 id로 강제된다
        PageResponseDto<ScheduleResponseDto> result = scheduleService.getBoardSchedules(
                user.getEmail(), 999L, category.getId(), ScheduleStatus.PENDING, null, pageable);

        verify(scheduleRepository).searchBoardSchedules(eq(user.getId()), eq(category.getId()),
                eq(ScheduleStatus.PENDING), any(), any(), eq(pageable));
        assertThat(result.content()).containsExactly(dto);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADMIN은 요청 userId를 그대로 사용한다(비우면 전체 조회)")
    void adminUsesRequestedUserId() {
        when(userRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        Pageable pageable = PageRequest.of(0, 5);
        Page<ScheduleResponseDto> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        when(scheduleRepository.searchBoardSchedules(isNull(), any(), eq(ScheduleStatus.COMPLETED), any(), any(), eq(pageable)))
                .thenReturn(emptyPage);

        scheduleService.getBoardSchedules(admin.getEmail(), null, null, ScheduleStatus.COMPLETED, null, pageable);

        verify(scheduleRepository).searchBoardSchedules(isNull(), isNull(), eq(ScheduleStatus.COMPLETED),
                any(), any(), eq(pageable));
    }

    @Test
    @DisplayName("date를 생략하면 조회 범위는 오늘 00:00부터 내일 00:00까지로 고정된다")
    void rangeDefaultsToTodayWhenDateOmitted() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        Pageable pageable = PageRequest.of(0, 5);
        Page<ScheduleResponseDto> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        when(scheduleRepository.searchBoardSchedules(any(), any(), any(), any(), any(), any())).thenReturn(emptyPage);

        ArgumentCaptor<LocalDateTime> rangeStartCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> rangeEndCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        scheduleService.getBoardSchedules(user.getEmail(), null, null, ScheduleStatus.PENDING, null, pageable);

        verify(scheduleRepository).searchBoardSchedules(any(), any(), any(),
                rangeStartCaptor.capture(), rangeEndCaptor.capture(), any());
        LocalDateTime expectedStart = LocalDate.now().atStartOfDay();
        assertThat(rangeStartCaptor.getValue()).isEqualTo(expectedStart);
        assertThat(rangeEndCaptor.getValue()).isEqualTo(expectedStart.plusDays(1));
    }

    @Test
    @DisplayName("date를 지정하면 조회 범위가 그 날짜 00:00부터 다음날 00:00까지로 좁혀진다(전날/다음날 이동)")
    void rangeUsesGivenDateWhenProvided() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        Pageable pageable = PageRequest.of(0, 5);
        Page<ScheduleResponseDto> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        when(scheduleRepository.searchBoardSchedules(any(), any(), any(), any(), any(), any())).thenReturn(emptyPage);

        ArgumentCaptor<LocalDateTime> rangeStartCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> rangeEndCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        LocalDate requestedDate = LocalDate.now().minusDays(1);
        scheduleService.getBoardSchedules(user.getEmail(), null, null, ScheduleStatus.PENDING, requestedDate, pageable);

        verify(scheduleRepository).searchBoardSchedules(any(), any(), any(),
                rangeStartCaptor.capture(), rangeEndCaptor.capture(), any());
        LocalDateTime expectedStart = requestedDate.atStartOfDay();
        assertThat(rangeStartCaptor.getValue()).isEqualTo(expectedStart);
        assertThat(rangeEndCaptor.getValue()).isEqualTo(expectedStart.plusDays(1));
    }
}
