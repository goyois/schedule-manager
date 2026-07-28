package com.example.schedule_manager.domain.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiChatMessageRequestDto(
        @NotBlank(message = "메시지를 입력해주세요.")
        @Size(max = 500, message = "메시지는 500자를 넘을 수 없습니다.")
        String message
) {
}
