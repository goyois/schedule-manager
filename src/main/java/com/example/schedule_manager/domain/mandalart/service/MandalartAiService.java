package com.example.schedule_manager.domain.mandalart.service;

import com.example.schedule_manager.domain.ai.service.AiRateLimiter;
import com.example.schedule_manager.domain.mandalart.dto.MandalartBoardResponseDto;
import com.example.schedule_manager.domain.mandalart.dto.MandalartCellSuggestion;
import com.example.schedule_manager.domain.mandalart.dto.MandalartFillSuggestion;
import com.example.schedule_manager.domain.mandalart.entity.MandalartBoard;
import com.example.schedule_manager.domain.mandalart.entity.MandalartCell;
import com.example.schedule_manager.domain.mandalart.repository.MandalartBoardRepository;
import com.example.schedule_manager.domain.mandalart.repository.MandalartCellRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// 만다라트(9x9)는 핵심 목표(4,4) 1칸 + 그걸 이루기 위한 세부목표 8칸(중앙 3x3 블록에서 핵심 목표를
// 둘러싼 칸들) + 각 세부목표를 실행하기 위한 실행항목 8칸씩(바깥 8개 3x3 블록, 총 64칸)으로 이루어진다.
// 사용자가 중앙 9칸(핵심 목표 + 세부목표 8개)을 먼저 채우면, 이 서비스가:
//   1) 바깥 8개 블록의 "자기 블록 중심" 칸(=해당 세부목표의 사본, 정석 만다라트 구조)을 순수 복사로 채우고
//   2) 나머지 빈 칸(대부분 실행항목 64개)만 AI로 채운다
// 이미 채워진 칸은 절대 건드리지 않는다(사용자 요청).
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MandalartAiService {

    // (blockRow, blockCol) - 중앙 블록(1,1)을 제외한 나머지 8개 블록
    private static final int[][] OUTER_BLOCKS = {
            {0, 0}, {0, 1}, {0, 2},
            {1, 0},         {1, 2},
            {2, 0}, {2, 1}, {2, 2},
    };

    private static final String SYSTEM_PROMPT = """
            당신은 만다라트(9x9 목표 달성표) 작성을 돕는 도우미입니다. 만다라트는 핵심 목표 1개와 그것을
            이루기 위한 세부목표 8개, 그리고 각 세부목표마다 그걸 실행하기 위한 구체적인 실행항목 8개
            (세부목표당 8개)로 이루어집니다.

            요청의 [현재 만다라트 상태]로 핵심 목표/각 세부목표/이미 채워진 칸을 보여드리니 참고해서,
            [채워야 하는 칸 좌표]에 나열된 좌표만 정확히 채우세요 - 그 외 좌표는 절대 포함하지 마세요.
            각 칸은 그 칸이 속한 블록의 세부목표를 실행하기 위한 구체적이고 실천 가능한 문구(20자 내외,
            최대 40자)로 채우세요. 같은 세부목표 아래 항목끼리는 서로 겹치지 않고 다양한 각도여야 합니다.
            """;

    private final MandalartBoardRepository mandalartBoardRepository;
    private final MandalartCellRepository mandalartCellRepository;
    private final UserRepository userRepository;
    private final AiRateLimiter aiRateLimiter;

    // Lombok의 @RequiredArgsConstructor는 필드의 @Qualifier를 생성자 파라미터로 복사해주지 않으므로
    // (lombok.config 없이는), 필드/파라미터 이름을 AiConfig의 빈 이름과 정확히 맞춰 Spring의 이름 기반
    // 폴백으로 올바른 빈이 주입되게 한다 - "chatClient"로 이름 지으면 그 이름의 다른 빈이 주입된다
    private final ChatClient mandalartFillChatClient;

    public MandalartBoardResponseDto fillWithAi(String requesterEmail, Long boardId) {
        User requester = findUserByEmail(requesterEmail);
        aiRateLimiter.checkLimit(requester.getId(), requester.getUserType());

        MandalartBoard board = findBoard(boardId);
        assertOwner(board, requester);

        List<MandalartCell> cells = mandalartCellRepository.findByBoardIdOrderByRowIndexAscColIndexAsc(boardId);
        Map<String, MandalartCell> cellByKey = cells.stream()
                .collect(Collectors.toMap(c -> key(c.getRowIndex(), c.getColIndex()), c -> c, (a, b) -> a, HashMap::new));

        // 1단계: 바깥 블록의 "자기 블록 중심" 칸은 중앙 블록에 있는 같은 방향의 세부목표를 그대로
        // 복사한다(정석 만다라트 구조상 같은 문구가 두 번 나온다) - AI를 부를 필요가 없는 순수 로직
        mirrorSubGoalsToOuterBlockCenters(cellByKey);

        List<MandalartCell> emptyCells = cells.stream()
                .filter(c -> isBlank(c.getContent()))
                .toList();
        if (emptyCells.isEmpty()) {
            return MandalartBoardResponseDto.from(board, cells);
        }

        String gridContext = buildGridContext(cellByKey);
        String targetContext = emptyCells.stream()
                .map(c -> "- (%d,%d)".formatted(c.getRowIndex(), c.getColIndex()))
                .collect(Collectors.joining("\n"));

        MandalartFillSuggestion suggestion;
        try {
            suggestion = mandalartFillChatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("[현재 만다라트 상태]\n" + gridContext + "\n\n[채워야 하는 칸 좌표]\n" + targetContext)
                    .call()
                    .entity(MandalartFillSuggestion.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AI_REQUEST_FAILED, e);
        }

        applySuggestion(suggestion, emptyCells, cellByKey);

        return MandalartBoardResponseDto.from(board, cells);
    }

    // 모델이 요청하지 않은 좌표를 지어내거나 이미 채워진 칸을 덮어쓰려 할 수 있으므로, 실제로 이번에
    // "비어 있어서 요청한" 좌표일 때만 반영한다(SCHEDULE_UPDATE의 targetScheduleId 검증과 같은 이유)
    private void applySuggestion(MandalartFillSuggestion suggestion, List<MandalartCell> emptyCells,
                                  Map<String, MandalartCell> cellByKey) {
        if (suggestion == null || suggestion.cells() == null) return;

        Set<String> emptyKeys = emptyCells.stream().map(c -> key(c.getRowIndex(), c.getColIndex())).collect(Collectors.toSet());
        for (MandalartCellSuggestion s : suggestion.cells()) {
            String k = key(s.row(), s.col());
            if (!emptyKeys.contains(k)) {
                log.warn("만다라트 AI 채우기 - 요청하지 않은 좌표라 무시함: ({},{})", s.row(), s.col());
                continue;
            }
            if (isBlank(s.content())) continue;
            cellByKey.get(k).update(s.content().trim());
        }
    }

    private void mirrorSubGoalsToOuterBlockCenters(Map<String, MandalartCell> cellByKey) {
        for (int[] block : OUTER_BLOCKS) {
            int blockRow = block[0];
            int blockCol = block[1];
            MandalartCell subGoalCell = cellByKey.get(key(blockRow + 3, blockCol + 3));
            MandalartCell blockCenterCell = cellByKey.get(key(blockRow * 3 + 1, blockCol * 3 + 1));
            if (blockCenterCell != null && isBlank(blockCenterCell.getContent())
                    && subGoalCell != null && !isBlank(subGoalCell.getContent())) {
                blockCenterCell.update(subGoalCell.getContent());
            }
        }
    }

    // 모델에게 핵심 목표/세부목표/각 블록의 실행항목 현황을 블록 단위로 정리해서 보여준다
    private String buildGridContext(Map<String, MandalartCell> cellByKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("핵심 목표: ").append(displayOrBlank(cellByKey.get(key(4, 4)).getContent())).append("\n\n");

        for (int[] block : OUTER_BLOCKS) {
            int blockRow = block[0];
            int blockCol = block[1];
            String subGoal = cellByKey.get(key(blockRow + 3, blockCol + 3)).getContent();
            sb.append("세부목표(").append(displayOrBlank(subGoal)).append(")의 실행항목:\n");
            for (int r = blockRow * 3; r < blockRow * 3 + 3; r++) {
                for (int c = blockCol * 3; c < blockCol * 3 + 3; c++) {
                    if (r == blockRow * 3 + 1 && c == blockCol * 3 + 1) continue; // 블록 중심(세부목표 사본)은 이미 위에 표기
                    sb.append("  - (").append(r).append(",").append(c).append("): ")
                            .append(displayOrBlank(cellByKey.get(key(r, c)).getContent())).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String displayOrBlank(String content) {
        return isBlank(content) ? "(비어있음)" : content;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String key(int row, int col) {
        return row + "-" + col;
    }

    private void assertOwner(MandalartBoard board, User requester) {
        if (!board.getUser().getId().equals(requester.getId())) {
            throw new BusinessException(ErrorCode.MANDALART_ACCESS_DENIED);
        }
    }

    private MandalartBoard findBoard(Long id) {
        return mandalartBoardRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MANDALART_NOT_FOUND));
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
