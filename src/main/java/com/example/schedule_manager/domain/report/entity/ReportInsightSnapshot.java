package com.example.schedule_manager.domain.report.entity;

import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

// AI 코멘트(ReportInsightDto)를 생성할 때마다 다시 Anthropic을 호출하지 않도록, 유저×기간(period)×
// 구간 시작일(rangeStart) 조합마다 마지막으로 생성된 결과 하나만 저장해둔다(그 이전 결과는 덮어쓴다 -
// 히스토리를 남길 필요는 없다는 판단). strengths/improvements는 리스트지만 별도 컬렉션 테이블 없이
// ReportService.ITEM_DELIMITER로 이어붙인 문자열 하나로 저장한다(RecurringSchedule.daysOfWeek의
// CSV 컬럼 패턴과 같은 이유 - 항목이 몇 개 안 되고 관계형 조회가 필요 없다).
//
// scheduleCount/latestScheduleUpdatedAt은 "생성 시점에 이 기간 범위 안에 있던 일정들의 스냅샷"이다 -
// ReportService.getCachedInsight가 매 조회 시 현재 값과 다시 비교해서(개수가 다르거나, 그 안의 일정
// 중 하나라도 더 최근에 수정됐으면) 이 결과가 오래됐는지(stale) 판단한다. 일정이 삭제되거나 이 기간
// 밖으로 옮겨지면 개수가 달라지고, 내용/상태만 바뀌면 최신 수정 시각이 달라지므로 두 값의 조합으로
// 대부분의 변경을 잡아낼 수 있다(둘 다 같은데 실제로는 바뀐 경우 - 예: 같은 시각에 삭제와 생성이 동시에
// 일어나 개수가 우연히 같고 남은 일정들의 최신 수정 시각도 우연히 같은 경우 - 는 이론상 있을 수 있지만
// 무시해도 될 만큼 드물다).
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "report_insight_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_report_insight_snapshot", columnNames = {"user_id", "period", "range_start"}))
public class ReportInsightSnapshot extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_insight_snapshot_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportPeriod period;

    @Column(name = "range_start", nullable = false)
    private LocalDate rangeStart;

    @Column(name = "range_end", nullable = false)
    private LocalDate rangeEnd;

    @Column(length = 2000)
    private String strengths;

    @Column(length = 2000)
    private String improvements;

    @Column(name = "behavior_pattern", length = 1000)
    private String behaviorPattern;

    @Column(name = "personality_note", length = 1000)
    private String personalityNote;

    @Column(name = "schedule_count", nullable = false)
    private long scheduleCount;

    // 그 기간에 일정이 하나도 없었으면 null
    @Column(name = "latest_schedule_updated_at")
    private LocalDateTime latestScheduleUpdatedAt;

    public void update(String strengths, String improvements, String behaviorPattern, String personalityNote,
                        long scheduleCount, LocalDateTime latestScheduleUpdatedAt) {
        this.strengths = strengths;
        this.improvements = improvements;
        this.behaviorPattern = behaviorPattern;
        this.personalityNote = personalityNote;
        this.scheduleCount = scheduleCount;
        this.latestScheduleUpdatedAt = latestScheduleUpdatedAt;
    }
}
