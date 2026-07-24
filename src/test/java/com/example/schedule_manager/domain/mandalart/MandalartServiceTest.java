package com.example.schedule_manager.domain.mandalart;

import com.example.schedule_manager.domain.mandalart.dto.MandalartBoardCreateRequestDto;
import com.example.schedule_manager.domain.mandalart.dto.MandalartBoardResponseDto;
import com.example.schedule_manager.domain.mandalart.dto.MandalartBoardSummaryDto;
import com.example.schedule_manager.domain.mandalart.dto.MandalartCellUpdateRequestDto;
import com.example.schedule_manager.domain.mandalart.entity.MandalartBoard;
import com.example.schedule_manager.domain.mandalart.entity.MandalartCell;
import com.example.schedule_manager.domain.mandalart.repository.MandalartBoardRepository;
import com.example.schedule_manager.domain.mandalart.repository.MandalartCellRepository;
import com.example.schedule_manager.domain.mandalart.service.MandalartService;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MandalartServiceTest {

    @Mock
    private MandalartBoardRepository mandalartBoardRepository;

    @Mock
    private MandalartCellRepository mandalartCellRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MandalartService mandalartService;

    private User user(Long id) {
        return User.builder().id(id).username("tester").email("tester@example.com").userType(UserType.USER).build();
    }

    @Test
    @DisplayName("만다라트 생성 성공 - 요청자가 소유자로 저장되고 81개 셀이 함께 생성된다")
    void createBoard_success_createsBoardWith81Cells() {
        MandalartBoardCreateRequestDto request = new MandalartBoardCreateRequestDto("2026년 목표");
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.save(any(MandalartBoard.class))).thenAnswer(invocation -> {
            MandalartBoard saved = invocation.getArgument(0);
            return MandalartBoard.builder().id(100L).title(saved.getTitle()).user(saved.getUser()).build();
        });
        when(mandalartCellRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        MandalartBoardResponseDto response = mandalartService.createBoard("tester@example.com", request);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.title()).isEqualTo("2026년 목표");
        assertThat(response.cells()).hasSize(81);
        assertThat(response.cells()).allMatch(cell -> cell.content().isEmpty());

        ArgumentCaptor<MandalartBoard> boardCaptor = ArgumentCaptor.forClass(MandalartBoard.class);
        verify(mandalartBoardRepository).save(boardCaptor.capture());
        assertThat(boardCaptor.getValue().getUser()).isEqualTo(requester);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MandalartCell>> cellsCaptor = ArgumentCaptor.forClass(List.class);
        verify(mandalartCellRepository).saveAll(cellsCaptor.capture());
        assertThat(cellsCaptor.getValue()).hasSize(81);
    }

    @Test
    @DisplayName("만다라트 목록 조회 성공 - 요청자 소유 보드만 반환한다")
    void getBoards_success_returnsRequesterOwnedBoards() {
        User requester = user(1L);
        MandalartBoard board = MandalartBoard.builder().id(1L).title("목표").user(requester).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(board));

        List<MandalartBoardSummaryDto> response = mandalartService.getBoards("tester@example.com");

        assertThat(response).hasSize(1);
        assertThat(response.get(0).title()).isEqualTo("목표");
    }

    @Test
    @DisplayName("만다라트 상세 조회 성공 - 본인 소유 보드는 셀과 함께 조회된다")
    void getBoard_success_returnsBoardWithCells() {
        User requester = user(1L);
        MandalartBoard board = MandalartBoard.builder().id(1L).title("목표").user(requester).build();
        MandalartCell cell = MandalartCell.builder().id(1L).board(board).rowIndex(4).colIndex(4).content("메인 목표").build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findById(1L)).thenReturn(Optional.of(board));
        when(mandalartCellRepository.findByBoardIdOrderByRowIndexAscColIndexAsc(1L)).thenReturn(List.of(cell));

        MandalartBoardResponseDto response = mandalartService.getBoard("tester@example.com", 1L);

        assertThat(response.cells()).hasSize(1);
        assertThat(response.cells().get(0).content()).isEqualTo("메인 목표");
    }

    @Test
    @DisplayName("만다라트 상세 조회 실패 - 존재하지 않는 보드면 예외가 발생한다")
    void getBoard_notFound_throws() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mandalartService.getBoard("tester@example.com", 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANDALART_NOT_FOUND);
    }

    @Test
    @DisplayName("만다라트 상세 조회 실패 - 다른 유저의 보드는 접근할 수 없다")
    void getBoard_otherUsersBoard_throwsAccessDenied() {
        User requester = user(1L);
        MandalartBoard othersBoard = MandalartBoard.builder().id(2L).title("남의 목표").user(user(2L)).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findById(2L)).thenReturn(Optional.of(othersBoard));

        assertThatThrownBy(() -> mandalartService.getBoard("tester@example.com", 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANDALART_ACCESS_DENIED);
    }

    @Test
    @DisplayName("셀 수정 성공 - 본인 소유 보드의 셀 내용을 바꿀 수 있다")
    void updateCell_success_updatesContent() {
        User requester = user(1L);
        MandalartBoard board = MandalartBoard.builder().id(1L).title("목표").user(requester).build();
        MandalartCell cell = MandalartCell.builder().id(1L).board(board).rowIndex(0).colIndex(0).content("").build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findById(1L)).thenReturn(Optional.of(board));
        when(mandalartCellRepository.findByBoardIdAndRowIndexAndColIndex(1L, 0, 0)).thenReturn(Optional.of(cell));

        mandalartService.updateCell("tester@example.com", 1L, 0, 0, new MandalartCellUpdateRequestDto("실행 항목 1"));

        assertThat(cell.getContent()).isEqualTo("실행 항목 1");
    }

    @Test
    @DisplayName("셀 수정 실패 - row/col 이 0~8 범위를 벗어나면 예외가 발생한다")
    void updateCell_invalidPosition_throws() {
        assertThatThrownBy(() -> mandalartService.updateCell("tester@example.com", 1L, -1, 0, new MandalartCellUpdateRequestDto("x")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANDALART_INVALID_POSITION);

        assertThatThrownBy(() -> mandalartService.updateCell("tester@example.com", 1L, 0, 9, new MandalartCellUpdateRequestDto("x")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANDALART_INVALID_POSITION);

        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    @DisplayName("셀 수정 실패 - 다른 유저의 보드는 수정할 수 없다")
    void updateCell_otherUsersBoard_throwsAccessDenied() {
        User requester = user(1L);
        MandalartBoard othersBoard = MandalartBoard.builder().id(2L).title("남의 목표").user(user(2L)).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findById(2L)).thenReturn(Optional.of(othersBoard));

        assertThatThrownBy(() -> mandalartService.updateCell("tester@example.com", 2L, 0, 0, new MandalartCellUpdateRequestDto("x")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANDALART_ACCESS_DENIED);
    }

    @Test
    @DisplayName("만다라트 삭제 성공 - 셀을 먼저 지운 뒤 보드를 삭제한다")
    void deleteBoard_success_deletesCellsThenBoard() {
        User requester = user(1L);
        MandalartBoard board = MandalartBoard.builder().id(1L).title("목표").user(requester).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findById(1L)).thenReturn(Optional.of(board));

        mandalartService.deleteBoard("tester@example.com", 1L);

        verify(mandalartCellRepository).deleteByBoardId(1L);
        verify(mandalartBoardRepository).delete(board);
    }

    @Test
    @DisplayName("만다라트 삭제 실패 - 다른 유저의 보드는 삭제할 수 없다")
    void deleteBoard_otherUsersBoard_throwsAccessDenied() {
        User requester = user(1L);
        MandalartBoard othersBoard = MandalartBoard.builder().id(2L).title("남의 목표").user(user(2L)).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(mandalartBoardRepository.findById(2L)).thenReturn(Optional.of(othersBoard));

        assertThatThrownBy(() -> mandalartService.deleteBoard("tester@example.com", 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANDALART_ACCESS_DENIED);

        verify(mandalartCellRepository, never()).deleteByBoardId(any());
        verify(mandalartBoardRepository, never()).delete(any());
    }
}
