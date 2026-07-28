package com.example.schedule_manager.domain.recurringschedule.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

public record RecurringScheduleResponseDto(
        Long id,
        String title,
        String content,
        LocalTime startTime,
        LocalTime endTime,
        Set<DayOfWeek> daysOfWeek,
        LocalDate startDate,
        LocalDate endDate,
        Long categoryId,
        String categoryName
) {
}
