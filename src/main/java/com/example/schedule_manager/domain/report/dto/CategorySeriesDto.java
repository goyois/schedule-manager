package com.example.schedule_manager.domain.report.dto;

import java.util.List;

// counts는 CategoryTrendDto.bucketLabels와 같은 길이/순서 - counts.get(i)가 bucketLabels.get(i) 구간의
// 이 카테고리 일정 건수다
public record CategorySeriesDto(
        String categoryName,
        List<Long> counts
) {
}
