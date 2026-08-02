package com.example.schedule_manager.domain.report.dto;

import java.util.List;

// 카테고리별 선 그래프(기간 내 시간 흐름에 따른 추이) 데이터. 구간 단위는 기간에 따라 다르다 -
// WEEK/MONTH는 일 단위, YEAR는 월 단위(ReportService.buildCategoryTrend 참고). series는
// ReportStatsDto.categoryBreakdown과 같은 순서(건수 내림차순)라 프론트가 파이차트/범례와 같은 색을
// 그대로 재사용할 수 있다.
public record CategoryTrendDto(
        List<String> bucketLabels,
        List<CategorySeriesDto> series
) {
}
