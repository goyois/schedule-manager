package com.example.schedule_manager.domain.recurringschedule.controller;

import com.example.schedule_manager.domain.recurringschedule.dto.RecurringScheduleRequestDto;
import com.example.schedule_manager.domain.recurringschedule.dto.RecurringScheduleResponseDto;
import com.example.schedule_manager.domain.recurringschedule.service.RecurringScheduleService;
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
@RequestMapping("/api/recurring-schedules")
public class RecurringScheduleController {

    private final RecurringScheduleService recurringScheduleService;

    @PostMapping
    public ResponseEntity<ApiResponse<RecurringScheduleResponseDto>> create(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody RecurringScheduleRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                recurringScheduleService.createRecurringSchedule(principal.getUsername(), request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<RecurringScheduleResponseDto>>> list(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(ApiResponse.success(recurringScheduleService.getRecurringSchedules(principal.getUsername())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id) {
        recurringScheduleService.deleteRecurringSchedule(principal.getUsername(), id);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
