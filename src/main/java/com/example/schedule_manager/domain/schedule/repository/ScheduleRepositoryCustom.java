package com.example.schedule_manager.domain.schedule.repository;

import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;

import java.util.List;

// ScheduleRepository(JpaRepository) 의 커스텀 확장. userId/categoryId 조건에 따라 갈라지던
// findAllByUserId / findAllByCategoryId / findAllByUserIdAndCategoryId / findAll() 네 갈래를
// QueryDSL 동적 쿼리 하나로 통합하고, 결과를 엔티티가 아닌 ScheduleResponseDto 로 바로 projection 한다
public interface ScheduleRepositoryCustom {

    List<ScheduleResponseDto> searchSchedules(Long userId, Long categoryId);
}
