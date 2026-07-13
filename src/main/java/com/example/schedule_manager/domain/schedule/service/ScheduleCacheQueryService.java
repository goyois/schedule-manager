package com.example.schedule_manager.domain.schedule.service;

import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// ScheduleService.getSchedules() 에서 권한에 따라 계산한 targetUserId(실제 조회 대상 유저)를
// 캐시 키에 그대로 반영하기 위해 분리된 빈. @Cacheable 메서드를 같은 클래스 안에서 self-invocation
// 으로 호출하면 프록시를 거치지 않아 캐싱이 동작하지 않으므로 별도 빈으로 둔다
@Service
@RequiredArgsConstructor
class ScheduleCacheQueryService {

    private final ScheduleRepository scheduleRepository;

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = ScheduleService.SCHEDULE_CACHE, key = "#requesterEmail + '-' + #targetUserId + '-' + #categoryId", unless = "#result.isEmpty()")
    public List<ScheduleResponseDto> getSchedules(String requesterEmail, Long targetUserId, Long categoryId) {
        return scheduleRepository.searchSchedules(targetUserId, categoryId);
    }
}
