package com.example.schedule_manager.domain.schedule.repository;

import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long>, ScheduleRepositoryCustom {

    List<Schedule> findAllByUserId(Long userId);

    List<Schedule> findAllByCategoryId(Long categoryId);

    boolean existsByCategoryId(Long categoryId);

    List<Schedule> findAllByUserIdAndCategoryId(Long userId, Long categoryId);

    List<Schedule> findAllByUserIdAndStatus(Long userId, ScheduleStatus status);
}
