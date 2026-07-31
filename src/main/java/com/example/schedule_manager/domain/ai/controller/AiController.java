package com.example.schedule_manager.domain.ai.controller;

import com.example.schedule_manager.domain.ai.dto.AiChatExchangeDto;
import com.example.schedule_manager.domain.ai.dto.AiChatMessageDto;
import com.example.schedule_manager.domain.ai.dto.AiChatMessageRequestDto;
import com.example.schedule_manager.domain.ai.dto.AiChatRegisterRequestDto;
import com.example.schedule_manager.domain.ai.service.AiService;
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
@RequestMapping("/api/ai/chat")
public class AiController {

    private final AiService aiService;

    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<List<AiChatMessageDto>>> getConversation(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(ApiResponse.success(aiService.getConversation(principal.getUsername())));
    }

    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<AiChatExchangeDto>> sendMessage(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody AiChatMessageRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(aiService.sendMessage(principal.getUsername(), request.message())));
    }

    @PatchMapping("/messages/{id}/register")
    public ResponseEntity<ApiResponse<AiChatMessageDto>> markRegistered(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id,
            @Valid @RequestBody AiChatRegisterRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(aiService.markRegistered(principal.getUsername(), id, request.scheduleId())));
    }

    // SCHEDULE_RECOMMENDATION이 한 번에 여러 일정을 제안했을 때, 그중 특정 항목(itemId)이 등록됐음을 기록한다
    @PatchMapping("/messages/{id}/items/{itemId}/register")
    public ResponseEntity<ApiResponse<AiChatMessageDto>> markItemRegistered(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody AiChatRegisterRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                aiService.markItemRegistered(principal.getUsername(), id, itemId, request.scheduleId())));
    }

    @DeleteMapping("/messages")
    public ResponseEntity<ApiResponse<Void>> clearConversation(@AuthenticationPrincipal UserDetails principal) {
        aiService.clearConversation(principal.getUsername());
        return ResponseEntity.ok(ApiResponse.success());
    }
}
