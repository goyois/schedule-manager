package com.example.schedule_manager.domain.schedule.dto;

import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record ScheduleRequestDto(
        @NotBlank(message = "제목을 입력해주세요.")
        String title,

        String content,

        @NotNull(message = "시작 시각을 입력해주세요.")
        LocalDateTime startAt,

        @NotNull(message = "종료 시각을 입력해주세요.")
        LocalDateTime endAt,

        @NotNull(message = "상태를 선택해주세요.")
        ScheduleStatus status,

        // ScheduleService.updateSchedule()은 이 필드를 사용하지 않는다(소유자는 이미 존재하는 일정에서 정해짐) —
        // update/상태변경 요청은 이 값을 null로 보낼 수 있어 필수로 강제하지 않는다
        Long userId,

        @NotNull(message = "카테고리를 선택해주세요.")
        Long categoryId
) {
}
