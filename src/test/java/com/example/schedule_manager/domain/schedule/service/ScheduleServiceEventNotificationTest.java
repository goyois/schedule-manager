package com.example.schedule_manager.domain.schedule.service;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.schedule.dto.ScheduleRequestDto;
import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.example.schedule_manager.domain.schedule.repository.ScheduleRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScheduleService의 일정 변경 지점(생성/수정/삭제/자동 상태 전환)이 모두
 * ScheduleEventPublisher.notifyChanged()를 호출해 SSE 구독자에게 알리는지 검증한다.
 * (evictScheduleCacheForUser() 안에서 캐시 무효화와 함께 호출되므로, 그 메서드가 불리는
 * 네 지점을 각각 검증한다.)
 */
@ExtendWith(MockitoExtension.class)
class ScheduleServiceEventNotificationTest {

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

    private User owner;
    private Category category;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(1L).username("tester").email("tester@example.com").userType(UserType.USER).build();
        category = Category.builder().id(10L).name("업무").build();
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(Collections.emptySet());
    }

    @Test
    @DisplayName("일정 생성 시 소유자에게 변경 이벤트를 알린다")
    void createSchedule_notifiesOwner() {
        ScheduleRequestDto request = new ScheduleRequestDto(
                "회의", "", LocalDateTime.now(), LocalDateTime.now().plusHours(1),
                ScheduleStatus.PENDING, owner.getId(), category.getId());
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduleService.createSchedule(request);

        verify(scheduleEventPublisher).notifyChanged(owner.getId());
        verify(scheduleEmbeddingService).reindexSchedule(eq(owner.getId()), any(Schedule.class));
    }

    @Test
    @DisplayName("일정 수정 시 소유자에게 변경 이벤트를 알린다")
    void updateSchedule_notifiesOwner() {
        Schedule schedule = Schedule.builder()
                .id(100L).title("회의").status(ScheduleStatus.PENDING).user(owner).category(category).build();
        when(scheduleRepository.findById(100L)).thenReturn(Optional.of(schedule));
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
        ScheduleRequestDto request = new ScheduleRequestDto(
                "회의(수정)", "", LocalDateTime.now(), null, ScheduleStatus.IN_PROGRESS, null, category.getId());

        scheduleService.updateSchedule(100L, request);

        verify(scheduleEventPublisher).notifyChanged(owner.getId());
        verify(scheduleEmbeddingService).reindexSchedule(eq(owner.getId()), any(Schedule.class));
    }

    @Test
    @DisplayName("일정 삭제 시 소유자에게 변경 이벤트를 알린다")
    void deleteSchedule_notifiesOwner() {
        Schedule schedule = Schedule.builder()
                .id(100L).title("회의").status(ScheduleStatus.PENDING).user(owner).category(category).build();
        when(scheduleRepository.findById(100L)).thenReturn(Optional.of(schedule));

        scheduleService.deleteSchedule(100L);

        verify(scheduleEventPublisher).notifyChanged(owner.getId());
        verify(scheduleEmbeddingService).deleteScheduleEmbedding(100L);
    }

    @Test
    @DisplayName("자동 상태 전환 시 대상 유저에게 변경 이벤트를 알린다")
    void autoTransition_notifiesAffectedUser() {
        Schedule justStarted = Schedule.builder()
                .id(200L).title("알림형").status(ScheduleStatus.PENDING)
                .startAt(LocalDateTime.now().minusMinutes(1)).endAt(null).user(owner).category(category).build();
        when(scheduleRepository.findByStatusAndStartAtLessThanEqualAndUser_AutoStatusModeTrue(any(), any()))
                .thenReturn(List.of(justStarted));
        when(scheduleRepository.findByStatusInAndEndAtLessThanEqualAndUser_AutoStatusModeTrue(any(), any()))
                .thenReturn(Collections.emptyList());

        scheduleService.autoTransitionScheduleStatuses();

        verify(scheduleEventPublisher).notifyChanged(owner.getId());
    }
}
