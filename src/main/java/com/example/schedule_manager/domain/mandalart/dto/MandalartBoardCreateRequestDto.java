package com.example.schedule_manager.domain.mandalart.dto;

import jakarta.validation.constraints.NotBlank;

public record MandalartBoardCreateRequestDto(
        @NotBlank(message = "만다라트 제목을 입력해주세요.")
        String title
) {
}
