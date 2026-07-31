package com.example.schedule_manager.domain.ai.repository;

import com.example.schedule_manager.domain.ai.entity.AiChatSuggestedSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiChatSuggestedScheduleRepository extends JpaRepository<AiChatSuggestedSchedule, Long> {
}
