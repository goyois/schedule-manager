package com.example.schedule_manager.domain.schedule.dto;

import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.querydsl.core.annotations.QueryProjection;

import java.time.LocalDateTime;

public record ScheduleResponseDto(
        Long id,
        String title,
        String content,
        LocalDateTime startAt,
        LocalDateTime endAt,
        ScheduleStatus status,
        String username,
        String categoryName
) {

    // 정규 생성자를 명시적으로 다시 선언하고 @QueryProjection 을 붙여, querydsl-apt 가 QScheduleResponseDto 를
    // 생성하게 한다 → ScheduleRepositoryImpl 에서 new QScheduleResponseDto(...) 로 컴파일 타임 타입 체크되는 projection 사용
    @QueryProjection
    public ScheduleResponseDto(Long id, String title, String content, LocalDateTime startAt, LocalDateTime endAt,
                                ScheduleStatus status, String username, String categoryName) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.startAt = startAt;
        this.endAt = endAt;
        this.status = status;
        this.username = username;
        this.categoryName = categoryName;
    }

    public static ScheduleResponseDto from(Schedule schedule) {
        return new ScheduleResponseDto(
                schedule.getId(),
                schedule.getTitle(),
                schedule.getContent(),
                schedule.getStartAt(),
                schedule.getEndAt(),
                schedule.getStatus(),
                schedule.getUser().getUsername(),
                schedule.getCategory().getName()
        );
    }
}
