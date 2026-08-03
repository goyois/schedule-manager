package com.example.schedule_manager.domain.schedule.repository;

import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

// ScheduleRepository(JpaRepository) 의 커스텀 확장. userId/categoryId 조건에 따라 갈라지던
// findAllByUserId / findAllByCategoryId / findAllByUserIdAndCategoryId / findAll() 네 갈래를
// QueryDSL 동적 쿼리 하나로 통합하고, 결과를 엔티티가 아닌 ScheduleResponseDto 로 바로 projection 한다
public interface ScheduleRepositoryCustom {

    List<ScheduleResponseDto> searchSchedules(Long userId, Long categoryId);

    // 보드 뷰 전용 - 상태 컬럼 하나(status)를 [rangeStart, rangeEnd) 범위와 겹치는 일정만으로 좁혀
    // LIMIT/OFFSET(Pageable) 기반으로 조회한다. dashboard.js의 "더보기"가 이 메서드를 호출한다
    Page<ScheduleResponseDto> searchBoardSchedules(Long userId, Long categoryId, ScheduleStatus status,
                                                    LocalDateTime rangeStart, LocalDateTime rangeEnd, Pageable pageable);

    // ReportService 전용 - status 필터/페이징 없이 [rangeStart, rangeEnd) 범위와 겹치는 일정만 조회한다.
    // 리포트가 필요로 하는 건 이번 기간+직전 기간뿐인데, 이전엔 유저 전체 이력을 무제한으로 가져온 뒤
    // 메모리에서 걸러냈다 - 이 메서드로 그 조회 자체를 DB에서 범위로 좁힌다
    List<ScheduleResponseDto> searchSchedulesInRange(Long userId, Long categoryId, LocalDateTime rangeStart, LocalDateTime rangeEnd);

    // ReportService.buildRagContext 전용 - RAG가 매칭한 일정은 이번/직전 기간 밖(과거 몇 달 전 등)일
    // 수 있어 위 range 조회로는 안 잡힌다. 매칭 건수가 RAG_TOP_K(5)로 이미 적어, id로 바로 targeted
    // 조회하는 편이 "전체 이력을 무제한으로 들고 있기"보다 훨씬 싸다
    List<ScheduleResponseDto> searchSchedulesByIds(Set<Long> ids);
}
