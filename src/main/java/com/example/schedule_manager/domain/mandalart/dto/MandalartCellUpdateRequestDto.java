package com.example.schedule_manager.domain.mandalart.dto;

import jakarta.validation.constraints.Size;

// 셀 내용은 비워서 지우는 것이 정상 흐름이라 @NotBlank 를 걸지 않는다
public record MandalartCellUpdateRequestDto(
        @Size(max = 200, message = "셀 내용은 200자를 넘을 수 없습니다.")
        String content
) {
}
