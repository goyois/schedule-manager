package com.example.schedule_manager.domain.mandalart.repository;

import com.example.schedule_manager.domain.mandalart.entity.MandalartCell;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MandalartCellRepository extends JpaRepository<MandalartCell, Long> {

    List<MandalartCell> findByBoardIdOrderByRowIndexAscColIndexAsc(Long boardId);

    Optional<MandalartCell> findByBoardIdAndRowIndexAndColIndex(Long boardId, int rowIndex, int colIndex);

    void deleteByBoardId(Long boardId);
}
