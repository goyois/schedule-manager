package com.example.schedule_manager.domain.schedule.service;

import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

// AS-IS(변경 전: reindexSchedule을 ScheduleService.createSchedule/updateSchedule에서 동기 호출)와
// TO-BE(@Async 전환 후)의 호출자 블로킹 시간을 같은 테스트 안에서 직접 비교한다. 실제 OpenAI 임베딩 API
// 왕복은 네트워크 변동성이 커서 재현 가능한 숫자를 못 얻으므로, VectorStore.add를 인위적 지연으로
// 대체해 "임베딩 API가 N ms 걸릴 때 호출자가 그 시간을 그대로 떠안는지"만 격리해서 측정한다.
//
// AS-IS는 순수 객체(new ScheduleEmbeddingService(...))로 직접 호출해 재현한다 - @Async는 스프링이
// 관리하는 빈에 씌운 AOP 프록시로만 동작하므로, 프록시를 거치지 않는 순수 객체 호출은 이번 변경 전
// 코드와 동일하게(=호출 스레드에서 그대로 블로킹) 동작한다.
@Tag("performance")
@Slf4j
@SpringBootTest
class ScheduleEmbeddingAsyncPerformanceTest {

    private static final long SIMULATED_EMBEDDING_LATENCY_MS = 300;

    @MockitoBean
    private VectorStore vectorStore;

    @Autowired
    private ScheduleEmbeddingService proxiedScheduleEmbeddingService;

    @Test
    @DisplayName("AS-IS(동기 호출) vs TO-BE(@Async) - 임베딩 API 지연이 호출자를 블로킹하는지 비교")
    void reindexSchedule_asyncDoesNotBlockCaller() {
        doAnswer(invocation -> {
            Thread.sleep(SIMULATED_EMBEDDING_LATENCY_MS);
            return null;
        }).when(vectorStore).add(anyList());

        Schedule schedule = Schedule.builder()
                .id(1L)
                .title("팀 회의")
                .content("분기 목표 논의")
                .status(ScheduleStatus.PENDING)
                .user(User.builder().id(1L).username("tester").email("tester@example.com").userType(UserType.USER).build())
                .build();

        ScheduleEmbeddingService rawService = new ScheduleEmbeddingService(vectorStore);
        long asIsStart = System.nanoTime();
        rawService.reindexSchedule(1L, schedule);
        long asIsMillis = (System.nanoTime() - asIsStart) / 1_000_000;

        long toBeStart = System.nanoTime();
        proxiedScheduleEmbeddingService.reindexSchedule(1L, schedule);
        long toBeMillis = (System.nanoTime() - toBeStart) / 1_000_000;

        log.info("AS-IS(동기): {}ms / TO-BE(@Async 반환): {}ms (시뮬레이션 임베딩 지연={}ms)",
                asIsMillis, toBeMillis, SIMULATED_EMBEDDING_LATENCY_MS);

        assertThat(asIsMillis).isGreaterThanOrEqualTo(SIMULATED_EMBEDDING_LATENCY_MS);
        assertThat(toBeMillis).isLessThan(SIMULATED_EMBEDDING_LATENCY_MS / 3);

        // TO-BE 호출도 결국 실제로 실행은 되는지(그냥 유실되는 게 아니라 백그라운드에서 완료되는지) 확인
        verify(vectorStore, timeout(SIMULATED_EMBEDDING_LATENCY_MS * 3).times(2)).add(anyList());
    }
}
