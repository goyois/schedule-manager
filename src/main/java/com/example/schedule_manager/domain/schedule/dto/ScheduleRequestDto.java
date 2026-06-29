package com.example.schedule_manager.domain.schedule.dto;

import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;

import java.time.LocalDateTime;

public record ScheduleRequestDto(
        String title,
        String content,
        LocalDateTime startAt,
        LocalDateTime endAt,
        ScheduleStatus status,
        Long userId,
        Long categoryId
) {
}
