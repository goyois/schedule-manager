package com.example.schedule_manager.domain.schedule.repository;

import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long>, ScheduleRepositoryCustom {

    List<Schedule> findAllByUserId(Long userId);

    List<Schedule> findAllByCategoryId(Long categoryId);

    boolean existsByCategoryId(Long categoryId);

    List<Schedule> findAllByUserIdAndCategoryId(Long userId, Long categoryId);

    List<Schedule> findAllByUserIdAndStatus(Long userId, ScheduleStatus status);

    // ScheduleService.autoTransitionScheduleStatuses()(@Scheduled) 전용 조회 - 소유자의
    // autoStatusMode가 켜져 있는 유저의 일정만 대상으로 한다. "User_AutoStatusMode"의 언더스코어는
    // user.autoStatusMode 로 연관관계를 타고 들어가는 경로임을 명시하는 구분자(Spring Data 관례)
    List<Schedule> findByStatusAndStartAtLessThanEqualAndUser_AutoStatusModeTrue(ScheduleStatus status, LocalDateTime now);

    List<Schedule> findByStatusInAndEndAtLessThanEqualAndUser_AutoStatusModeTrue(Collection<ScheduleStatus> statuses, LocalDateTime now);
}
