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

    // RecurringScheduleService 전용 - 이미 만들어둔 occurrence 날짜와 겹치지 않는 후보 날짜만 새로
    // 만들기 위한 조회, 그리고 반복 일정을 중단/삭제할 때 아직 지나지 않은(PENDING) occurrence만 정리하기 위한 조회
    List<Schedule> findByRecurringScheduleId(Long recurringScheduleId);

    List<Schedule> findByRecurringScheduleIdAndStatus(Long recurringScheduleId, ScheduleStatus status);
}
