package com.example.schedule_manager.domain.mandalart.service;

import com.example.schedule_manager.domain.mandalart.dto.MandalartBoardCreateRequestDto;
import com.example.schedule_manager.domain.mandalart.dto.MandalartBoardResponseDto;
import com.example.schedule_manager.domain.mandalart.dto.MandalartBoardSummaryDto;
import com.example.schedule_manager.domain.mandalart.dto.MandalartCellUpdateRequestDto;
import com.example.schedule_manager.domain.mandalart.entity.MandalartBoard;
import com.example.schedule_manager.domain.mandalart.entity.MandalartCell;
import com.example.schedule_manager.domain.mandalart.repository.MandalartBoardRepository;
import com.example.schedule_manager.domain.mandalart.repository.MandalartCellRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class MandalartService {

    private static final int GRID_SIZE = 9;

    private final MandalartBoardRepository mandalartBoardRepository;
    private final MandalartCellRepository mandalartCellRepository;
    private final UserRepository userRepository;

    // 보드 생성과 동시에 9x9 = 81개 셀을 전부 빈 문자열로 만들어둔다 — 이후 조회/수정 로직에서
    // "셀이 아직 없을 수도 있다"는 경우의 수를 다룰 필요가 없어진다
    public MandalartBoardResponseDto createBoard(String requesterEmail, MandalartBoardCreateRequestDto request) {
        User requester = findUserByEmail(requesterEmail);
        MandalartBoard board = mandalartBoardRepository.save(
                MandalartBoard.builder()
                        .title(request.title())
                        .user(requester)
                        .build()
        );

        List<MandalartCell> cells = new ArrayList<>(GRID_SIZE * GRID_SIZE);
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                cells.add(MandalartCell.builder()
                        .board(board)
                        .rowIndex(row)
                        .colIndex(col)
                        .content("")
                        .build());
            }
        }
        List<MandalartCell> savedCells = mandalartCellRepository.saveAll(cells);

        return MandalartBoardResponseDto.from(board, savedCells);
    }

    @Transactional(readOnly = true)
    public List<MandalartBoardSummaryDto> getBoards(String requesterEmail) {
        User requester = findUserByEmail(requesterEmail);
        return mandalartBoardRepository.findByUserIdOrderByCreatedAtDesc(requester.getId()).stream()
                .map(MandalartBoardSummaryDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public MandalartBoardResponseDto getBoard(String requesterEmail, Long boardId) {
        User requester = findUserByEmail(requesterEmail);
        MandalartBoard board = findBoard(boardId);
        assertOwner(board, requester);

        List<MandalartCell> cells = mandalartCellRepository.findByBoardIdOrderByRowIndexAscColIndexAsc(boardId);
        return MandalartBoardResponseDto.from(board, cells);
    }

    public void updateCell(String requesterEmail, Long boardId, int row, int col, MandalartCellUpdateRequestDto request) {
        if (row < 0 || row >= GRID_SIZE || col < 0 || col >= GRID_SIZE) {
            throw new BusinessException(ErrorCode.MANDALART_INVALID_POSITION);
        }

        User requester = findUserByEmail(requesterEmail);
        MandalartBoard board = findBoard(boardId);
        assertOwner(board, requester);

        MandalartCell cell = mandalartCellRepository.findByBoardIdAndRowIndexAndColIndex(boardId, row, col)
                .orElseThrow(() -> new BusinessException(ErrorCode.MANDALART_NOT_FOUND));
        cell.update(request.content());
    }

    public void deleteBoard(String requesterEmail, Long boardId) {
        User requester = findUserByEmail(requesterEmail);
        MandalartBoard board = findBoard(boardId);
        assertOwner(board, requester);

        mandalartCellRepository.deleteByBoardId(boardId);
        mandalartBoardRepository.delete(board);
    }

    // 만다라트 보드는 Category 의 소유자-null 공유 모델과 달리 공유/기본 개념이 없는 완전 개인 소유라,
    // Schedule.getSchedule() 과 같은 단순 소유권 검사(신원을 숨기지 않는 순수 403)를 따른다
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
