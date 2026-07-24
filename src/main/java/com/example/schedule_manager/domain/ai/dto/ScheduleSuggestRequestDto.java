package com.example.schedule_manager.domain.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ScheduleSuggestRequestDto(
        @NotBlank(message = "무엇을 추천받고 싶은지 입력해주세요.")
        @Size(max = 500, message = "요청 내용은 500자를 넘을 수 없습니다.")
        String prompt
) {
}
