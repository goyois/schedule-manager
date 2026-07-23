package com.example.schedule_manager.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequestDto(
        @NotBlank(message = "리프레시 토큰이 없습니다.")
        String refreshToken
) {
}
