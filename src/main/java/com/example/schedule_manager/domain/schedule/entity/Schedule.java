package com.example.schedule_manager.domain.schedule.entity;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "schedules", indexes = @Index(name = "idx_schedule_user_category", columnList = "user_id, category_id"))
public class Schedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long id;

    private String title;
    private String content;

    @Column(name = "start_at")
    private LocalDateTime startAt;

    @Column(name = "end_at")
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    private ScheduleStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    // 반복 일정(RecurringSchedule)에서 미리 생성된 occurrence 인지 표시한다 - 그 반복 규칙이 삭제돼도
    // 이미 지난/진행 중인 일정은 그대로 남겨야 하므로 하드 FK로 묶지 않는다(RecurringSchedule 삭제 시
    // PENDING 상태인 것만 정리하고, 이 컬럼 자체는 그대로 남아 "예전에 어떤 반복에서 나왔는지" 기록으로 쓰인다)
    @Column(name = "recurring_schedule_id")
    private Long recurringScheduleId;

    public void update(String title, String content, LocalDateTime startAt, LocalDateTime endAt,
                        ScheduleStatus status, Category category) {
        this.title = title;
        this.content = content;
        this.startAt = startAt;
        this.endAt = endAt;
        this.status = status;
        this.category = category;
    }
}
