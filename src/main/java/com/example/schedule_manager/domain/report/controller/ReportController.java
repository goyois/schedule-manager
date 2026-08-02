package com.example.schedule_manager.domain.report.controller;

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

    // AI 생성 서술("잘한 점/아쉬운 점", 행동 패턴/성향 평가) - AiRateLimiter 적용 대상이므로 프론트에서
    // 명시적인 사용자 액션(버튼 클릭)에만 호출한다
    @GetMapping("/insight")
    public ResponseEntity<ApiResponse<ReportInsightDto>> getInsight(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getInsight(principal.getUsername(), period, date)));
    }
}
