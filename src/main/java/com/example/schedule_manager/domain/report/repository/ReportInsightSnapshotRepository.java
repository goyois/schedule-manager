package com.example.schedule_manager.domain.report.repository;

import com.example.schedule_manager.domain.report.entity.ReportInsightSnapshot;
import com.example.schedule_manager.domain.report.entity.ReportPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ReportInsightSnapshotRepository extends JpaRepository<ReportInsightSnapshot, Long> {

    Optional<ReportInsightSnapshot> findByUserIdAndPeriodAndRangeStart(Long userId, ReportPeriod period, LocalDate rangeStart);
}
