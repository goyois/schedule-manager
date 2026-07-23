package com.example.schedule_manager.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequestDto(
        @NotBlank(message = "구글 ID 토큰이 없습니다.")
        String idToken
) {
}
