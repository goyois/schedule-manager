package com.example.schedule_manager.global.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    // spring-ai-anthropic-spring-boot-starter 가 ChatModel(AnthropicChatModel) 빈을 자동 구성해준다 -
    // 여기서는 그 위에 얹는 ChatClient 만 만들면 된다
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
