package com.example.schedule_manager.domain.ai.dto;

import com.example.schedule_manager.domain.ai.entity.AiChatMessage;
import com.example.schedule_manager.domain.ai.entity.AiChatSuggestedSchedule;

import java.time.LocalDateTime;
import java.util.List;

public record AiChatMessageDto(
        Long id,
        String role,
        String message,
        String category,
        Long targetScheduleId,
        Long targetMandalartBoardId,
        String suggestedTitle,
        String suggestedContent,
        LocalDateTime suggestedStartAt,
        LocalDateTime suggestedEndAt,
        Long suggestedCategoryId,
        Long registeredScheduleId,
        List<SuggestedScheduleItemDto> suggestedItems,
        LocalDateTime createdAt
) {
    // SCHEDULE_RECOMMENDATION일 때만 채워진다(한 번에 여러 일정을 제안할 수 있어 항목마다 하나씩) -
    // 위 suggestedTitle 등 단일 필드는 이 스키마 변경 이전에 저장된 옛 SCHEDULE_RECOMMENDATION 행을 위한
    // 하위호환용이고, 새 SCHEDULE_RECOMMENDATION 행은 이 리스트만 채워진다(단일 필드는 전부 null)
    public record SuggestedScheduleItemDto(
            Long id,
            String title,
            String content,
            LocalDateTime startAt,
            LocalDateTime endAt,
            Long categoryId,
            Long registeredScheduleId
    ) {
        public static SuggestedScheduleItemDto from(AiChatSuggestedSchedule entity) {
            return new SuggestedScheduleItemDto(
                    entity.getId(),
                    entity.getTitle(),
                    entity.getContent(),
                    entity.getStartAt(),
                    entity.getEndAt(),
                    entity.getCategoryId(),
                    entity.getRegisteredScheduleId());
        }
    }

    public static AiChatMessageDto from(AiChatMessage entity) {
        return new AiChatMessageDto(
                entity.getId(),
                entity.getRole().name(),
                entity.getMessageText(),
                entity.getCategory() != null ? entity.getCategory().name() : null,
                entity.getTargetScheduleId(),
                entity.getTargetMandalartBoardId(),
                entity.getSuggestedTitle(),
                entity.getSuggestedContent(),
                entity.getSuggestedStartAt(),
                entity.getSuggestedEndAt(),
                entity.getSuggestedCategoryId(),
                entity.getRegisteredScheduleId(),
                entity.getSuggestedSchedules().stream().map(SuggestedScheduleItemDto::from).toList(),
                entity.getCreatedAt());
    }
}
