package com.example.schedule_manager.domain.report.dto;

import java.util.List;

// ChatClient.entity(ReportInsightDto.class)가 이 레코드의 필드로 JSON 스키마를 만들어 모델 응답 포맷을
// 강제한다(AiScheduleSuggestion과 같은 패턴) - 별도 채팅 기록에 저장하지 않고 응답으로 그대로 내려주는
// 일회성 결과라 AiChatMessage 같은 영속 엔티티가 필요 없다. ReportService.sanitizeInsight가 null 리스트를
// 빈 리스트로, 개수 상한을 넘는 항목은 잘라서 반환한다(모델이 과도하게 채워 보내는 경우 방어).
public record ReportInsightDto(
        List<String> strengths,
        List<String> improvements,
        String behaviorPattern,
        String personalityNote
) {
}
