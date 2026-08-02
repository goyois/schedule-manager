package com.example.schedule_manager.domain.report.controller;

import com.example.schedule_manager.domain.report.dto.ReportInsightCacheDto;
import com.example.schedule_manager.domain.report.dto.ReportInsightDto;
import com.example.schedule_manager.domain.report.dto.ReportStatsDto;
import com.example.schedule_manager.domain.report.entity.ReportPeriod;
import com.example.schedule_manager.domain.report.service.ReportService;
import com.example.schedule_manager.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    // 결정적 통계(파이차트/완료율/직전 기간 대비) - AI 호출 없음, 항상 즉시 응답
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<ReportStatsDto>> getStats(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getStats(principal.getUsername(), period, date)));
    }

    // 마지막으로 저장해둔 AI 코멘트 + 그 이후 이 기간 일정이 바뀌었는지(stale) - AI 호출 없음, 레이트리밋 대상
    // 아님. 프론트가 리포트 페이지 진입/기간 전환마다 먼저 이걸 불러 저장된 결과가 있으면 바로 보여준다
    @GetMapping("/insight/cached")
    public ResponseEntity<ApiResponse<ReportInsightCacheDto>> getCachedInsight(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getCachedInsight(principal.getUsername(), period, date)));
    }

    // AI 생성 서술("잘한 점/아쉬운 점", 행동 패턴/성향 평가)을 실제로 (재)생성하고 저장한다 - AiRateLimiter
    // 적용 대상이므로 프론트에서 명시적인 사용자 액션(버튼 클릭)에만 호출한다. 저장(upsert)이라는 부수효과가
    // 있어 GET이 아니라 POST다(MandalartController의 ai-fill과 같은 이유)
    @PostMapping("/insight")
    public ResponseEntity<ApiResponse<ReportInsightDto>> generateInsight(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getInsight(principal.getUsername(), period, date)));
    }
}
