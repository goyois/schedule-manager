package com.example.schedule_manager.domain.mandalart.service;

import com.example.schedule_manager.domain.mandalart.entity.MandalartCell;
import com.example.schedule_manager.domain.mandalart.repository.MandalartCellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

// 완성된(세부목표 + 그 블록의 실행항목 8개가 모두 채워진) 바깥 3x3 블록 하나를 문서 1개로 임베딩해두고,
// 새 만다라트를 AI로 채울 때(MandalartAiService.fillWithAi) 지금 채우려는 세부목표와 의미적으로 비슷한
// 과거 블록을 검색해 그 실행항목들을 few-shot 예시로 준다.
//
// 검색은 항상 요청자 본인 소유 블록으로만 스코프한다 - 만다라트 보드는 Category 같은 공유 모델이 없는
// 완전 개인 소유라(MandalartService 참고), 다른 유저의 목표 텍스트가 섞이면 안 된다.
//
// 색인/검색 모두 실패해도 원래 흐름(셀 저장, AI 채우기)을 막지 않는다(fail-open) - 이 코드베이스 전반의
// 확립된 패턴(Redis 캐시 evict의 CacheFailSafeErrorHandler, JwtAuthenticationFilter의 블랙리스트 체크,
// AiRateLimiter)과 동일: RAG는 어디까지나 AI 채우기 품질을 보조하는 기능이라, 임베딩 API(OpenAI) 장애가
// 만다라트 기능 자체를 막아서는 안 된다.
@Slf4j
@Service
@RequiredArgsConstructor
public class MandalartGoalEmbeddingService {

    private static final List<int[]> OUTER_BLOCK_COORDS = List.of(
            new int[] {0, 0}, new int[] {0, 1}, new int[] {0, 2},
            new int[] {1, 0}, new int[] {1, 2},
            new int[] {2, 0}, new int[] {2, 1}, new int[] {2, 2}
    );

    // ScheduleEmbeddingService와 VectorStore(테이블 하나)를 공유하므로, 문서마다 이 값으로
    // 도메인을 구분해둔다 - 없으면 userId만 겹쳐도 검색 결과에 일정 문서가 섞여 들어올 수 있다
    private static final String DOC_TYPE = "mandalart_block";

    private final VectorStore vectorStore;
    private final MandalartCellRepository mandalartCellRepository;

    public record GoalExample(String subGoal, String actionItems) {}

    // 편집된 셀 (row, col)이 영향을 주는 바깥 블록 좌표를 반환한다. 핵심 목표(4,4)는 색인 대상이 아니라
    // null. 세부목표 8칸(중앙 블록 안, (4,4) 제외)은 자신과 짝이 되는 바깥 블록으로 매핑된다
    // (MandalartAiService의 OUTER_BLOCKS/(blockRow+3, blockCol+3) 규약과 정확히 반대 방향 매핑).
    public int[] resolveAffectedBlock(int row, int col) {
        if (row == 4 && col == 4) return null;
        if (row / 3 == 1 && col / 3 == 1) {
            return new int[] {row - 3, col - 3};
        }
        return new int[] {row / 3, col / 3};
    }

    public void reindexBlockIfComplete(Long userId, Long boardId, int blockRow, int blockCol) {
        try {
            Optional<MandalartCell> subGoalCellOpt = mandalartCellRepository
                    .findByBoardIdAndRowIndexAndColIndex(boardId, blockRow + 3, blockCol + 3);
            if (subGoalCellOpt.isEmpty() || isBlank(subGoalCellOpt.get().getContent())) return;

            List<MandalartCell> blockCells = mandalartCellRepository.findByBoardIdAndRowIndexBetweenAndColIndexBetween(
                    boardId, blockRow * 3, blockRow * 3 + 2, blockCol * 3, blockCol * 3 + 2);
            int centerRow = blockRow * 3 + 1;
            int centerCol = blockCol * 3 + 1;
            List<MandalartCell> actionItems = blockCells.stream()
                    .filter(c -> !(c.getRowIndex() == centerRow && c.getColIndex() == centerCol))
                    .toList();
            if (actionItems.size() < 8 || actionItems.stream().anyMatch(c -> isBlank(c.getContent()))) return;

            String subGoal = subGoalCellOpt.get().getContent().trim();
            String actionItemsText = actionItems.stream()
                    .map(c -> c.getContent().trim())
                    .collect(Collectors.joining("\n"));

            Document document = new Document(
                    blockDocumentId(boardId, blockRow, blockCol),
                    subGoal,
                    Map.of(
                            "docType", DOC_TYPE,
                            "userId", userId,
                            "boardId", boardId,
                            "blockRow", blockRow,
                            "blockCol", blockCol,
                            "actionItems", actionItemsText
                    )
            );
            vectorStore.add(List.of(document));
        } catch (Exception e) {
            log.warn("만다라트 목표 임베딩 색인 실패 - 무시하고 계속 진행 (boardId={}, block=({},{}))", boardId, blockRow, blockCol, e);
        }
    }

    // 본인 소유(userId) + 현재 채우는 보드 자신은 제외(excludeBoardId)하고, subGoalText와 의미적으로
    // 가장 가까운 과거 블록을 topK개 검색한다. 실패 시 빈 목록(=few-shot 없이 평소대로 진행).
    public List<GoalExample> findSimilarExamples(Long userId, Long excludeBoardId, String subGoalText, int topK) {
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression filter = b.and(
                    b.and(b.eq("userId", userId), b.ne("boardId", excludeBoardId)),
                    b.eq("docType", DOC_TYPE)
            ).build();

            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.query(subGoalText)
                            .withTopK(topK)
                            .withFilterExpression(filter)
            );
            return results.stream()
                    .map(d -> new GoalExample(d.getContent(), String.valueOf(d.getMetadata().get("actionItems"))))
                    .toList();
        } catch (Exception e) {
            log.warn("만다라트 목표 유사도 검색 실패 - few-shot 예시 없이 계속 진행 (userId={})", userId, e);
            return List.of();
        }
    }

    public void deleteBoardEmbeddings(Long boardId) {
        try {
            List<String> ids = OUTER_BLOCK_COORDS.stream()
                    .map(bc -> blockDocumentId(boardId, bc[0], bc[1]))
                    .toList();
            vectorStore.delete(ids);
        } catch (Exception e) {
            log.warn("만다라트 목표 임베딩 삭제 실패 - 무시하고 보드 삭제는 계속 진행 (boardId={})", boardId, e);
        }
    }

    // PgVectorStore의 id 컬럼은 uuid 타입이라 임의 문자열을 그대로 쓸 수 없다 - 같은 (boardId, block)에
    // 항상 같은 UUID가 나오도록 이름 기반(버전 3) UUID로 변환해서 결정적 id(=upsert 키)를 만든다
    private String blockDocumentId(Long boardId, int blockRow, int blockCol) {
        String key = "board:" + boardId + ":block:" + blockRow + ":" + blockCol;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
