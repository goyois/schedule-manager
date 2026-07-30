package com.example.schedule_manager.domain.mandalart.dto;

import com.example.schedule_manager.domain.mandalart.entity.MandalartBoard;

import java.time.LocalDateTime;

public record MandalartBoardSummaryDto(
        Long id,
        String title,
        LocalDateTime createdAt,
        boolean active
) {
    public static MandalartBoardSummaryDto from(MandalartBoard board, boolean active) {
        return new MandalartBoardSummaryDto(board.getId(), board.getTitle(), board.getCreatedAt(), active);
    }
}
