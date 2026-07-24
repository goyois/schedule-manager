package com.example.schedule_manager.domain.mandalart.dto;

import com.example.schedule_manager.domain.mandalart.entity.MandalartBoard;
import com.example.schedule_manager.domain.mandalart.entity.MandalartCell;

import java.util.List;

public record MandalartBoardResponseDto(
        Long id,
        String title,
        List<MandalartCellDto> cells
) {
    public static MandalartBoardResponseDto from(MandalartBoard board, List<MandalartCell> cells) {
        return new MandalartBoardResponseDto(
                board.getId(),
                board.getTitle(),
                cells.stream().map(MandalartCellDto::from).toList()
        );
    }
}
