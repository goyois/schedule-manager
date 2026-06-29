package com.example.schedule_manager.domain.user.dto;

import com.example.schedule_manager.domain.user.entity.UserType;

public record UserRequestDto(
        String username,
        String password,
        String email,
        UserType userType
) {
}
