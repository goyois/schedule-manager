package com.example.schedule_manager.domain.ai.dto;

import com.example.schedule_manager.domain.ai.entity.AiChatMessage;

import java.time.LocalDateTime;

public record AiChatMessageDto(
        Long id,
        String role,
        String message,
        String suggestedTitle,
        String suggestedContent,
        LocalDateTime suggestedStartAt,
        LocalDateTime suggestedEndAt,
        Long suggestedCategoryId,
        Long registeredScheduleId,
        LocalDateTime createdAt
) {
    public static AiChatMessageDto from(AiChatMessage entity) {
        return new AiChatMessageDto(
                entity.getId(),
                entity.getRole().name(),
                entity.getMessageText(),
                entity.getSuggestedTitle(),
                entity.getSuggestedContent(),
                entity.getSuggestedStartAt(),
                entity.getSuggestedEndAt(),
                entity.getSuggestedCategoryId(),
                entity.getRegisteredScheduleId(),
                entity.getCreatedAt());
    }
}
