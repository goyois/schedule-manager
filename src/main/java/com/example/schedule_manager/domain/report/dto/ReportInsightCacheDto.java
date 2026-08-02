package com.example.schedule_manager.domain.report.dto;

import java.time.LocalDateTime;

// GET /api/reports/insight/cached 응답 - AI를 호출하지 않고(요금/레이트리밋 없음) 저장된 마지막 결과만
// 돌려준다. insight가 null이면 이 기간에 대해 아직 한 번도 생성한 적이 없다는 뜻(프론트는 이때 기존처럼
// "AI 코멘트 생성" 버튼만 보여준다). insight가 있으면 stale로 그 이후 일정이 바뀌었는지 알려준다 -
// true면 프론트가 저장된 결과는 그대로 보여주면서 "스케줄 변동으로 새로운 결과를 얻을 수 있습니다"
// 안내와 함께 재생성 버튼을 같이 띄운다.
public record ReportInsightCacheDto(
        ReportInsightDto insight,
        boolean stale,
        LocalDateTime generatedAt
) {
}
