package com.example.schedule_manager.domain.recurringschedule;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.recurringschedule.dto.RecurringScheduleRequestDto;
import com.example.schedule_manager.domain.recurringschedule.dto.RecurringScheduleResponseDto;
import com.example.schedule_manager.domain.recurringschedule.entity.RecurringSchedule;
import com.example.schedule_manager.domain.recurringschedule.repository.RecurringScheduleRepository;
import com.example.schedule_manager.domain.recurringschedule.service.RecurringScheduleService;
import com.example.schedule_manager.domain.schedule.dto.ScheduleRequestDto;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.example.schedule_manager.domain.schedule.service.ScheduleService;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringScheduleServiceTest {

    @Mock
    private RecurringScheduleRepository recurringScheduleRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ScheduleService scheduleService;

    @InjectMocks
    private RecurringScheduleService recurringScheduleService;

    private User user(Long id) {
        return User.builder().id(id).username("tester").email("tester@example.com").userType(UserType.USER).build();
    }

    private Category category(Long id, String name) {
        return Category.builder().id(id).name(name).build();
    }

    @Test
    @DisplayName("반복 일정 생성 성공 - 지정한 요일에만, 오늘부터 창(window) 끝까지 occurrence를 만든다")
    void createRecurringSchedule_success_materializesMatchingWeekdaysOnly() {
        User requester = user(1L);
        Category category = category(10L, "운동");
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(category));
        when(recurringScheduleRepository.save(any(RecurringSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(scheduleService.getOccurrenceDates(any())).thenReturn(Set.of());
        when(scheduleService.createSchedules(any(), any())).thenReturn(List.of());

        // 화/목요일만 반복, 시작일은 오늘
        RecurringScheduleRequestDto request = new RecurringScheduleRequestDto(
                "영양제 먹기", "비타민 챙겨먹기",
                LocalTime.of(9, 0), null,
                Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
                LocalDate.now(), null, 10L);

        RecurringScheduleResponseDto response = recurringScheduleService.createRecurringSchedule("tester@example.com", request);

        assertThat(response.title()).isEqualTo("영양제 먹기");
        assertThat(response.daysOfWeek()).containsExactlyInAnyOrder(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY);
        assertThat(response.categoryName()).isEqualTo("운동");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScheduleRequestDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(scheduleService).createSchedules(captor.capture(), eq(null));
        List<ScheduleRequestDto> created = captor.getValue();

        // 60일 창 안에서 화/목요일만 생성됐는지, 다른 요일은 하나도 없는지 확인
        assertThat(created).isNotEmpty();
        assertThat(created).allMatch(dto ->
                dto.startAt().getDayOfWeek() == DayOfWeek.TUESDAY || dto.startAt().getDayOfWeek() == DayOfWeek.THURSDAY);
        assertThat(created).allMatch(dto -> dto.status() == ScheduleStatus.PENDING);
        assertThat(created).allMatch(dto -> dto.categoryId().equals(10L) && dto.userId().equals(1L));
    }

    @Test
    @DisplayName("반복 일정 생성 성공 - 종료일을 지정하면 그 이후 날짜는 만들지 않는다")
    void createRecurringSchedule_success_respectsEndDate() {
        User requester = user(1L);
        Category category = category(10L, "운동");
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(category));
        when(recurringScheduleRepository.save(any(RecurringSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(scheduleService.getOccurrenceDates(any())).thenReturn(Set.of());
        when(scheduleService.createSchedules(any(), any())).thenReturn(List.of());

        LocalDate endDate = LocalDate.now().plusDays(3);
        RecurringScheduleRequestDto request = new RecurringScheduleRequestDto(
                "매일 운동", null,
                LocalTime.of(7, 0), LocalTime.of(7, 30),
                Set.of(DayOfWeek.values()), // 매일
                LocalDate.now(), endDate, 10L);

        recurringScheduleService.createRecurringSchedule("tester@example.com", request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScheduleRequestDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(scheduleService).createSchedules(captor.capture(), eq(null));
        List<ScheduleRequestDto> created = captor.getValue();

        assertThat(created).allMatch(dto -> !dto.startAt().toLocalDate().isAfter(endDate));
        assertThat(created).hasSize(4); // 오늘부터 endDate까지 4일
    }

    @Test
    @DisplayName("반복 일정 생성 실패 - 존재하지 않는 카테고리면 예외가 발생한다")
    void createRecurringSchedule_categoryNotFound_throws() {
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(user(1L)));
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        RecurringScheduleRequestDto request = new RecurringScheduleRequestDto(
                "제목", null, LocalTime.NOON, null, Set.of(DayOfWeek.MONDAY), LocalDate.now(), null, 999L);

        assertThatThrownBy(() -> recurringScheduleService.createRecurringSchedule("tester@example.com", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    @DisplayName("반복 일정 목록 조회 성공 - 저장된 요일 문자열을 다시 Set<DayOfWeek>로 변환해 응답한다")
    void getRecurringSchedules_success() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        RecurringSchedule rule = RecurringSchedule.builder()
                .id(5L).title("식단").startTime(LocalTime.of(8, 0))
                .daysOfWeek("MONDAY,WEDNESDAY").startDate(LocalDate.now())
                .user(requester).category(category(10L, "식단")).build();
        when(recurringScheduleRepository.findByUserId(1L)).thenReturn(List.of(rule));

        List<RecurringScheduleResponseDto> result = recurringScheduleService.getRecurringSchedules("tester@example.com");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).daysOfWeek()).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
    }

    @Test
    @DisplayName("반복 일정 삭제 성공 - 아직 지나지 않은 occurrence를 정리한 뒤 규칙 자체를 지운다")
    void deleteRecurringSchedule_success() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        RecurringSchedule rule = RecurringSchedule.builder()
                .id(5L).title("식단").startTime(LocalTime.NOON)
                .daysOfWeek("MONDAY").startDate(LocalDate.now())
                .user(requester).category(category(10L, "식단")).build();
        when(recurringScheduleRepository.findById(5L)).thenReturn(Optional.of(rule));

        recurringScheduleService.deleteRecurringSchedule("tester@example.com", 5L);

        verify(scheduleService).deletePendingOccurrences(5L, 1L);
        verify(recurringScheduleRepository).delete(rule);
    }

    @Test
    @DisplayName("반복 일정 삭제 실패 - 다른 유저 소유 규칙이면 존재하지 않는 것처럼 예외가 발생한다")
    void deleteRecurringSchedule_otherUsersRule_throwsNotFound() {
        User requester = user(1L);
        User owner = user(2L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        RecurringSchedule rule = RecurringSchedule.builder()
                .id(5L).title("식단").startTime(LocalTime.NOON)
                .daysOfWeek("MONDAY").startDate(LocalDate.now())
                .user(owner).category(category(10L, "식단")).build();
        when(recurringScheduleRepository.findById(5L)).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> recurringScheduleService.deleteRecurringSchedule("tester@example.com", 5L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RECURRING_SCHEDULE_NOT_FOUND);
        verify(scheduleService, never()).deletePendingOccurrences(anyLong(), anyLong());
    }

    @Test
    @DisplayName("반복 일정 삭제 실패 - 존재하지 않는 규칙이면 예외가 발생한다")
    void deleteRecurringSchedule_notFound_throws() {
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(user(1L)));
        when(recurringScheduleRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recurringScheduleService.deleteRecurringSchedule("tester@example.com", 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RECURRING_SCHEDULE_NOT_FOUND);
    }

    @Test
    @DisplayName("매일 창 연장(@Scheduled) - 활성 규칙만 대상으로 삼고, 이미 있는 날짜는 건너뛴다")
    void extendActiveRecurringSchedules_success_skipsExistingDates() {
        User requester = user(1L);
        RecurringSchedule rule = RecurringSchedule.builder()
                .id(5L).title("매일 운동").startTime(LocalTime.of(7, 0))
                .daysOfWeek("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY")
                .startDate(LocalDate.now().minusDays(10))
                .user(requester).category(category(10L, "운동")).build();
        when(recurringScheduleRepository.findByEndDateIsNullOrEndDateGreaterThanEqual(LocalDate.now()))
                .thenReturn(List.of(rule));
        // 오늘 날짜는 이미 만들어져 있다고 가정 - 오늘치는 다시 생성되면 안 된다
        when(scheduleService.getOccurrenceDates(5L)).thenReturn(Set.of(LocalDate.now()));
        when(scheduleService.createSchedules(any(), eq(5L))).thenReturn(List.of());

        recurringScheduleService.extendActiveRecurringSchedules();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScheduleRequestDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(scheduleService, times(1)).createSchedules(captor.capture(), eq(5L));
        assertThat(captor.getValue()).noneMatch(dto -> dto.startAt().toLocalDate().isEqual(LocalDate.now()));
    }
}
