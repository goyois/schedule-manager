package com.example.schedule_manager.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

// RAG 임베딩 색인/삭제(ScheduleEmbeddingService, MandalartGoalEmbeddingService) 전용 executor.
// 이 호출들은 OpenAI 임베딩 API + pgvector 왕복이 끝날 때까지 원래 요청 스레드(일정 생성/수정/삭제,
// 만다라트 셀 저장)를 블로킹하고 있었다 - RAG는 나중에 AI 챗봇/만다라트 채우기가 참고용으로 쓰는
// 보강 데이터일 뿐이라, 그 결과를 기다릴 이유가 없다(fail-open으로 이미 실패는 무시하던 것과 같은 이유로
// 지연도 무시해도 된다). @Async 메서드는 스레드 풀 이름을 명시로 지정한다 - 빈 이름 기반 기본 매칭에
// 기대지 말라는 게 이 코드베이스의 기존 방침(Lombok+@Qualifier 관련 CLAUDE.md 노트와 동일한 이유).
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "embeddingTaskExecutor")
    public Executor embeddingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("embedding-");
        executor.initialize();
        return executor;
    }
}
