package com.example.schedule_manager.domain.mandalart.controller;

import com.example.schedule_manager.domain.mandalart.dto.MandalartBoardCreateRequestDto;
import com.example.schedule_manager.domain.mandalart.dto.MandalartBoardResponseDto;
import com.example.schedule_manager.domain.mandalart.dto.MandalartBoardSummaryDto;
import com.example.schedule_manager.domain.mandalart.dto.MandalartCellUpdateRequestDto;
import com.example.schedule_manager.domain.mandalart.service.MandalartAiService;
import com.example.schedule_manager.domain.mandalart.service.MandalartService;
import com.example.schedule_manager.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mandalart")
public class MandalartController {

    private final MandalartService mandalartService;
    private final MandalartAiService mandalartAiService;

    @PostMapping
    public ResponseEntity<ApiResponse<MandalartBoardResponseDto>> createBoard(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody MandalartBoardCreateRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(mandalartService.createBoard(principal.getUsername(), request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<MandalartBoardSummaryDto>>> getBoards(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(ApiResponse.success(mandalartService.getBoards(principal.getUsername())));
    }

    @GetMapping("/{boardId}")
    public ResponseEntity<ApiResponse<MandalartBoardResponseDto>> getBoard(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long boardId) {
        return ResponseEntity.ok(ApiResponse.success(mandalartService.getBoard(principal.getUsername(), boardId)));
    }

    @PutMapping("/{boardId}/cells/{row}/{col}")
    public ResponseEntity<ApiResponse<Void>> updateCell(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long boardId,
            @PathVariable int row,
            @PathVariable int col,
            @Valid @RequestBody MandalartCellUpdateRequestDto request) {
        mandalartService.updateCell(principal.getUsername(), boardId, row, col, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // 중앙 9칸(핵심 목표 + 세부목표 8개)을 채운 뒤 나머지 빈 칸을 AI로 채운다 - 이미 채워진 칸은
    // 건드리지 않는다(MandalartAiService 참고)
    @PostMapping("/{boardId}/ai-fill")
    public ResponseEntity<ApiResponse<MandalartBoardResponseDto>> fillWithAi(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long boardId) {
        return ResponseEntity.ok(ApiResponse.success(mandalartAiService.fillWithAi(principal.getUsername(), boardId)));
    }

    @DeleteMapping("/{boardId}")
    public ResponseEntity<ApiResponse<Void>> deleteBoard(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long boardId) {
        mandalartService.deleteBoard(principal.getUsername(), boardId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
