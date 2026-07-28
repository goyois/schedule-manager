package com.example.schedule_manager.domain.ai.dto;

// ChatClient.entity()가 이 레코드의 필드로 JSON 스키마를 만들어 모델 응답 포맷을 강제하고, 받은 JSON을
// 이 타입으로 역직렬화한다. startAt/endAt을 LocalDateTime이 아닌 String으로 받는 이유: 모델이 형식을
// 어겨도(예: 다른 구분자) 여기서 예외로 죽지 않고, AiService에서 파싱 성공 여부를 직접 제어해 실패 시
// 해당 필드만 비운 채로 응답하기 위함. categoryId도 마찬가지로 AiService가 실제 사용자 카테고리
// 목록과 대조해 검증한 뒤에만 신뢰한다(모델이 존재하지 않는 id를 지어낼 수 있으므로).
public record AiScheduleSuggestion(
        String title,
        String content,
        String startAt,
        String endAt,
        Long categoryId,
        String reason
) {
}
