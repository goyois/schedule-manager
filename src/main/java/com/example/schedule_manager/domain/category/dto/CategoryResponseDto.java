package com.example.schedule_manager.domain.category.dto;

import com.example.schedule_manager.domain.category.entity.Category;

public record CategoryResponseDto(
        Long id,
        String name
) {
    public static CategoryResponseDto from(Category category) {
        return new CategoryResponseDto(
                category.getId(),
                category.getName()
        );
    }
}
