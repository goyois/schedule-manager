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
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ScheduleService {

    // #v2
    // getSchedules() 조회 결과를 캐싱하는 캐시 이름. ScheduleCacheQueryService 의 @Cacheable 과
    // evictScheduleCacheForUser() 가 모두 이 이름을 공유해야 무효화가 실제로 캐싱된 항목에 적용된다
    static final String SCHEDULE_CACHE = "schedules";

    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ScheduleCacheQueryService scheduleCacheQueryService;

    // #v3: 새 일정이 생기면 해당 유저의 목록 캐시가 최신 상태가 아니게 된다
    // 이전엔 특정 키 하나만 골라 지울 수 없다는 이유로 캐시 전체(allEntries)를 무효화했는데,
    // 그러면 무관한 다른 유저들의 캐시까지 이 한 번의 쓰기로 전부 날아간다.
    // create 시점엔 이미 request.userId() 로 대상 유저를 알고 있으므로, 그 유저와 관련된 키만 지운다
    public ScheduleResponseDto createSchedule(ScheduleRequestDto request) {
        User user = userRepository.findById(request.userId()).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Category category = categoryRepository.findById(request.categoryId()).orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));

        Schedule schedule = Schedule.builder()
                .title(request.title())
                .content(request.content())
                .startAt(request.startAt())
                .endAt(request.endAt())
                .status(request.status())
                .user(user)
                .category(category)
                .build();

        ScheduleResponseDto response = ScheduleResponseDto.from(scheduleRepository.save(schedule));
        evictScheduleCacheForUser(user.getId());
        return response;
    }

    // 요청자 role 에 따라 결과를 제한한다: ADMIN 은 임의의 일정을 조회할 수 있고,
    // 일반 USER 는 본인 소유 일정이 아니면 조회할 수 없다
    @Transactional(readOnly = true)
    public ScheduleResponseDto getSchedule(String requesterEmail, Long id) {
        User requester = findUserByEmail(requesterEmail);
        Schedule schedule = findSchedule(id);

        if (requester.getUserType() != UserType.ADMIN && !schedule.getUser().getId().equals(requester.getId())) {
            throw new BusinessException(ErrorCode.SCHEDULE_ACCESS_DENIED);
        }
        return ScheduleResponseDto.from(schedule);
    }

    // #v2
    // (요청자 email + targetUserId + categoryId) 를 키로 조회 결과(List<ScheduleResponseDto>) 를 캐싱한다
    // 첫 호출은 DB 조회 후 결과를 Redis 에 저장하고, 이후 같은 키로 들어오는 호출은 DB 를 거치지 않고
    // Redis 에서 바로 반환한다 (캐시 적용 전/후 성능 비교의 대상이 되는 지점)
    // unless: 조회 결과가 비어 있으면 캐싱하지 않는다 (아직 일정이 없는 유저의 빈 목록이 계속 캐싱되는 것을 방지)
    //
    // role 권한: ADMIN 은 userId 파라미터를 그대로 사용해 임의 유저(또는 전체)의 일정을 조회할 수 있지만,
    // 일반 USER 는 파라미터로 넘어온 userId 를 신뢰하지 않고 본인 id 로 강제해 본인 일정만 돌려받는다
    // #v4: 이전엔 Schedule 엔티티를 조회한 뒤 스트림에서 ScheduleResponseDto::from 으로 매핑했는데,
    // user/category 가 LAZY 라 매핑 중 schedule.getUser()/getCategory() 를 호출할 때마다
    // 영속성 컨텍스트에 없는 프록시는 추가 SELECT 를 유발했다(N+1). ScheduleRepositoryImpl.searchSchedules() 는
    // QueryDSL projection 으로 user/category 를 join 해 DTO 필드로 바로 뽑아오므로 SQL 1번으로 끝난다
    // #v5: 캐시 키를 (요청 파라미터 userId 가 아니라) 실제 조회에 쓰이는 targetUserId 로 만들어야
    // evictScheduleCacheForUser() 의 "*-{userId}-*" 패턴이 이 키에 매치된다. 그런데 targetUserId 는
    // 이 메서드 안에서 계산되므로, 캐싱 자체는 별도 빈(ScheduleCacheQueryService)에 위임한다
    // (같은 클래스 안에서 @Cacheable 메서드를 self-invocation 으로 호출하면 프록시를 안 거쳐 캐싱이 무시됨)
    @Transactional(readOnly = true)
    public List<ScheduleResponseDto> getSchedules(String requesterEmail, Long userId, Long categoryId) {
        User requester = findUserByEmail(requesterEmail);
        Long targetUserId = requester.getUserType() == UserType.ADMIN ? userId : requester.getId();

        return scheduleCacheQueryService.getSchedules(requesterEmail, targetUserId, categoryId);
    }

    // #v3: update/delete 는 매개변수로 스케줄 id 만 받기 때문에, 이전엔 소유자(userId)를 알아내려면
    // 조회가 하나 더 필요하다는 이유로 캐시 전체(allEntries)를 무효화했다.
    // 그런데 findSchedule(id) 로 이미 스케줄을 로드하는 시점에 schedule.getUser().getId() 를 공짜로 알 수 있으므로,
    // 추가 조회 없이도 그 유저와 관련된 키만 골라 지울 수 있다
    public ScheduleResponseDto updateSchedule(Long id, ScheduleRequestDto request) {
        Schedule schedule = findSchedule(id);
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));

        schedule.update(
                request.title(),
                request.content(),
                request.startAt(),
                request.endAt(),
                request.status(),
                category
        );
        evictScheduleCacheForUser(schedule.getUser().getId());
        return ScheduleResponseDto.from(schedule);
    }

    // #v3: 위 updateSchedule() 과 같은 이유로 소유자 유저의 캐시만 무효화한다
    public void deleteSchedule(Long id) {
        Schedule schedule = findSchedule(id);
        Long ownerId = schedule.getUser().getId();
        scheduleRepository.delete(schedule);
        evictScheduleCacheForUser(ownerId);
    }

    // #v3: getSchedules() 캐시 키는 "requesterEmail-userId-categoryId" 조합이라 특정 키 하나만 정확히
    // 골라 지울 순 없지만, userId 세그먼트로 패턴 매칭(Redis KEYS)해서 그 유저와 관련된 키만 지울 수는 있다.
    // @CacheEvict SpEL 로는 와일드카드 삭제가 안 되므로 RedisTemplate 을 직접 사용한다
    // (RedisConfig 에 이런 용도로 미리 준비된 redisTemplate 빈을 재사용).
    //
    // 알려진 한계: ADMIN 이 userId 없이(전체 조회) 캐싱한 키(예: "email-null-3", "email-null-null")는
    // 이 패턴에 걸리지 않아 무효화되지 않는다 — 이 캐시엔 TTL 도 없어 그대로 stale 상태로 남는다.
    // ADMIN 전체조회는 드물게 쓰이는 경로라, 그 대가로 훨씬 흔한 "무관한 유저 캐시까지 통째로 날아가는" 문제를
    // 없애는 쪽을 택한 절충이다. 완전히 없애려면 캐시 키 구조 자체를 바꿔야 해서 이번 스코프 밖으로 둔다.
    //
    // Redis 장애 시에도 update/delete 자체가 500 으로 번지지 않도록 fail-open 한다
    // (CacheFailSafeErrorHandler 는 @Cacheable/@CacheEvict 애노테이션 경로에만 적용되고, 이 직접 호출엔
    // 안 걸리므로 여기서 직접 같은 패턴을 적용 — JwtAuthenticationFilter.isBlacklisted() 참고)
    private void evictScheduleCacheForUser(Long userId) {
        String pattern = SCHEDULE_CACHE + "::*-" + userId + "-*";
        try {
            Set<String> keys = scanKeys(pattern);
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (DataAccessException e) {
            log.warn("일정 캐시 삭제 실패 - userId={}", userId, e);
        }
    }

    // 원래는 redisTemplate.keys(pattern) (Redis KEYS 커맨드) 을 썼는데, KEYS 는 매치되는 키를 다 찾을 때까지
    // 전체 키스페이스를 한 번의 커맨드로 훑고, 그동안 Redis 의 단일 이벤트루프를 통째로 블로킹한다 — 이 메서드가
    // 일정 생성/수정/삭제마다 호출되므로, 키스페이스가 커지면 그때마다 다른 모든 클라이언트 요청이 지연될 수 있다.
    // SCAN 은 같은 O(전체 키스페이스) 전수조사를 커서 기반으로 작은 배치(COUNT)씩 나눠서 하기 때문에,
    // 한 번의 호출이 짧게 끝나 그 사이사이에 다른 명령이 끼어들 수 있다 (논블로킹).
    // 대신 KEYS 와 달리 실행 시점의 스냅샷이 아니라서, 순회 도중 새로 추가/삭제되는 키는 중복 반환되거나
    // 누락될 수 있다 — 다만 이건 "캐시 evict가 이번 텀에 안 잡히고 다음 쓰기에 잡히는" 정도의 트레이드오프라
    // (evict 대상 키는 애초에 이 메서드 호출 시점 이전에 쓰인 것들이라 스캔 도중 사라질 일은 없고, 스캔 도중
    // 새로 생기는 키는 이번 evict 대상이 아니었으므로 놓쳐도 무해하다) 이 용도에는 안전하다.
    private Set<String> scanKeys(String pattern) {
        return redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> keys = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return keys;
        });
    }

    private Schedule findSchedule(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
