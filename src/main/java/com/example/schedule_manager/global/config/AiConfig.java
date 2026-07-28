package com.example.schedule_manager.global.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.autoconfigure.anthropic.AnthropicChatProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class AiConfig {

    // spring-ai-anthropic-spring-boot-starter(1.0.0-M1)의 자동 구성은 AnthropicChatProperties 생성자에서
    // temperature 를 항상 기본값으로 채워 매 요청에 temperature 파라미터를 실어 보낸다. 그런데 설정된 모델
    // (claude-opus-4-8)은 temperature 파라미터 자체를 지원하지 않아 "temperature is deprecated for this model"
    // 400 에러로 거부한다. AnthropicChatOptions 에서 temperature 를 아예 세팅하지 않으면
    // (@JsonInclude(NON_NULL)이라) 요청 JSON에서 필드 자체가 빠지므로, 그런 옵션으로 ChatModel 빈을 직접 만들어
    // 자동 구성된 anthropicChatModel 빈을 오버라이드한다(자동 구성은 @ConditionalOnMissingBean).
    // max_tokens 는 Anthropic API에서 필수 필드이므로(temperature와 달리 생략 불가) chatProperties 의 기본값
    // (DEFAULT_MAX_TOKENS=500)을 그대로 옮겨 실어 보낸다 - 빠뜨리면 "max_tokens: Field required" 400 에러가 난다.
    @Bean
    public ChatModel chatModel(AnthropicApi anthropicApi, AnthropicChatProperties chatProperties, RetryTemplate retryTemplate) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .withModel(chatProperties.getOptions().getModel())
                .withMaxTokens(chatProperties.getOptions().getMaxTokens())
                .build();
        return new AnthropicChatModel(anthropicApi, options, retryTemplate);
    }

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
