package com.example.schedule_manager.domain.schedule.repository;

import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

// ScheduleRepository(JpaRepository) 의 커스텀 확장. userId/categoryId 조건에 따라 갈라지던
// findAllByUserId / findAllByCategoryId / findAllByUserIdAndCategoryId / findAll() 네 갈래를
// QueryDSL 동적 쿼리 하나로 통합하고, 결과를 엔티티가 아닌 ScheduleResponseDto 로 바로 projection 한다
public interface ScheduleRepositoryCustom {

    List<ScheduleResponseDto> searchSchedules(Long userId, Long categoryId);

    // 보드 뷰 전용 - 상태 컬럼 하나(status)를 [rangeStart, rangeEnd) 범위와 겹치는 일정만으로 좁혀
    // LIMIT/OFFSET(Pageable) 기반으로 조회한다. dashboard.js의 "더보기"가 이 메서드를 호출한다
    Page<ScheduleResponseDto> searchBoardSchedules(Long userId, Long categoryId, ScheduleStatus status,
                                                    LocalDateTime rangeStart, LocalDateTime rangeEnd, Pageable pageable);
}
