package com.example.schedule_manager.domain.auth.dto;

public record LoginRequestDto(
        String email,
        String password
) {
}
