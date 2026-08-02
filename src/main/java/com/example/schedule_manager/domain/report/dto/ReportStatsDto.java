package com.example.schedule_manager.domain.report.dto;

import com.example.schedule_manager.domain.report.entity.ReportPeriod;

import java.time.LocalDate;
import java.util.List;

// AI 호출 없이 순수 집계로만 만드는 부분 - 항상 즉시/무료로 응답 가능해야 하므로 ReportInsightDto(AI 생성)와
// 별도 엔드포인트로 분리한다(ReportService 참고)
public record ReportStatsDto(
        ReportPeriod period,
        LocalDate rangeStart,
        LocalDate rangeEnd,
        long totalCount,
        double completionRate,
        List<StatusCountDto> statusCounts,
        List<CategoryStatDto> categoryBreakdown,
        PreviousPeriodComparisonDto previous
) {
}
