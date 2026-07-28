package com.example.schedule_manager.domain.ai.dto;

// 유저 메시지 하나를 보내면 곧바로 그 유저 메시지와 AI 응답 메시지가 한 쌍으로 저장되므로,
// 프론트가 별도로 다시 조회하지 않고 바로 채팅창에 두 말풍선을 그릴 수 있게 함께 반환한다
public record AiChatExchangeDto(
        AiChatMessageDto userMessage,
        AiChatMessageDto assistantMessage
) {
}
