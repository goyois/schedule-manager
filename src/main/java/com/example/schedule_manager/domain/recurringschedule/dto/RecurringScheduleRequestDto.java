package com.example.schedule_manager.domain.recurringschedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

public record RecurringScheduleRequestDto(
        @NotBlank(message = "제목을 입력해주세요.")
        String title,

        String content,

        @NotNull(message = "시작 시각을 입력해주세요.")
        LocalTime startTime,

        // 알림형(종료 시각 없는) 반복 일정은 null로 보낼 수 있어 필수로 강제하지 않는다
        LocalTime endTime,

        @NotEmpty(message = "반복할 요일을 하나 이상 선택해주세요.")
        Set<DayOfWeek> daysOfWeek,

        @NotNull(message = "시작일을 입력해주세요.")
        LocalDate startDate,

        // null이면 무기한 반복
        LocalDate endDate,

        @NotNull(message = "카테고리를 선택해주세요.")
        Long categoryId
) {
}
