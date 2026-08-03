package com.example.schedule_manager.domain.schedule.service;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.schedule.dto.PageResponseDto;
import com.example.schedule_manager.domain.schedule.dto.ScheduleRequestDto;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.repository.ScheduleRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ScheduleEventPublisher scheduleEventPublisher;
    private final ScheduleEmbeddingService scheduleEmbeddingService;

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

        Schedule saved = scheduleRepository.save(schedule);
        scheduleEmbeddingService.reindexSchedule(user.getId(), saved);
        evictScheduleCacheForUser(user.getId());
        return ScheduleResponseDto.from(saved);
    }

    // RecurringScheduleService가 반복 규칙에서 미리 여러 occurrence를 만들 때 쓴다 - 건마다
    // createSchedule()을 반복 호출하면 그때마다 캐시 무효화·SSE 알림이 따로 나가 우수수 쏟아지므로,
    // 여러 건을 한 번에 저장하고 캐시 무효화·알림은 마지막에 한 번만 한다(모두 같은 유저 소유라고 가정)
    public List<ScheduleResponseDto> createSchedules(List<ScheduleRequestDto> requests, Long recurringScheduleId) {
        if (requests.isEmpty()) return List.of();

        List<Schedule> schedules = requests.stream().map(request -> {
            User user = userRepository.findById(request.userId()).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
            Category category = categoryRepository.findById(request.categoryId()).orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
            return Schedule.builder()
                    .title(request.title())
                    .content(request.content())
                    .startAt(request.startAt())
                    .endAt(request.endAt())
                    .status(request.status())
                    .user(user)
                    .category(category)
                    .recurringScheduleId(recurringScheduleId)
                    .build();
        }).toList();

        List<ScheduleResponseDto> saved = scheduleRepository.saveAll(schedules).stream()
                .map(ScheduleResponseDto::from)
                .toList();
        evictScheduleCacheForUser(schedules.get(0).getUser().getId());
        return saved;
    }

    // RecurringScheduleService가 반복 규칙에 대해 이미 만들어둔 occurrence 날짜와 겹치지 않는 후보
    // 날짜만 새로 만들기 위해 조회한다 - 사용자가 특정 occurrence의 시각을 직접 수정했을 수도 있으니
    // 정확한 시:분이 아니라 "그 날짜에 이미 하나 있는지"만 본다
    @Transactional(readOnly = true)
    public Set<LocalDate> getOccurrenceDates(Long recurringScheduleId) {
        return scheduleRepository.findByRecurringScheduleId(recurringScheduleId).stream()
                .map(schedule -> schedule.getStartAt().toLocalDate())
                .collect(Collectors.toSet());
    }

    // 반복 일정을 중단/삭제할 때, 아직 지나지 않은(PENDING) occurrence는 정리한다 - 이미 진행중/완료/
    // 취소된 occurrence는 지나간 기록이니 그대로 남긴다. 여러 건을 지우지만 캐시 무효화는 한 번만 한다
    public void deletePendingOccurrences(Long recurringScheduleId, Long userId) {
        List<Schedule> pending = scheduleRepository.findByRecurringScheduleIdAndStatus(recurringScheduleId, ScheduleStatus.PENDING);
        if (pending.isEmpty()) return;
        scheduleRepository.deleteAll(pending);
        evictScheduleCacheForUser(userId);
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

    // ReportService 전용 - status 필터/페이징 없이 [rangeStart, rangeEnd) 범위와 겹치는 일정만 조회한다.
    // getSchedules()와 같은 권한 규칙을 따르되(다만 ReportService는 항상 본인 id를 그대로 넘기므로
    // ADMIN 분기는 사실상 타지 않는다), 캐싱은 하지 않는다 - 리포트 기간(WEEK/MONTH/YEAR × referenceDate)
    // 조합이 getBoardSchedules의 페이지 조합만큼 다양해 캐시 적중률이 낮다(같은 이유로 getBoardSchedules도
    // 캐싱하지 않는다)
    @Transactional(readOnly = true)
    public List<ScheduleResponseDto> getSchedulesInRange(String requesterEmail, Long userId, Long categoryId,
                                                          LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        User requester = findUserByEmail(requesterEmail);
        Long targetUserId = requester.getUserType() == UserType.ADMIN ? userId : requester.getId();

        return scheduleRepository.searchSchedulesInRange(targetUserId, categoryId, rangeStart, rangeEnd);
    }

    // ReportService.buildRagContext 전용 - RAG가 매칭한 (기간 밖일 수 있는) id들을 바로 조회한다.
    // 이 id들은 이미 ScheduleEmbeddingService가 requester 본인 소유로 스코프해 찾아낸 것이므로
    // (docType+userId 필터) 여기서 다시 소유권을 확인할 필요가 없다
    @Transactional(readOnly = true)
    public List<ScheduleResponseDto> getSchedulesByIds(Set<Long> ids) {
        return scheduleRepository.searchSchedulesByIds(ids);
    }

    // 보드 뷰 상태 컬럼 하나("더보기" 대상)를 서버에서 LIMIT/OFFSET(Pageable)으로 페이징 조회한다.
    // getSchedules()와 같은 권한 규칙(USER는 본인 id로 강제, ADMIN은 요청 userId 그대로)을 따르되,
    // 대상 범위는 하루(date, 없으면 오늘)로 고정한다 - 보드 자체가 하루치 일정만 보여주는 뷰이기 때문
    // (feat-38), 전날/다음날 화살표(dashboard.js today-nav)로 date 를 바꿔가며 다시 조회한다.
    // getSchedules()와 달리 캐싱하지 않는다: 페이지 조합이 (email, userId, categoryId, status, date, size)로
    // 훨씬 다양해 캐시 적중률이 낮고, 하루 범위라 자정마다 저절로 갱신 대상이 바뀌는 데이터라 캐싱해서
    // 얻는 이득이 적다
    @Transactional(readOnly = true)
    public PageResponseDto<ScheduleResponseDto> getBoardSchedules(String requesterEmail, Long userId, Long categoryId,
                                                                    ScheduleStatus status, LocalDate date, Pageable pageable) {
        User requester = findUserByEmail(requesterEmail);
        Long targetUserId = requester.getUserType() == UserType.ADMIN ? userId : requester.getId();

        LocalDateTime rangeStart = (date != null ? date : LocalDate.now()).atStartOfDay();
        LocalDateTime rangeEnd = rangeStart.plusDays(1);

        Page<ScheduleResponseDto> page = scheduleRepository.searchBoardSchedules(
                targetUserId, categoryId, status, rangeStart, rangeEnd, pageable);
        return PageResponseDto.from(page);
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
        scheduleEmbeddingService.reindexSchedule(schedule.getUser().getId(), schedule);
        evictScheduleCacheForUser(schedule.getUser().getId());
        return ScheduleResponseDto.from(schedule);
    }

    // #v3: 위 updateSchedule() 과 같은 이유로 소유자 유저의 캐시만 무효화한다
    public void deleteSchedule(Long id) {
        Schedule schedule = findSchedule(id);
        Long ownerId = schedule.getUser().getId();
        scheduleRepository.delete(schedule);
        scheduleEmbeddingService.deleteScheduleEmbedding(id);
        evictScheduleCacheForUser(ownerId);
    }

    // 프론트 dashboard.js의 setInterval 기반 체크(checkScheduleTimers)는 그 탭이 열려 있을 때만
    // 동작한다는 한계가 있었다 - 이 스케줄러는 서버에서 직접 도니까 탭이 닫혀 있어도, 로그아웃
    // 상태여도 동작한다. 대신 User.autoStatusMode(예전엔 브라우저 localStorage에만 있던 값)를 DB로
    // 옮겨와야 서버가 "이 유저가 자동 모드를 켰는지"를 알 수 있다.
    //
    // 규칙은 프론트에 있던 것과 동일: 시작 시각이 지났고 아직 대기(PENDING) 상태면 진행중으로(종료
    // 시각이 없는 알림형 일정은 진행중으로 머물 종료 시점이 없으므로 곧바로 완료로), 종료 시각이
    // 지났고 아직 완료가 아니면 완료로 전환한다.
    //
    // @SpringBootTest는 이 스케줄도 그대로 활성화한 채 컨텍스트를 띄우므로, 테스트 실행 중에도 실제로
    // 한 번 돈다(첫 실행은 fixedRate 특성상 컨텍스트 기동 직후). 기존 테스트들은 autoStatusMode=true인
    // 유저를 만들지 않으므로 조회 결과가 항상 비어 있어 무해하고, 이 기능을 검증하는 테스트는 타이머가
    // 자연히 돌기를 기다리지 않고 이 메서드를 직접 호출해 확정적으로 검증한다
    @Scheduled(fixedRate = 60_000)
    public void autoTransitionScheduleStatuses() {
        LocalDateTime now = LocalDateTime.now();

        List<Schedule> justStarted = scheduleRepository
                .findByStatusAndStartAtLessThanEqualAndUser_AutoStatusModeTrue(ScheduleStatus.PENDING, now);
        for (Schedule schedule : justStarted) {
            ScheduleStatus next = schedule.getEndAt() != null ? ScheduleStatus.IN_PROGRESS : ScheduleStatus.COMPLETED;
            transitionStatus(schedule, next);
        }

        List<Schedule> justEnded = scheduleRepository
                .findByStatusInAndEndAtLessThanEqualAndUser_AutoStatusModeTrue(
                        List.of(ScheduleStatus.PENDING, ScheduleStatus.IN_PROGRESS), now);
        for (Schedule schedule : justEnded) {
            transitionStatus(schedule, ScheduleStatus.COMPLETED);
        }
    }

    // 이 유저의 일정 변경 이벤트를 구독한다(SSE) - 컨트롤러가 이 메서드가 반환한 SseEmitter를
    // 그대로 응답 바디로 돌려주면, 이후 이 유저의 일정이 바뀔 때마다(evictScheduleCacheForUser 참고)
    // 서버가 이 연결로 직접 이벤트를 밀어준다
    @Transactional(readOnly = true)
    public SseEmitter subscribeToScheduleEvents(String requesterEmail) {
        User requester = findUserByEmail(requesterEmail);
        return scheduleEventPublisher.subscribe(requester.getId());
    }

    private void transitionStatus(Schedule schedule, ScheduleStatus status) {
        schedule.update(schedule.getTitle(), schedule.getContent(), schedule.getStartAt(), schedule.getEndAt(),
                status, schedule.getCategory());
        evictScheduleCacheForUser(schedule.getUser().getId());
    }

    // #v3: getSchedules() 캐시 키는 "requesterEmail-userId-categoryId" 조합이라 특정 키 하나만 정확히
    // 골라 지울 순 없지만, userId 세그먼트로 패턴 매칭(Redis KEYS)해서 그 유저와 관련된 키만 지울 수는 있다.
    // @CacheEvict SpEL 로는 와일드카드 삭제가 안 되므로 RedisTemplate 을 직접 사용한다
    // (RedisConfig 에 이런 용도로 미리 준비된 redisTemplate 빈을 재사용).
    //
    // #v6 [fix] ADMIN 이 userId 없이(전체 조회) 캐싱한 키(예: "email-null-3", "email-null-null")는
    // "*-{userId}-*" 패턴에 안 걸려 무효화되지 않는 문제가 있었다 - ADMIN 전체조회는 드물게 쓰이는
    // 경로라고 보고 일부러 남겨뒀던 known limitation인데(이전 커밋 참고), ADMIN 계정이 실제로 즐겨
    // 쓰는 화면이 되면서 "삭제해도 새로고침해도 계속 떠 있다"로 드러났다. 이 목록에는 어떤 유저의
    // 일정이든 다 섞여 있을 수 있으므로, 특정 유저 캐시 무효화 시 "-null-" 패턴도 함께 지운다
    // (RedisConfig 에 entryTtl 5분이 걸려 있어 방치해도 언젠가는 사라지지만, 그동안은 삭제한 일정이
    // 계속 보이는 게 사용자 입장에서 버그로 느껴지므로 즉시 지운다)
    //
    // Redis 장애 시에도 update/delete 자체가 500 으로 번지지 않도록 fail-open 한다
    // (CacheFailSafeErrorHandler 는 @Cacheable/@CacheEvict 애노테이션 경로에만 적용되고, 이 직접 호출엔
    // 안 걸리므로 여기서 직접 같은 패턴을 적용 — JwtAuthenticationFilter.isBlacklisted() 참고)
    // 캐시 무효화와 함께, 이 유저를 구독 중인 SSE 연결(열려 있는 브라우저 탭)에도 곧바로 알린다 -
    // 이 메서드가 호출되는 지점이 곧 "이 유저의 일정이 실제로 바뀐 시점"과 정확히 일치하기 때문이다
    private void evictScheduleCacheForUser(Long userId) {
        evictKeysMatching(SCHEDULE_CACHE + "::*-" + userId + "-*");
        evictKeysMatching(SCHEDULE_CACHE + "::*-null-*");
        scheduleEventPublisher.notifyChanged(userId);
    }

    private void evictKeysMatching(String pattern) {
        try {
            Set<String> keys = scanKeys(pattern);
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (DataAccessException e) {
            log.warn("일정 캐시 삭제 실패 - pattern={}", pattern, e);
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
