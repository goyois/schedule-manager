package com.example.schedule_manager.domain.category.controller;

import com.example.schedule_manager.domain.category.dto.CategoryRequestDto;
import com.example.schedule_manager.domain.category.dto.CategoryResponseDto;
import com.example.schedule_manager.domain.category.service.CategoryService;
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
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    @PostMapping
    public ResponseEntity<ApiResponse<CategoryResponseDto>> createCategory(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody CategoryRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(categoryService.createCategory(principal.getUsername(), request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryResponseDto>> getCategory(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(categoryService.getCategory(principal.getUsername(), id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponseDto>>> getCategories(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(ApiResponse.success(categoryService.getCategories(principal.getUsername())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryResponseDto>> updateCategory(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id,
            @Valid @RequestBody CategoryRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(categoryService.updateCategory(principal.getUsername(), id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCategory(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id) {
        categoryService.deleteCategory(principal.getUsername(), id);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
