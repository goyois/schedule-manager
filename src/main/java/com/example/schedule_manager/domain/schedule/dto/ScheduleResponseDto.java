package com.example.schedule_manager.domain.schedule.dto;

import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;

import java.time.LocalDateTime;

public record ScheduleResponseDto(
        Long id,
        String title,
        String content,
        LocalDateTime startAt,
        LocalDateTime endAt,
        ScheduleStatus status,
        String username,
        String categoryName
) {
    public static ScheduleResponseDto from(Schedule schedule) {
        return new ScheduleResponseDto(
                schedule.getId(),
                schedule.getTitle(),
                schedule.getContent(),
                schedule.getStartAt(),
                schedule.getEndAt(),
                schedule.getStatus(),
                schedule.getUser().getUsername(),
                schedule.getCategory().getName()
        );
    }
}
