package com.example.schedule_manager.domain.recurringschedule.entity;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

// 실제 일정(Schedule) 행을 반복적으로 찍어내는 "틀"이다. 이 규칙 자체는 상태(대기/진행중/완료)를
// 갖지 않는다 - 상태는 이 규칙에서 미리 생성해둔 개별 Schedule(occurrence)마다 따로 관리되므로,
// 보드/캘린더/자동 상태 전환/AI 추천 컨텍스트 등 기존 일정 관련 기능을 하나도 건드릴 필요가 없다.
// MVP라 규칙 자체의 수정은 지원하지 않는다(생성/삭제만) - "이후 전체 수정"은 삭제 범위·재생성 로직이
// 얽혀 복잡해서 다음 단계로 미뤘다
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "recurring_schedules")
public class RecurringSchedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recurring_schedule_id")
    private Long id;

    private String title;
    private String content;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    // 없으면 알림형(종료 시각 없는) occurrence로 생성된다
    @Column(name = "end_time")
    private LocalTime endTime;

    // "MONDAY,WEDNESDAY,FRIDAY" 형태로 저장한다 - 요일 하나 고르는 데 별도 조인 테이블까지 두기엔
    // 과하다고 판단했고, Set<DayOfWeek> 변환은 RecurringScheduleService에서 담당한다
    @Column(name = "days_of_week", nullable = false)
    private String daysOfWeek;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    // null이면 무기한 반복
    @Column(name = "end_date")
    private LocalDate endDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;
}
