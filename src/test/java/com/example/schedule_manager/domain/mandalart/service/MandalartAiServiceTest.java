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
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MandalartAiService.fillWithAi() - 중앙 9칸(핵심 목표 + 세부목표 8개)을 채운 뒤 나머지를 AI로 채우는 로직.
 * (1) 바깥 8개 블록의 "자기 블록 중심" 칸은 AI 호출 없이 순수 복사로 채워지는지,
 * (2) AI에는 실제로 비어 있던 칸만 요청되고, 그 결과도 요청한 좌표에만 반영되는지(방어적 검증),
 * (3) 이미 다 채워져 있으면 AI를 호출하지 않는지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MandalartAiServiceTest {

    @Mock
    private MandalartBoardRepository mandalartBoardRepository;
    @Mock
    private MandalartCellRepository mandalartCellRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AiRateLimiter aiRateLimiter;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequest chatClientRequest;
    @Mock
    private ChatClient.ChatClientRequest.CallResponseSpec callResponseSpec;

    private MandalartAiService mandalartAiService;

    private User requester;
    private MandalartBoard board;

    @BeforeEach
    void setUp() {
        mandalartAiService = new MandalartAiService(
                mandalartBoardRepository, mandalartCellRepository, userRepository, aiRateLimiter, chatClient);
        requester = User.builder().id(1L).username("tester").email("tester@example.com").userType(UserType.USER).build();
        board = MandalartBoard.builder().id(10L).title("2026년 목표").user(requester).build();
    }

    // 중앙 9칸(핵심 목표 + 세부목표 8개)만 채워진 81칸 보드를 만든다. overrides로 특정 좌표에 값을 더 채울 수 있다
    private List<MandalartCell> buildCellsWithFilledCenter(Map<String, String> extraOverrides) {
        Map<String, String> overrides = new HashMap<>(Map.of(
                "4-4", "건강한 삶",
                "3-3", "규칙적인 운동",
                "3-4", "균형 잡힌 식단",
                "3-5", "충분한 수면",
                "4-3", "정기 검진",
                "4-5", "스트레스 관리",
                "5-3", "금연",
                "5-4", "금주",
                "5-5", "체중 관리"
        ));
        overrides.putAll(extraOverrides);

        List<MandalartCell> cells = new ArrayList<>();
        for (int row = 0; row < 9; row++) {
            for (int col = 0; col < 9; col++) {
                String content = overrides.getOrDefault(row + "-" + col, "");
                cells.add(MandalartCell.builder()
                        .id((long) (row * 9 + col + 1)).board(board).rowIndex(row).colIndex(col).content(content).build());
            }
        }
        return cells;
    }

    private void stubChatClient(MandalartFillSuggestion suggestion) {
        when(chatClient.prompt()).thenReturn(chatClientRequest);
        when(chatClientRequest.system(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.user(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(eq(MandalartFillSuggestion.class))).thenReturn(suggestion);
    }

    @Test
    @DisplayName("채우기 성공 - 바깥 블록 중심(세부목표 사본)은 AI 없이 복사되고, AI는 요청한 빈 칸에만 반영된다")
    void fillWithAi_success_mirrorsSubGoalsAndAppliesOnlyRequestedEmptyCells() {
        List<MandalartCell> cells = buildCellsWithFilledCenter(Map.of());
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findById(10L)).thenReturn(Optional.of(board));
        when(mandalartCellRepository.findByBoardIdOrderByRowIndexAscColIndexAsc(10L)).thenReturn(cells);

        // (0,0)은 실제로 비어 있던 칸이라 반영되고, (4,4)는 이미 채워져 있던 칸(모델이 지어낸 좌표)이라 무시돼야 한다
        stubChatClient(new MandalartFillSuggestion(List.of(
                new MandalartCellSuggestion(0, 0, "아침 스트레칭"),
                new MandalartCellSuggestion(4, 4, "엉뚱하게 덮어쓰려는 시도")
        )));

        MandalartBoardResponseDto response = mandalartAiService.fillWithAi("tester@example.com", 10L);

        Map<String, String> byKey = new HashMap<>();
        response.cells().forEach(c -> byKey.put(c.row() + "-" + c.col(), c.content()));

        assertThat(byKey.get("0-0")).isEqualTo("아침 스트레칭");
        assertThat(byKey.get("4-4")).isEqualTo("건강한 삶"); // 이미 채워진 핵심 목표는 그대로 유지(덮어쓰기 안 됨)
        // 바깥 블록의 자기 블록 중심(1,1)은 (3,3)의 세부목표를 AI 없이 그대로 복사한다
        assertThat(byKey.get("1-1")).isEqualTo("규칙적인 운동");
        assertThat(byKey.get("7-7")).isEqualTo("체중 관리");
    }

    @Test
    @DisplayName("채우기 성공 - 빈 칸이 하나도 없으면 AI를 호출하지 않는다")
    void fillWithAi_noEmptyCells_doesNotCallChatClient() {
        // 81칸을 전부 채운 보드
        Map<String, String> allFilled = new HashMap<>();
        for (int row = 0; row < 9; row++) {
            for (int col = 0; col < 9; col++) {
                allFilled.put(row + "-" + col, "내용");
            }
        }
        List<MandalartCell> cells = buildCellsWithFilledCenter(allFilled);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findById(10L)).thenReturn(Optional.of(board));
        when(mandalartCellRepository.findByBoardIdOrderByRowIndexAscColIndexAsc(10L)).thenReturn(cells);

        mandalartAiService.fillWithAi("tester@example.com", 10L);

        verifyNoInteractions(chatClient);
    }

    @Test
    @DisplayName("채우기 실패 - 다른 유저의 보드는 채울 수 없다")
    void fillWithAi_otherUsersBoard_throwsAccessDenied() {
        User owner = User.builder().id(2L).username("owner").email("owner@example.com").userType(UserType.USER).build();
        MandalartBoard othersBoard = MandalartBoard.builder().id(20L).title("남의 목표").user(owner).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findById(20L)).thenReturn(Optional.of(othersBoard));

        assertThatThrownBy(() -> mandalartAiService.fillWithAi("tester@example.com", 20L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANDALART_ACCESS_DENIED);

        verifyNoInteractions(chatClient);
    }

    @Test
    @DisplayName("채우기 실패 - 존재하지 않는 보드면 예외가 발생한다")
    void fillWithAi_boardNotFound_throws() {
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mandalartAiService.fillWithAi("tester@example.com", 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANDALART_NOT_FOUND);
    }

    @Test
    @DisplayName("채우기 실패 - 분당 호출 제한에 걸리면 ChatClient를 호출하지 않고 예외를 그대로 전파한다")
    void fillWithAi_rateLimited_doesNotCallChatClientAndPropagates() {
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED))
                .when(aiRateLimiter).checkLimit(1L, UserType.USER);

        assertThatThrownBy(() -> mandalartAiService.fillWithAi("tester@example.com", 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_RATE_LIMIT_EXCEEDED);

        verifyNoInteractions(chatClient);
    }

    @Test
    @DisplayName("채우기 실패 - ChatClient 호출이 실패하면 AI_REQUEST_FAILED로 감싼다")
    void fillWithAi_chatClientThrows_wrapsAsBusinessException() {
        List<MandalartCell> cells = buildCellsWithFilledCenter(Map.of());
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findById(10L)).thenReturn(Optional.of(board));
        when(mandalartCellRepository.findByBoardIdOrderByRowIndexAscColIndexAsc(10L)).thenReturn(cells);

        when(chatClient.prompt()).thenReturn(chatClientRequest);
        when(chatClientRequest.system(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.user(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.call()).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> mandalartAiService.fillWithAi("tester@example.com", 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_REQUEST_FAILED);
    }
}
