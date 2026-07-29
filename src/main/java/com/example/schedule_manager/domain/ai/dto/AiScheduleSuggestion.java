package com.example.schedule_manager.domain.ai.dto;

import com.example.schedule_manager.domain.ai.entity.AiResponseCategory;

// ChatClient.entity()가 이 레코드의 필드로 JSON 스키마를 만들어 모델 응답 포맷을 강제하고, 받은 JSON을
// 이 타입으로 역직렬화한다. startAt/endAt을 LocalDateTime이 아닌 String으로 받는 이유: 모델이 형식을
// 어겨도(예: 다른 구분자) 여기서 예외로 죽지 않고, AiService에서 파싱 성공 여부를 직접 제어해 실패 시
// 해당 필드만 비운 채로 응답하기 위함. categoryId도 마찬가지로 AiService가 실제 사용자 카테고리
// 목록과 대조해 검증한 뒤에만 신뢰한다(모델이 존재하지 않는 id를 지어낼 수 있으므로).
//
// category: 모델이 이번 답변을 SCHEDULE_RECOMMENDATION(새 일정 추천) / SCHEDULE_UPDATE(기존 일정 수정
// 제안) / GENERAL(그 외 일반 답변) 중 어느 쪽으로 분류했는지. 프론트가 등록/수정 UI를 보여줄지, 보여준다면
// 어느 동작을 붙일지를 이 값 하나로 결정하므로, title 등 나머지 필드가 비어있는지로 추론하지 않고 모델이
// 먼저 명시적으로 분류하게 한다 - 구조화 출력에서 이렇게 먼저 분류값을 결정하게 하면 이후 필드를 채울지
// 말지도 그 결정을 따라가 일관성이 높아진다. AiService는 GENERAL이면 나머지 필드를 모델이 뭘 채워
// 보냈든 무시하고 강제로 비운다(방어적 처리).
//
// targetScheduleId: SCHEDULE_UPDATE일 때만 의미가 있다 - [기존 일정] 컨텍스트에 함께 실어 보낸 id 중
// 사용자가 바꾸고 싶어하는 일정의 id. AiService가 실제로 요청자 소유의 일정인지 검증한 뒤에만 신뢰한다
// (모델이 존재하지 않거나 다른 유저의 id를 지어낼 수 있으므로, categoryId 검증과 같은 이유).
public record AiScheduleSuggestion(
        AiResponseCategory category,
        Long targetScheduleId,
        String title,
        String content,
        String startAt,
        String endAt,
        Long categoryId,
        String reason
) {
}
