package com.example.schedule_manager.domain.mandalart.dto;

import com.example.schedule_manager.domain.mandalart.entity.MandalartCell;

public record MandalartCellDto(
        int row,
        int col,
        String content
) {
    public static MandalartCellDto from(MandalartCell cell) {
        return new MandalartCellDto(cell.getRowIndex(), cell.getColIndex(), cell.getContent());
    }
}
