package com.example.schedule_manager.domain.schedule.service;

import com.example.schedule_manager.domain.schedule.entity.Schedule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// AiService.sendMessage 가 매 턴 참고하는 "최근 2주~향후 2주" 고정 윈도우 컨텍스트를 보강한다 - 그 윈도우
// 밖(더 예전이거나 훨씬 나중)에 있는 일정이라도 지금 대화 내용과 의미적으로 비슷하면 pgvector로 찾아내
// 프롬프트에 추가로 얹어줄 수 있게 한다(윈도우를 대체하지 않고 보강). 검색은 항상 요청자 본인 소유
// 일정으로만 스코프한다.
//
// MandalartGoalEmbeddingService와 같은 VectorStore(테이블 하나를 공유)를 쓰므로, 문서마다
// "docType"="schedule" 메타데이터로 그쪽 문서와 섞이지 않게 구분한다.
//
// 색인/검색/삭제 모두 실패해도 원래 흐름(일정 저장, 챗봇 응답)을 막지 않는다(fail-open) - 만다라트
// RAG와 동일한 패턴.
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleEmbeddingService {

    private static final String DOC_TYPE = "schedule";

    // 코사인 유사도 하한선 - text-embedding-3-small(한국어)로 실측한 결과(2026-08-03, 등산/프로젝트
    // 발표 질의 각 6개 후보 기준), 실제로 관련 있는 일정끼리는 0.39~0.49, 무관한 일정은 0.18~0.34
    // 범위였다. 완전히 분리되는 값은 아니지만(무관한 항목이 0.34까지 올라간 사례도 있었음), 0.35 밑으로는
    // 대부분 무관했다 - topK를 무조건 채워서 "그나마 제일 나은 걸" 끼워 넣는 대신, 이 값 미만은 버리고
    // 차라리 RAG 보강 없이 진행하는 쪽을 택했다.
    private static final double SIMILARITY_THRESHOLD = 0.35;

    private final VectorStore vectorStore;

    @Async("embeddingTaskExecutor")
    public void reindexSchedule(Long userId, Schedule schedule) {
        try {
            String content = schedule.getContent() == null || schedule.getContent().isBlank()
                    ? schedule.getTitle()
                    : schedule.getTitle() + "\n" + schedule.getContent();

            Document document = new Document(
                    scheduleDocumentId(schedule.getId()),
                    content,
                    Map.of("docType", DOC_TYPE, "userId", userId, "scheduleId", schedule.getId())
            );
            vectorStore.add(List.of(document));
        } catch (Exception e) {
            log.warn("일정 임베딩 색인 실패 - 무시하고 계속 진행 (scheduleId={})", schedule.getId(), e);
        }
    }

    // queryText와 의미적으로 비슷한, 요청자 본인의 다른 일정 id만 반환한다 - 표시용 텍스트는 호출부
    // (AiService)가 이미 들고 있는 최신 ScheduleResponseDto에서 재구성하므로 여기서는 id만 넘기면 된다
    // (메타데이터로 오래된 상태를 보여줄 위험이 없음).
    //
    // excludeIds는 필터 표현식(nin)으로 내려보내지 않고 결과를 받아온 뒤 애플리케이션 코드에서 걸러낸다 -
    // 이 프로젝트가 쓰는 spring-ai 1.0.0-M1의 PgVectorFilterExpressionConverter는 nin을
    // "$.scheduleId nin [1,2]" 식으로 렌더링하는데, 이건 유효한 Postgres jsonpath 문법이 아니라
    // (jsonpath에 그런 nin 연산자가 없음) DB에서 "syntax error ... of jsonpath input"으로 그대로 깨진다
    // (실제 jar의 PgVectorFilterExpressionConverter를 직접 호출해 확인한 결과). eq/ne만 써서 필터를 만들고,
    // 제외는 topK보다 넉넉히 가져온 뒤 걸러내는 방식으로 우회한다.
    public List<Long> findSimilarScheduleIds(Long userId, String queryText, Set<Long> excludeIds, int topK) {
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression filter = b.and(b.eq("docType", DOC_TYPE), b.eq("userId", userId)).build();

            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.query(queryText)
                            .withTopK(topK + excludeIds.size())
                            .withSimilarityThreshold(SIMILARITY_THRESHOLD)
                            .withFilterExpression(filter));
            return results.stream()
                    .map(d -> ((Number) d.getMetadata().get("scheduleId")).longValue())
                    .filter(id -> !excludeIds.contains(id))
                    .limit(topK)
                    .toList();
        } catch (Exception e) {
            log.warn("일정 유사도 검색 실패 - RAG 없이 계속 진행 (userId={})", userId, e);
            return List.of();
        }
    }

    @Async("embeddingTaskExecutor")
    public void deleteScheduleEmbedding(Long scheduleId) {
        try {
            vectorStore.delete(List.of(scheduleDocumentId(scheduleId)));
        } catch (Exception e) {
            log.warn("일정 임베딩 삭제 실패 - 무시하고 삭제는 계속 진행 (scheduleId={})", scheduleId, e);
        }
    }

    // PgVectorStore의 id 컬럼은 uuid 타입이라 임의 문자열을 그대로 쓸 수 없다 - 같은 scheduleId에
    // 항상 같은 UUID가 나오도록 이름 기반(버전 3) UUID로 변환해서 결정적 id(=upsert 키)를 만든다
    // (MandalartGoalEmbeddingService.blockDocumentId와 동일한 이유)
    private String scheduleDocumentId(Long scheduleId) {
        return UUID.nameUUIDFromBytes(("schedule:" + scheduleId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
