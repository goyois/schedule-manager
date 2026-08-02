package com.example.schedule_manager.domain.report.dto;

public record CategoryStatDto(
        String categoryName,
        long count,
        double percentage
) {
}
