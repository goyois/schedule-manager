package com.example.schedule_manager.domain.schedule;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.schedule.dto.ScheduleRequestDto;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.example.schedule_manager.domain.schedule.repository.ScheduleRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.response.ApiResponse;
import com.example.schedule_manager.global.security.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScheduleService 의 create/update/delete 각각이 "쓰기 대상 유저의 캐시만" 지우고
 * 무관한 다른 유저(bystander)의 캐시는 그대로 두는지를 검증한다.
 *
 * 검증 방식: bystander 와 target 유저 각각의 목록 캐시를 미리 채워둔 뒤, target 유저에 대해
 * create/update/delete 를 한 번 수행하고 나서 Redis 에 남아있는 캐시 키를 직접 조회해
 * target 키는 사라지고 bystander 키는 살아있는지 확인한다 (allEntries=true 였다면 둘 다 사라진다).
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleCacheEvictionTest {

    private static final String SCHEDULE_CACHE_NAME = "schedules";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private JwtUtil jwtUtil;

    private Category category;
    private User target;
    private User bystander;

    @BeforeEach
    void setUp() {
        clearScheduleCache();

        category = categoryRepository.save(Category.builder()
                .name("evict-test-category-" + System.nanoTime())
                .build());

        target = createUser("target");
        bystander = createUser("bystander");

        // bystander 는 테스트 내내 쓰기가 일어나지 않는 쪽이라, 캐시가 채워질 수 있도록 일정을 하나 미리 만들어둔다
        // (getSchedules 는 unless = "#result.isEmpty()" 라서 빈 목록은 애초에 캐싱되지 않는다)
        saveSchedule(bystander, "bystander-existing");
    }

    @AfterEach
    void tearDown() {
        scheduleRepository.deleteAll(scheduleRepository.findAllByUserId(target.getId()));
        scheduleRepository.deleteAll(scheduleRepository.findAllByUserId(bystander.getId()));
        userRepository.delete(target);
        userRepository.delete(bystander);
        categoryRepository.delete(category);
        clearScheduleCache();
    }

    @Test
    @DisplayName("createSchedule: 생성 대상 유저의 캐시만 지워지고, 무관한 유저의 캐시는 살아남는다")
    void evictOnCreate() {
        saveSchedule(target, "target-existing");
        warmUpCache(target);
        warmUpCache(bystander);
        assertCachePresent(target);
        assertCachePresent(bystander);

        ScheduleRequestDto createRequest = buildRequest(target, "new-title");
        ResponseEntity<ApiResponse<ScheduleResponseDto>> response = restTemplate.exchange(
                "/api/schedules", HttpMethod.POST,
                new HttpEntity<>(createRequest, authHeaders(target)),
                new ParameterizedTypeReference<ApiResponse<ScheduleResponseDto>>() {});
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        assertCacheAbsent(target);
        assertCachePresent(bystander);
    }

    @Test
    @DisplayName("updateSchedule: 소유자 유저의 캐시만 지워지고, 무관한 유저의 캐시는 살아남는다")
    void evictOnUpdate() {
        Schedule schedule = saveSchedule(target, "before-title");
        warmUpCache(target);
        warmUpCache(bystander);
        assertCachePresent(target);
        assertCachePresent(bystander);

        ScheduleRequestDto updateRequest = buildRequest(target, "after-title");
        ResponseEntity<ApiResponse<ScheduleResponseDto>> response = restTemplate.exchange(
                "/api/schedules/" + schedule.getId(), HttpMethod.PUT,
                new HttpEntity<>(updateRequest, authHeaders(target)),
                new ParameterizedTypeReference<ApiResponse<ScheduleResponseDto>>() {});
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        assertCacheAbsent(target);
        assertCachePresent(bystander);

        Schedule updated = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("after-title");
    }

    @Test
    @DisplayName("deleteSchedule: 소유자 유저의 캐시만 지워지고, 무관한 유저의 캐시는 살아남는다")
    void evictOnDelete() {
        Schedule schedule = saveSchedule(target, "to-be-deleted");
        warmUpCache(target);
        warmUpCache(bystander);
        assertCachePresent(target);
        assertCachePresent(bystander);

        ResponseEntity<ApiResponse<Void>> response = restTemplate.exchange(
                "/api/schedules/" + schedule.getId(), HttpMethod.DELETE,
                authEntity(target),
                new ParameterizedTypeReference<ApiResponse<Void>>() {});
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        assertCacheAbsent(target);
        assertCachePresent(bystander);

        assertThat(scheduleRepository.findById(schedule.getId())).isEmpty();
    }

    // ---------- helpers ----------

    private User createUser(String prefix) {
        return userRepository.save(User.builder()
                .username(prefix + "-user")
                .password("test-password")
                .email(prefix + "-" + System.nanoTime() + "@test.com")
                .userType(UserType.USER)
                .build());
    }

    private Schedule saveSchedule(User owner, String title) {
        LocalDateTime now = LocalDateTime.now();
        return scheduleRepository.save(Schedule.builder()
                .title(title)
                .content("content")
                .startAt(now)
                .endAt(now.plusHours(1))
                .status(ScheduleStatus.PENDING)
                .user(owner)
                .category(category)
                .build());
    }

    private ScheduleRequestDto buildRequest(User owner, String title) {
        LocalDateTime now = LocalDateTime.now();
        return new ScheduleRequestDto(title, "content", now, now.plusHours(1),
                ScheduleStatus.PENDING, owner.getId(), category.getId());
    }

    // getSchedules 를 호출해 요청자 유저의 목록 캐시를 채운다
    private void warmUpCache(User user) {
        ResponseEntity<ApiResponse<List<ScheduleResponseDto>>> response = restTemplate.exchange(
                "/api/schedules", HttpMethod.GET, authEntity(user),
                new ParameterizedTypeReference<ApiResponse<List<ScheduleResponseDto>>>() {});
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isNotEmpty();
    }

    private void assertCachePresent(User user) {
        Set<String> keys = cacheKeysFor(user);
        assertThat(keys).as("유저 %s(id=%d) 의 캐시 키가 남아있어야 한다", user.getUsername(), user.getId()).isNotEmpty();
    }

    private void assertCacheAbsent(User user) {
        Set<String> keys = cacheKeysFor(user);
        assertThat(keys).as("유저 %s(id=%d) 의 캐시 키가 지워져 있어야 한다", user.getUsername(), user.getId()).isEmpty();
    }

    private Set<String> cacheKeysFor(User user) {
        Set<String> keys = redisTemplate.keys(SCHEDULE_CACHE_NAME + "::*-" + user.getId() + "-*");
        return keys == null ? Set.of() : keys;
    }

    private HttpEntity<Void> authEntity(User user) {
        return new HttpEntity<>(authHeaders(user));
    }

    private HttpHeaders authHeaders(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtUtil.generateToken(user.getEmail()));
        return headers;
    }

    private void clearScheduleCache() {
        var cache = cacheManager.getCache(SCHEDULE_CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
    }
}
