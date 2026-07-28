package com.example.schedule_manager.domain.recurringschedule.service;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.recurringschedule.dto.RecurringScheduleRequestDto;
import com.example.schedule_manager.domain.recurringschedule.dto.RecurringScheduleResponseDto;
import com.example.schedule_manager.domain.recurringschedule.entity.RecurringSchedule;
import com.example.schedule_manager.domain.recurringschedule.repository.RecurringScheduleRepository;
import com.example.schedule_manager.domain.schedule.dto.ScheduleRequestDto;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.example.schedule_manager.domain.schedule.service.ScheduleService;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class RecurringScheduleService {

    // 한 번에 이만큼의 미래 occurrence를 만들어둔다 - "설정만 해두면 계속 알아서 생긴다"는 체감을 주기엔
    // 충분히 길고, 무기한 반복이 DB에 무한히 쌓이지 않도록 짧게 잡는다. 시간이 지나 창의 앞쪽이 흘러가면
    // extendActiveRecurringSchedules()(@Scheduled)가 매일 뒤쪽에 새 날짜를 채워 항상 이만큼을 유지한다
    private static final int MATERIALIZE_WINDOW_DAYS = 60;

    private final RecurringScheduleRepository recurringScheduleRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final ScheduleService scheduleService;

    public RecurringScheduleResponseDto createRecurringSchedule(String requesterEmail, RecurringScheduleRequestDto request) {
        User requester = findUserByEmail(requesterEmail);
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));

        RecurringSchedule rule = RecurringSchedule.builder()
                .title(request.title())
                .content(request.content())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .daysOfWeek(joinDays(request.daysOfWeek()))
                .startDate(request.startDate())
                .endDate(request.endDate())
                .user(requester)
                .category(category)
                .build();
        recurringScheduleRepository.save(rule);

        materializeOccurrences(rule, LocalDate.now(), LocalDate.now().plusDays(MATERIALIZE_WINDOW_DAYS));
        return toResponseDto(rule);
    }

    @Transactional(readOnly = true)
    public List<RecurringScheduleResponseDto> getRecurringSchedules(String requesterEmail) {
        User requester = findUserByEmail(requesterEmail);
        return recurringScheduleRepository.findByUserId(requester.getId()).stream()
                .map(this::toResponseDto)
                .toList();
    }

    // 반복 규칙 자체를 지우고, 아직 지나지 않은(PENDING) occurrence도 함께 정리한다 - 이미 지난/진행 중인
    // occurrence는 실제 있었던 일정 기록이므로 그대로 남긴다
    public void deleteRecurringSchedule(String requesterEmail, Long id) {
        User requester = findUserByEmail(requesterEmail);
        RecurringSchedule rule = recurringScheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECURRING_SCHEDULE_NOT_FOUND));
        // 다른 유저 소유 규칙 id로 시도해도 존재 자체를 드러내지 않는다(다른 도메인의 소유권 검사와 동일한 패턴)
        if (!rule.getUser().getId().equals(requester.getId())) {
            throw new BusinessException(ErrorCode.RECURRING_SCHEDULE_NOT_FOUND);
        }
        scheduleService.deletePendingOccurrences(rule.getId(), requester.getId());
        recurringScheduleRepository.delete(rule);
    }

    // 매일 새벽에 모든 활성 반복 일정의 미래 창을 오늘 기준으로 다시 채운다 - 생성 시점엔
    // MATERIALIZE_WINDOW_DAYS만큼만 만들어두므로, 시간이 지나 창의 앞쪽이 과거로 흘러가면 그만큼
    // 뒤쪽에 새 날짜를 계속 채워 넣어야 "항상 앞으로 60일치가 준비돼 있는" 상태가 유지된다
    @Scheduled(cron = "0 0 1 * * *")
    public void extendActiveRecurringSchedules() {
        LocalDate today = LocalDate.now();
        List<RecurringSchedule> active = recurringScheduleRepository.findByEndDateIsNullOrEndDateGreaterThanEqual(today);
        for (RecurringSchedule rule : active) {
            materializeOccurrences(rule, today, today.plusDays(MATERIALIZE_WINDOW_DAYS));
        }
    }

    private void materializeOccurrences(RecurringSchedule rule, LocalDate from, LocalDate to) {
        LocalDate windowStart = rule.getStartDate().isAfter(from) ? rule.getStartDate() : from;
        LocalDate windowEnd = rule.getEndDate() != null && rule.getEndDate().isBefore(to) ? rule.getEndDate() : to;
        if (windowStart.isAfter(windowEnd)) return;

        Set<DayOfWeek> days = splitDays(rule.getDaysOfWeek());
        Set<LocalDate> existingDates = scheduleService.getOccurrenceDates(rule.getId());

        List<ScheduleRequestDto> toCreate = new ArrayList<>();
        for (LocalDate date = windowStart; !date.isAfter(windowEnd); date = date.plusDays(1)) {
            if (!days.contains(date.getDayOfWeek()) || existingDates.contains(date)) continue;
            toCreate.add(new ScheduleRequestDto(
                    rule.getTitle(),
                    rule.getContent(),
                    date.atTime(rule.getStartTime()),
                    rule.getEndTime() != null ? date.atTime(rule.getEndTime()) : null,
                    ScheduleStatus.PENDING,
                    rule.getUser().getId(),
                    rule.getCategory().getId()));
        }
        scheduleService.createSchedules(toCreate, rule.getId());
    }

    private String joinDays(Set<DayOfWeek> days) {
        return days.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private Set<DayOfWeek> splitDays(String value) {
        return Arrays.stream(value.split(",")).map(DayOfWeek::valueOf).collect(Collectors.toSet());
    }

    private RecurringScheduleResponseDto toResponseDto(RecurringSchedule rule) {
        return new RecurringScheduleResponseDto(
                rule.getId(),
                rule.getTitle(),
                rule.getContent(),
                rule.getStartTime(),
                rule.getEndTime(),
                splitDays(rule.getDaysOfWeek()),
                rule.getStartDate(),
                rule.getEndDate(),
                rule.getCategory().getId(),
                rule.getCategory().getName());
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
