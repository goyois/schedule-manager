package com.example.schedule_manager.global.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.autoconfigure.anthropic.AnthropicChatProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class AiConfig {

    // 만다라트 한 번 채우기는 최대 64칸짜리 구조화 응답이라 일반 채팅(기본 max-tokens)보다 훨씬 더
    // 큰 출력이 필요하다 - 부족하면 JSON이 중간에 잘려 파싱 자체가 실패한다(MandalartAiService 참고)
    private static final int MANDALART_FILL_MAX_TOKENS = 4096;

    // spring-ai-anthropic-spring-boot-starter의 AnthropicChatProperties 기본 max_tokens(500)는 원래
    // SCHEDULE_RECOMMENDATION이 한 번에 하나만 추천하던 시절엔 충분했지만, AiService.SYSTEM_PROMPT가
    // scheduleItems 배열로 한 턴에 최대 10개까지(각각 title/content/startAt/endAt/categoryId 포함, content가
    // 운동 세트/횟수처럼 장문일 수 있음) 추천하게 되면서 JSON 응답이 중간에 잘려 BeanOutputConverter 파싱
    // 자체가 실패하는 사례가 생겼다 - 만다라트 채우기와 같은 이유로 그보다 넉넉한 값을 명시적으로 오버라이드한다
    private static final int CHAT_MAX_TOKENS = 4096;

    // spring-ai-anthropic-spring-boot-starter(1.0.0-M1)의 자동 구성은 AnthropicChatProperties 생성자에서
    // temperature 를 항상 기본값으로 채워 매 요청에 temperature 파라미터를 실어 보낸다. 그런데 설정된 모델
    // (claude-opus-4-8)은 temperature 파라미터 자체를 지원하지 않아 "temperature is deprecated for this model"
    // 400 에러로 거부한다. AnthropicChatOptions 에서 temperature 를 아예 세팅하지 않으면
    // (@JsonInclude(NON_NULL)이라) 요청 JSON에서 필드 자체가 빠지므로, 그런 옵션으로 ChatModel 빈을 직접 만들어
    // 자동 구성된 anthropicChatModel 빈을 오버라이드한다(자동 구성은 @ConditionalOnMissingBean).
    // max_tokens 는 Anthropic API에서 필수 필드이므로(temperature와 달리 생략 불가) chatProperties 의 기본값
    // (DEFAULT_MAX_TOKENS=500) 대신 CHAT_MAX_TOKENS를 명시적으로 실어 보낸다 - 빠뜨리면
    // "max_tokens: Field required" 400 에러가 난다.
    @Bean
    @Qualifier("chatModel")
    public ChatModel chatModel(AnthropicApi anthropicApi, AnthropicChatProperties chatProperties, RetryTemplate retryTemplate) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .withModel(chatProperties.getOptions().getModel())
                .withMaxTokens(CHAT_MAX_TOKENS)
                .build();
        return new AnthropicChatModel(anthropicApi, options, retryTemplate);
    }

    @Bean
    public ChatClient chatClient(@Qualifier("chatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    // MandalartAiService 전용 - 나머지는 chatModel과 동일하고 max_tokens만 더 크다
    @Bean
    @Qualifier("mandalartFillChatModel")
    public ChatModel mandalartFillChatModel(AnthropicApi anthropicApi, AnthropicChatProperties chatProperties, RetryTemplate retryTemplate) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .withModel(chatProperties.getOptions().getModel())
                .withMaxTokens(MANDALART_FILL_MAX_TOKENS)
                .build();
        return new AnthropicChatModel(anthropicApi, options, retryTemplate);
    }

    @Bean
    public ChatClient mandalartFillChatClient(@Qualifier("mandalartFillChatModel") ChatModel mandalartFillChatModel) {
        return ChatClient.builder(mandalartFillChatModel).build();
    }
}
