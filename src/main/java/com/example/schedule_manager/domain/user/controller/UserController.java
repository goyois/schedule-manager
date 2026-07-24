package com.example.schedule_manager.domain.user.controller;

import com.example.schedule_manager.domain.user.dto.UserRequestDto;
import com.example.schedule_manager.domain.user.dto.UserResponseDto;
import com.example.schedule_manager.domain.user.service.UserService;
import com.example.schedule_manager.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<ApiResponse<UserResponseDto>> createUser(@Valid @RequestBody UserRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(userService.createUser(request)));
    }

    // "/{id}" 보다 먼저 선언할 필요는 없다 - Spring 은 리터럴 경로("/me")를 path variable 패턴보다
    // 더 구체적인 매치로 취급해 순서와 무관하게 올바르게 매핑한다
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponseDto>> getCurrentUser(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(ApiResponse.success(userService.getCurrentUser(principal.getUsername())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponseDto>> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(userService.getUser(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponseDto>> updateUser(@PathVariable Long id, @Valid @RequestBody UserRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(userService.updateUser(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
