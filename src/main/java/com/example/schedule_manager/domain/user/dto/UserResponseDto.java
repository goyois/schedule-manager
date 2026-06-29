package com.example.schedule_manager.domain.user.dto;

import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;

public record UserResponseDto(
        Long id,
        String username,
        String email,
        UserType userType
) {
    public static UserResponseDto from(User user) {
        return new UserResponseDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getUserType()
        );
    }
}
