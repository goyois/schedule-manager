package com.example.schedule_manager.domain.mandalart.repository;

import com.example.schedule_manager.domain.mandalart.entity.MandalartBoard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MandalartBoardRepository extends JpaRepository<MandalartBoard, Long> {

    List<MandalartBoard> findByUserIdOrderByCreatedAtDesc(Long userId);
}
