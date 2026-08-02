package com.example.schedule_manager.domain.report.dto;

import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;

public record StatusCountDto(
        ScheduleStatus status,
        long count
) {
}
