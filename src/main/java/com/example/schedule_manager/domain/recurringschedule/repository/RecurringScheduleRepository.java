package com.example.schedule_manager.domain.recurringschedule.repository;

import com.example.schedule_manager.domain.recurringschedule.entity.RecurringSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface RecurringScheduleRepository extends JpaRepository<RecurringSchedule, Long> {

    List<RecurringSchedule> findByUserId(Long userId);

    // RecurringScheduleService.extendActiveRecurringSchedules()(@Scheduled) 전용 - 아직 끝나지
    // 않은(무기한이거나 종료일이 오늘 이후인) 규칙만 매일 미래 창을 다시 채운다
    List<RecurringSchedule> findByEndDateIsNullOrEndDateGreaterThanEqual(LocalDate date);
}
