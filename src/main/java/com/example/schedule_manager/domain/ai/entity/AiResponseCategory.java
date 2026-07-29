package com.example.schedule_manager.domain.ai.entity;

// AI 응답을 세 갈래로 분류한다 - 프론트는 이 값으로 등록/수정 UI를 보여줄지, 보여준다면 어느 쪽인지
// 결정한다(AiChatMessageDto.category 참고). GENERAL이면 등록/수정 절차 자체를 숨기고 답변 텍스트만 보여준다.
public enum AiResponseCategory {
    // 새 일정 하나를 실행 가능한 형태로 추천 - title/startAt/endAt/categoryId를 채운다
    SCHEDULE_RECOMMENDATION,
    // 이미 등록된 일정 하나를 바꾸자는 제안 - targetScheduleId로 대상을 가리키고, 나머지 필드는
    // "바뀐 뒤의 값"을 채운다(바뀌지 않는 필드는 기존 값을 그대로 반복해서 채운다)
    SCHEDULE_UPDATE,
    // 그 외 모든 경우(잡담, 조회/설명 등) - 등록/수정과 무관한 일반 답변
    GENERAL
}
