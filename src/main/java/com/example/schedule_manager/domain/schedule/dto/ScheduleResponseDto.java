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
        String categoryName,
        // ReportService가 기간별 AI 인사이트 캐시(ReportInsightSnapshot)의 최신성 판단에 쓴다(건수 +
        // 이 값들 중 최댓값을 "핑거프린트"로 저장해뒀다가 다시 계산해 비교) - 그 외 화면(캘린더/보드 등)은
        // 이 필드를 그냥 무시한다
        LocalDateTime updatedAt
) {

    // 정규 생성자를 명시적으로 다시 선언하고 @QueryProjection 을 붙여, querydsl-apt 가 QScheduleResponseDto 를
    // 생성하게 한다 → ScheduleRepositoryImpl 에서 new QScheduleResponseDto(...) 로 컴파일 타임 타입 체크되는 projection 사용
    @QueryProjection
    public ScheduleResponseDto(Long id, String title, String content, LocalDateTime startAt, LocalDateTime endAt,
                                ScheduleStatus status, String username, String categoryName, LocalDateTime updatedAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.startAt = startAt;
        this.endAt = endAt;
        this.status = status;
        this.username = username;
        this.categoryName = categoryName;
        this.updatedAt = updatedAt;
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
                schedule.getCategory().getName(),
                schedule.getUpdatedAt()
        );
    }
}
