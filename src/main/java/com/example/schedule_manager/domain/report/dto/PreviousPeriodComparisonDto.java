package com.example.schedule_manager.domain.report.dto;

// 같은 길이의 직전 기간(예: 이번 주 vs 지난 주) 대비 비교치. totalCountDelta/completionRateDelta는
// "이번 기간 - 직전 기간"이므로 양수면 늘었다는 뜻이다.
public record PreviousPeriodComparisonDto(
        long totalCount,
        double completionRate,
        long totalCountDelta,
        double completionRateDelta
) {
}
