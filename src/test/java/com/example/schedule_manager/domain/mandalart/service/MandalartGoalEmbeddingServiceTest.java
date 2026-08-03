package com.example.schedule_manager.domain.mandalart.service;

import com.example.schedule_manager.domain.mandalart.entity.MandalartBoard;
import com.example.schedule_manager.domain.mandalart.entity.MandalartCell;
import com.example.schedule_manager.domain.mandalart.repository.MandalartCellRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MandalartGoalEmbeddingService - 완성된(세부목표+실행항목 8개) 바깥 블록만 색인하고, 색인/검색/삭제
 * 모두 VectorStore 장애 시에도 예외를 밖으로 던지지 않는지(fail-open)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MandalartGoalEmbeddingServiceTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private MandalartCellRepository mandalartCellRepository;

    @InjectMocks
    private MandalartGoalEmbeddingService mandalartGoalEmbeddingService;

    private User user(Long id) {
        return User.builder().id(id).username("tester").email("tester@example.com").userType(UserType.USER).build();
    }

    private MandalartCell cell(MandalartBoard board, int row, int col, String content) {
        return MandalartCell.builder().id((long) (row * 9 + col + 1)).board(board).rowIndex(row).colIndex(col).content(content).build();
    }

    // 블록(0,0): 세부목표는 (3,3), 실행항목은 (0,0)~(2,2) 중 자기 블록 중심 (1,1)을 뺀 8칸
    private List<MandalartCell> block00ActionItems(MandalartBoard board, String... contents) {
        List<MandalartCell> cells = new ArrayList<>();
        int idx = 0;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (r == 1 && c == 1) continue; // 블록 중심(세부목표 사본)은 실행항목이 아님
                cells.add(cell(board, r, c, contents[idx++]));
            }
        }
        return cells;
    }

    private static final String[] EIGHT_FILLED = {
            "아침 조깅 30분", "물 2L 마시기", "주 3회 헬스장", "야식 끊기",
            "계단 이용하기", "홈트레이닝", "스트레칭", "충분한 수면"
    };

    @Test
    @DisplayName("색인 성공 - 세부목표와 실행항목 8개가 모두 채워져 있으면 VectorStore에 문서를 추가한다")
    void reindexBlockIfComplete_blockComplete_addsDocument() {
        User owner = user(1L);
        MandalartBoard board = MandalartBoard.builder().id(10L).title("목표").user(owner).build();
        when(mandalartCellRepository.findByBoardIdAndRowIndexAndColIndex(10L, 3, 3))
                .thenReturn(Optional.of(cell(board, 3, 3, "규칙적인 운동")));
        when(mandalartCellRepository.findByBoardIdAndRowIndexBetweenAndColIndexBetween(10L, 0, 2, 0, 2))
                .thenReturn(block00ActionItems(board, EIGHT_FILLED));

        mandalartGoalEmbeddingService.reindexBlockIfComplete(1L, 10L, 0, 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Document document = captor.getValue().get(0);
        assertThat(document.getContent()).isEqualTo("규칙적인 운동");
        assertThat(document.getMetadata().get("docType")).isEqualTo("mandalart_block");
        assertThat(document.getMetadata().get("userId")).isEqualTo(1L);
        assertThat(document.getMetadata().get("boardId")).isEqualTo(10L);
        assertThat((String) document.getMetadata().get("actionItems")).contains("아침 조깅 30분", "충분한 수면");
    }

    @Test
    @DisplayName("색인 안 함 - 세부목표가 비어 있으면 VectorStore를 건드리지 않는다")
    void reindexBlockIfComplete_subGoalBlank_doesNotIndex() {
        MandalartBoard board = MandalartBoard.builder().id(10L).title("목표").user(user(1L)).build();
        when(mandalartCellRepository.findByBoardIdAndRowIndexAndColIndex(10L, 3, 3))
                .thenReturn(Optional.of(cell(board, 3, 3, "")));

        mandalartGoalEmbeddingService.reindexBlockIfComplete(1L, 10L, 0, 0);

        verify(vectorStore, never()).add(anyList());
    }

    @Test
    @DisplayName("색인 안 함 - 실행항목이 하나라도 비어 있으면 VectorStore를 건드리지 않는다")
    void reindexBlockIfComplete_someActionItemBlank_doesNotIndex() {
        MandalartBoard board = MandalartBoard.builder().id(10L).title("목표").user(user(1L)).build();
        String[] sevenFilledOneBlank = EIGHT_FILLED.clone();
        sevenFilledOneBlank[7] = "";
        when(mandalartCellRepository.findByBoardIdAndRowIndexAndColIndex(10L, 3, 3))
                .thenReturn(Optional.of(cell(board, 3, 3, "규칙적인 운동")));
        when(mandalartCellRepository.findByBoardIdAndRowIndexBetweenAndColIndexBetween(10L, 0, 2, 0, 2))
                .thenReturn(block00ActionItems(board, sevenFilledOneBlank));

        mandalartGoalEmbeddingService.reindexBlockIfComplete(1L, 10L, 0, 0);

        verify(vectorStore, never()).add(anyList());
    }

    @Test
    @DisplayName("색인 실패 흡수 - VectorStore.add가 예외를 던져도 밖으로 전파하지 않는다(fail-open)")
    void reindexBlockIfComplete_vectorStoreThrows_doesNotPropagate() {
        MandalartBoard board = MandalartBoard.builder().id(10L).title("목표").user(user(1L)).build();
        when(mandalartCellRepository.findByBoardIdAndRowIndexAndColIndex(10L, 3, 3))
                .thenReturn(Optional.of(cell(board, 3, 3, "규칙적인 운동")));
        when(mandalartCellRepository.findByBoardIdAndRowIndexBetweenAndColIndexBetween(10L, 0, 2, 0, 2))
                .thenReturn(block00ActionItems(board, EIGHT_FILLED));
        org.mockito.Mockito.doThrow(new RuntimeException("OpenAI 장애")).when(vectorStore).add(anyList());

        mandalartGoalEmbeddingService.reindexBlockIfComplete(1L, 10L, 0, 0);
        // 예외 없이 정상 반환되면 성공
    }

    @Test
    @DisplayName("유사도 검색 성공 - 검색 결과를 GoalExample로 변환하고 본인 소유/현재 보드 제외 필터를 건다")
    void findSimilarExamples_success_returnsMappedExamplesWithFilter() {
        Document found = new Document("doc-id", "규칙적인 운동", Map.of("actionItems", "아침 조깅 30분\n주 3회 헬스장"));
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
                .thenReturn(List.of(found));

        List<MandalartGoalEmbeddingService.GoalExample> examples =
                mandalartGoalEmbeddingService.findSimilarExamples(1L, 10L, "체력 기르기", 1);

        assertThat(examples).hasSize(1);
        assertThat(examples.get(0).subGoal()).isEqualTo("규칙적인 운동");
        assertThat(examples.get(0).actionItems()).contains("아침 조깅 30분");

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        SearchRequest sentRequest = requestCaptor.getValue();
        assertThat(sentRequest.getQuery()).isEqualTo("체력 기르기");
        assertThat(sentRequest.getTopK()).isEqualTo(1);
        assertThat(sentRequest.hasFilterExpression()).isTrue();
        // topK=1이라 하한선이 없으면 무관한 과거 블록도 "가장 비슷한 것"이라는 이유만으로 항상 few-shot에
        // 끼워 넣게 된다 - ScheduleEmbeddingService와 동일한 근거(실측값)로 고정한 하한선
        assertThat(sentRequest.getSimilarityThreshold()).isEqualTo(0.35);
    }

    @Test
    @DisplayName("유사도 검색 실패 흡수 - VectorStore 검색이 예외를 던지면 빈 목록을 반환한다(fail-open)")
    void findSimilarExamples_vectorStoreThrows_returnsEmptyList() {
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
                .thenThrow(new RuntimeException("OpenAI 장애"));

        List<MandalartGoalEmbeddingService.GoalExample> examples =
                mandalartGoalEmbeddingService.findSimilarExamples(1L, 10L, "체력 기르기", 1);

        assertThat(examples).isEmpty();
    }

    @Test
    @DisplayName("보드 삭제 시 임베딩 정리 - 바깥 8개 블록의 id를 전부 삭제 요청한다")
    void deleteBoardEmbeddings_success_deletesAllEightBlockIds() {
        mandalartGoalEmbeddingService.deleteBoardEmbeddings(10L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).delete(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).hasSize(8);
    }

    @Test
    @DisplayName("임베딩 삭제 실패 흡수 - VectorStore.delete가 예외를 던져도 밖으로 전파하지 않는다(fail-open)")
    void deleteBoardEmbeddings_vectorStoreThrows_doesNotPropagate() {
        when(vectorStore.delete(anyList())).thenThrow(new RuntimeException("DB 장애"));

        mandalartGoalEmbeddingService.deleteBoardEmbeddings(10L);
        // 예외 없이 정상 반환되면 성공
    }

    @Test
    @DisplayName("영향 블록 계산 - 핵심 목표(4,4)는 색인 대상이 아니다")
    void resolveAffectedBlock_mainGoalCell_returnsNull() {
        assertThat(mandalartGoalEmbeddingService.resolveAffectedBlock(4, 4)).isNull();
    }

    @Test
    @DisplayName("영향 블록 계산 - 세부목표 칸은 짝이 되는 바깥 블록으로 매핑된다")
    void resolveAffectedBlock_subGoalCell_mapsToPairedOuterBlock() {
        assertThat(mandalartGoalEmbeddingService.resolveAffectedBlock(3, 3)).containsExactly(0, 0);
        assertThat(mandalartGoalEmbeddingService.resolveAffectedBlock(5, 5)).containsExactly(2, 2);
    }

    @Test
    @DisplayName("영향 블록 계산 - 바깥 블록의 실행항목 칸은 자기 블록 좌표로 매핑된다")
    void resolveAffectedBlock_actionItemCell_mapsToOwnBlock() {
        assertThat(mandalartGoalEmbeddingService.resolveAffectedBlock(0, 1)).containsExactly(0, 0);
        assertThat(mandalartGoalEmbeddingService.resolveAffectedBlock(7, 8)).containsExactly(2, 2);
    }
}
