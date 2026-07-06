package com.example.schedule_manager.domain.schedule.controller;

import com.example.schedule_manager.domain.schedule.dto.ScheduleRequestDto;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.service.ScheduleService;
import com.example.schedule_manager.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    @PostMapping
    public ResponseEntity<ApiResponse<ScheduleResponseDto>> createSchedule(@RequestBody ScheduleRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(scheduleService.createSchedule(request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ScheduleResponseDto>> getSchedule(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(scheduleService.getSchedule(principal.getUsername(), id)));
    }

    // userId: ADMIN 만 유효 - 특정 유저(또는 비우면 전체)의 일정을 지정해 조회할 때 사용. 일반 USER 는 무시되고 본인 일정만 반환된다
    @GetMapping
    public ResponseEntity<ApiResponse<List<ScheduleResponseDto>>> getSchedules(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long categoryId) {
        return ResponseEntity.ok(ApiResponse.success(scheduleService.getSchedules(principal.getUsername(), userId, categoryId)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ScheduleResponseDto>> updateSchedule(@PathVariable Long id, @RequestBody ScheduleRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(scheduleService.updateSchedule(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteSchedule(@PathVariable Long id) {
        scheduleService.deleteSchedule(id);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
