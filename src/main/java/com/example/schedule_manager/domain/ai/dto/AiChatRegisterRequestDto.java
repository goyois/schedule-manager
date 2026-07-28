package com.example.schedule_manager.domain.ai.dto;

import jakarta.validation.constraints.NotNull;

public record AiChatRegisterRequestDto(
        @NotNull(message = "등록된 일정 id가 필요합니다.")
        Long scheduleId
) {
}
