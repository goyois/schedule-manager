package com.example.schedule_manager.domain.schedule.service;

import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScheduleEmbeddingService - AiService의 고정 2주 윈도우를 보강하는 일정 RAG.
 * 색인/검색/삭제 모두 VectorStore 장애 시에도 예외를 밖으로 던지지 않는지(fail-open)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleEmbeddingServiceTest {

    @Mock
    private VectorStore vectorStore;

    @InjectMocks
    private ScheduleEmbeddingService scheduleEmbeddingService;

    private Schedule schedule(Long id, String title, String content) {
        User user = User.builder().id(1L).username("tester").email("tester@example.com").userType(UserType.USER).build();
        return Schedule.builder().id(id).title(title).content(content).status(ScheduleStatus.PENDING).user(user).build();
    }

    @Test
    @DisplayName("색인 성공 - title+content를 합쳐 문서로 만들고 docType/userId/scheduleId를 메타데이터에 담는다")
    void reindexSchedule_success_addsDocumentWithMetadata() {
        Schedule schedule = schedule(100L, "팀 회의", "분기 목표 논의");

        scheduleEmbeddingService.reindexSchedule(1L, schedule);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Document document = captor.getValue().get(0);
        assertThat(document.getContent()).isEqualTo("팀 회의\n분기 목표 논의");
        assertThat(document.getMetadata().get("docType")).isEqualTo("schedule");
        assertThat(document.getMetadata().get("userId")).isEqualTo(1L);
        assertThat(document.getMetadata().get("scheduleId")).isEqualTo(100L);
    }

    @Test
    @DisplayName("색인 성공 - content가 비어 있으면 title만으로 문서를 만든다")
    void reindexSchedule_blankContent_usesTitleOnly() {
        Schedule schedule = schedule(101L, "병원 예약", "");

        scheduleEmbeddingService.reindexSchedule(1L, schedule);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertThat(captor.getValue().get(0).getContent()).isEqualTo("병원 예약");
    }

    @Test
    @DisplayName("색인 실패 흡수 - VectorStore.add가 예외를 던져도 밖으로 전파하지 않는다(fail-open)")
    void reindexSchedule_vectorStoreThrows_doesNotPropagate() {
        org.mockito.Mockito.doThrow(new RuntimeException("OpenAI 장애")).when(vectorStore).add(anyList());

        scheduleEmbeddingService.reindexSchedule(1L, schedule(100L, "회의", ""));
        // 예외 없이 정상 반환되면 성공
    }

    @Test
    @DisplayName("유사도 검색 성공 - 결과 문서의 scheduleId만 추출하고 본인 소유/docType 필터를 건다")
    void findSimilarScheduleIds_success_returnsScheduleIds() {
        Document doc1 = new Document("id1", "팀 회의", Map.of("scheduleId", 100L));
        Document doc2 = new Document("id2", "다른 회의", Map.of("scheduleId", 101L));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc1, doc2));

        List<Long> ids = scheduleEmbeddingService.findSimilarScheduleIds(1L, "회의 관련 일정", Set.of(200L), 5);

        assertThat(ids).containsExactly(100L, 101L);

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        SearchRequest sentRequest = requestCaptor.getValue();
        assertThat(sentRequest.getQuery()).isEqualTo("회의 관련 일정");
        // excludeIds는 필터(nin)로 내려보내지 않고 애플리케이션에서 걸러내므로, 그만큼 더 넉넉히 요청한다
        assertThat(sentRequest.getTopK()).isEqualTo(6);
        assertThat(sentRequest.hasFilterExpression()).isTrue();
        // 유사도 하한선 없이 topK를 무조건 채우던 버그의 재발 방지 - 실측(text-embedding-3-small,
        // 한국어) 기준 0.35 미만은 대부분 무관했다는 근거로 고정한 값
        assertThat(sentRequest.getSimilarityThreshold()).isEqualTo(0.35);
    }

    @Test
    @DisplayName("유사도 검색 성공 - excludeIds에 해당하는 결과는 nin 필터가 아니라 애플리케이션에서 걸러낸다")
    void findSimilarScheduleIds_success_filtersExcludeIdsInApplicationCode() {
        // spring-ai 1.0.0-M1의 PgVectorFilterExpressionConverter는 nin을 유효하지 않은 jsonpath로
        // 렌더링해 DB에서 그대로 깨지므로, 필터에는 excludeIds를 절대 싣지 않는지가 이 테스트의 핵심이다
        Document excluded = new Document("id1", "제외 대상", Map.of("scheduleId", 200L));
        Document kept = new Document("id2", "유지 대상", Map.of("scheduleId", 101L));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(excluded, kept));

        List<Long> ids = scheduleEmbeddingService.findSimilarScheduleIds(1L, "회의", Set.of(200L), 5);

        assertThat(ids).containsExactly(101L);

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getFilterExpression().toString()).doesNotContain("200");
    }

    @Test
    @DisplayName("유사도 검색 성공 - excludeIds가 비어 있어도 정상적으로 필터를 구성한다")
    void findSimilarScheduleIds_emptyExcludeIds_stillSearches() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        List<Long> ids = scheduleEmbeddingService.findSimilarScheduleIds(1L, "회의", Set.of(), 5);

        assertThat(ids).isEmpty();
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("유사도 검색 실패 흡수 - VectorStore 검색이 예외를 던지면 빈 목록을 반환한다(fail-open)")
    void findSimilarScheduleIds_vectorStoreThrows_returnsEmptyList() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("OpenAI 장애"));

        List<Long> ids = scheduleEmbeddingService.findSimilarScheduleIds(1L, "회의", Set.of(), 5);

        assertThat(ids).isEmpty();
    }

    @Test
    @DisplayName("일정 삭제 시 임베딩도 함께 삭제 요청한다")
    void deleteScheduleEmbedding_success_deletesDocument() {
        scheduleEmbeddingService.deleteScheduleEmbedding(100L);

        verify(vectorStore).delete(anyList());
    }

    @Test
    @DisplayName("임베딩 삭제 실패 흡수 - VectorStore.delete가 예외를 던져도 밖으로 전파하지 않는다(fail-open)")
    void deleteScheduleEmbedding_vectorStoreThrows_doesNotPropagate() {
        when(vectorStore.delete(anyList())).thenThrow(new RuntimeException("DB 장애"));

        scheduleEmbeddingService.deleteScheduleEmbedding(100L);
        // 예외 없이 정상 반환되면 성공
    }
}
