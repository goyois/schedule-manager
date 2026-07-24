package com.example.schedule_manager.domain.ai.controller;

import com.example.schedule_manager.domain.ai.dto.ScheduleSuggestRequestDto;
import com.example.schedule_manager.domain.ai.dto.ScheduleSuggestResponseDto;
import com.example.schedule_manager.domain.ai.service.AiService;
import com.example.schedule_manager.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;

    @PostMapping("/suggest")
    public ResponseEntity<ApiResponse<ScheduleSuggestResponseDto>> suggest(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody ScheduleSuggestRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(aiService.suggestSchedule(principal.getUsername(), request.prompt())));
    }
}
