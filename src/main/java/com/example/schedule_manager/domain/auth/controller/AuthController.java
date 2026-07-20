package com.example.schedule_manager.domain.auth.controller;

import com.example.schedule_manager.domain.auth.dto.GoogleLoginRequestDto;
import com.example.schedule_manager.domain.auth.dto.LoginRequestDto;
import com.example.schedule_manager.domain.auth.dto.LoginResponseDto;
import com.example.schedule_manager.domain.auth.dto.RefreshTokenRequestDto;
import com.example.schedule_manager.domain.auth.service.AuthService;
import com.example.schedule_manager.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @Value("${google.oauth.client-id}")
    private String googleClientId;

    // 구글 OAuth client-id 는 비밀값이 아니라(프론트에 노출되는 값) 공개 조회용으로 열어둔다 —
    // 프론트가 하드코딩 없이 GIS(Google Identity Services) 초기화 시 이 값을 가져다 쓴다
    @GetMapping("/google/client-id")
    public ResponseEntity<ApiResponse<Map<String, String>>> googleClientId() {
        return ResponseEntity.ok(ApiResponse.success(Map.of("clientId", googleClientId)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponseDto>> login(@RequestBody LoginRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(authService.login(request)));
    }

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<LoginResponseDto>> loginWithGoogle(@RequestBody GoogleLoginRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(authService.loginWithGoogle(request)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponseDto>> refresh(@RequestBody RefreshTokenRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(authService.refresh(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        String token = resolveToken(request);
        authService.logout(token);
        return ResponseEntity.ok(ApiResponse.success());
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        throw new IllegalArgumentException("토큰이 없습니다.");
    }
}
