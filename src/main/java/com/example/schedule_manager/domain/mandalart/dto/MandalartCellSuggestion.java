package com.example.schedule_manager.domain.mandalart.dto;

// MandalartAiService가 ChatClient.entity()로 구조화 응답을 받을 때 배열 원소로 쓰는 타입.
// row/col은 MandalartAiService가 실제로 "이번에 비어 있어서 채워달라고 요청한 좌표"와 대조해서만
// 신뢰한다(모델이 요청하지 않은 좌표를 지어내거나 이미 채워진 칸을 덮어쓰려 할 수 있으므로)
public record MandalartCellSuggestion(int row, int col, String content) {
}
