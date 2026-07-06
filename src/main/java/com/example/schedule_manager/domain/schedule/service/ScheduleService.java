package com.example.schedule_manager.domain.schedule.service;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.schedule.dto.ScheduleRequestDto;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.repository.ScheduleRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ScheduleService {

    // #v2
    // getSchedules() 조회 결과를 캐싱하는 캐시 이름. 아래 @Cacheable/@CacheEvict 가 모두 이 이름을 공유해야
    // 무효화가 실제로 캐싱된 항목에 적용된다
    private static final String SCHEDULE_CACHE = "schedules";

    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;

    // #v2: 새 일정이 생기면 해당 유저의 목록 캐시가 최신 상태가 아니게 된다
    // getSchedules() 캐시 키가 이제 userId + categoryId 조합이라 특정 키 하나만 골라 지울 수 없으므로 전체 무효화한다
    @CacheEvict(cacheNames = SCHEDULE_CACHE, allEntries = true)
    public ScheduleResponseDto createSchedule(ScheduleRequestDto request) {
        User user = userRepository.findById(request.userId()).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유저입니다."));
        Category category = categoryRepository.findById(request.categoryId()).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 카테고리입니다."));

        Schedule schedule = Schedule.builder()
                .title(request.title())
                .content(request.content())
                .startAt(request.startAt())
                .endAt(request.endAt())
                .status(request.status())
                .user(user)
                .category(category)
                .build();

        return ScheduleResponseDto.from(scheduleRepository.save(schedule));
    }

    // 요청자 role 에 따라 결과를 제한한다: ADMIN 은 임의의 일정을 조회할 수 있고,
    // 일반 USER 는 본인 소유 일정이 아니면 조회할 수 없다
    @Transactional(readOnly = true)
    public ScheduleResponseDto getSchedule(String requesterEmail, Long id) {
        User requester = findUserByEmail(requesterEmail);
        Schedule schedule = findSchedule(id);

        if (requester.getUserType() != UserType.ADMIN && !schedule.getUser().getId().equals(requester.getId())) {
            throw new IllegalArgumentException("본인의 일정만 조회할 수 있습니다.");
        }
        return ScheduleResponseDto.from(schedule);
    }

    // #v2
    // (요청자 email + userId + categoryId) 를 키로 조회 결과(List<ScheduleResponseDto>) 를 캐싱한다
    // 첫 호출은 DB 조회 후 결과를 Redis 에 저장하고, 이후 같은 키로 들어오는 호출은 DB 를 거치지 않고
    // Redis 에서 바로 반환한다 (캐시 적용 전/후 성능 비교의 대상이 되는 지점)
    // unless: 조회 결과가 비어 있으면 캐싱하지 않는다 (아직 일정이 없는 유저의 빈 목록이 계속 캐싱되는 것을 방지)
    //
    // role 권한: ADMIN 은 userId 파라미터를 그대로 사용해 임의 유저(또는 전체)의 일정을 조회할 수 있지만,
    // 일반 USER 는 파라미터로 넘어온 userId 를 신뢰하지 않고 본인 id 로 강제해 본인 일정만 돌려받는다
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = SCHEDULE_CACHE, key = "#requesterEmail + '-' + #userId + '-' + #categoryId", unless = "#result.isEmpty()")
    public List<ScheduleResponseDto> getSchedules(String requesterEmail, Long userId, Long categoryId) {
        User requester = findUserByEmail(requesterEmail);
        Long targetUserId = requester.getUserType() == UserType.ADMIN ? userId : requester.getId();

        List<Schedule> schedules;
        if (targetUserId != null && categoryId != null) {
            schedules = scheduleRepository.findAllByUserIdAndCategoryId(targetUserId, categoryId);
        } else if (targetUserId != null) {
            schedules = scheduleRepository.findAllByUserId(targetUserId);
        } else if (categoryId != null) {
            schedules = scheduleRepository.findAllByCategoryId(categoryId);
        } else {
            schedules = scheduleRepository.findAll();
        }

        return schedules.stream()
                .map(ScheduleResponseDto::from)
                .toList();
    }

    // #v2: update/delete 는 매개변수로 스케줄 id 만 받기 때문에, 별도 조회 없이는 소유자(userId)를 알 수 없다
    // 그 소유자만 정확히 골라 무효화하려면 조회가 하나 더 필요해서, 대신 캐시 전체(allEntries)를 무효화한다
    // (수정/삭제 빈도가 조회보다 훨씬 낮은 걸 고려한 절충)
    @CacheEvict(cacheNames = SCHEDULE_CACHE, allEntries = true)
    public ScheduleResponseDto updateSchedule(Long id, ScheduleRequestDto request) {
        Schedule schedule = findSchedule(id);
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 카테고리입니다."));

        schedule.update(
                request.title(),
                request.content(),
                request.startAt(),
                request.endAt(),
                request.status(),
                category
        );
        return ScheduleResponseDto.from(schedule);
    }

    // #v2: 위 updateSchedule() 과 같은 이유로 캐시 전체를 무효화한다
    @CacheEvict(cacheNames = SCHEDULE_CACHE, allEntries = true)
    public void deleteSchedule(Long id) {
        scheduleRepository.delete(findSchedule(id));
    }

    private Schedule findSchedule(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 일정입니다."));
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유저입니다."));
    }
}
