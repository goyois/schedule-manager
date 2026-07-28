package com.example.schedule_manager.domain.ai.repository;

import com.example.schedule_manager.domain.ai.entity.AiChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    // 대화창 전체 표시용 - 오래된 것부터
    List<AiChatMessage> findByUserIdOrderByCreatedAtAsc(Long userId);

    // LLM에 실어 보낼 최근 대화 컨텍스트용 - 최신순으로 N개만 뽑은 뒤 서비스 계층에서 뒤집어 시간순으로 쓴다
    List<AiChatMessage> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    void deleteByUserId(Long userId);
}
