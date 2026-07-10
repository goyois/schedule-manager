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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "쓰기 시 캐시 전체 무효화(allEntries=true)" 대비 "쓰기 유저 것만 타겟 무효화"의
 * blast radius 차이를 측정한다.
 *
 * bystander 유저 여러 명이 각자 GET /api/schedules 로 자기 캐시를 채운 뒤,
 * writer 유저 한 명이 POST /api/schedules 로 일정을 하나 생성한다(단 한 번의 쓰기).
 * 그 직후 (1) bystander들의 캐시 키가 몇 개나 살아남았는지, (2) bystander들이 재조회할 때
 * 응답 시간이 어떻게 달라지는지를 측정한다.
 *
 * allEntries=true 상태에서 실행하면 생존 키 0개 / 재조회 전부 DB 재조회(느림)가 나오고,
 * 유저별 타겟 evict로 바꾼 뒤 실행하면 생존 키 전부 유지 / 재조회 전부 캐시 히트(빠름)가 나와야 한다.
 * 같은 테스트를 코드 변경 전/후로 두 번 돌려서 비교하는 용도.
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleCacheEvictionBenchmarkTest {

    private static final String SCHEDULE_CACHE_NAME = "schedules";
    private static final int BYSTANDER_COUNT = 10;
    private static final int SCHEDULES_PER_BYSTANDER = 200;

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
    private List<User> bystanders;
    private User writer;
    private List<Schedule> createdSchedules;

    @BeforeEach
    void setUp() {
        clearScheduleCache();

        category = categoryRepository.save(Category.builder()
                .name("bench-category-" + System.nanoTime())
                .build());

        bystanders = new ArrayList<>();
        createdSchedules = new ArrayList<>();

        for (int u = 0; u < BYSTANDER_COUNT; u++) {
            User bystander = userRepository.save(User.builder()
                    .username("bystander-" + u)
                    .password("bench-password")
                    .email("bystander-" + u + "-" + System.nanoTime() + "@test.com")
                    .userType(UserType.USER)
                    .build());
            bystanders.add(bystander);

            LocalDateTime now = LocalDateTime.now();
            List<Schedule> schedules = new ArrayList<>();
            for (int i = 0; i < SCHEDULES_PER_BYSTANDER; i++) {
                schedules.add(Schedule.builder()
                        .title("bystander-" + u + "-title-" + i)
                        .content("content-" + i)
                        .startAt(now.plusMinutes(i))
                        .endAt(now.plusMinutes(i + 30))
                        .status(ScheduleStatus.PENDING)
                        .user(bystander)
                        .category(category)
                        .build());
            }
            createdSchedules.addAll(scheduleRepository.saveAll(schedules));
        }

        writer = userRepository.save(User.builder()
                .username("writer")
                .password("bench-password")
                .email("writer-" + System.nanoTime() + "@test.com")
                .userType(UserType.USER)
                .build());
    }

    @AfterEach
    void tearDown() {
        scheduleRepository.deleteAll(scheduleRepository.findAllByUserId(writer.getId()));
        scheduleRepository.deleteAll(createdSchedules);
        userRepository.delete(writer);
        userRepository.deleteAll(bystanders);
        categoryRepository.delete(category);
        clearScheduleCache();
    }

    private void clearScheduleCache() {
        var cache = cacheManager.getCache(SCHEDULE_CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    @DisplayName("한 유저의 쓰기가 다른 유저들의 캐시에 미치는 영향(blast radius) 측정")
    void writeBlastRadius() {
        ParameterizedTypeReference<ApiResponse<List<ScheduleResponseDto>>> listType =
                new ParameterizedTypeReference<>() {};

        // 1) bystander 전원의 캐시를 채운다
        for (User bystander : bystanders) {
            ResponseEntity<ApiResponse<List<ScheduleResponseDto>>> response = restTemplate.exchange(
                    "/api/schedules", HttpMethod.GET, authEntity(bystander), listType);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getData()).hasSize(SCHEDULES_PER_BYSTANDER);
        }

        Set<String> keysBeforeWrite = redisTemplate.keys(SCHEDULE_CACHE_NAME + "::*");
        log.info("쓰기 전 캐시 키 개수: {}", keysBeforeWrite == null ? 0 : keysBeforeWrite.size());
        assertThat(keysBeforeWrite).hasSize(BYSTANDER_COUNT);

        // 2) writer 가 일정 하나를 생성한다 (단 한 번의 쓰기)
        ScheduleRequestDto createRequest = new ScheduleRequestDto(
                "writer-title", "writer-content",
                LocalDateTime.now(), LocalDateTime.now().plusHours(1),
                ScheduleStatus.PENDING, writer.getId(), category.getId());
        ParameterizedTypeReference<ApiResponse<ScheduleResponseDto>> createType =
                new ParameterizedTypeReference<>() {};
        ResponseEntity<ApiResponse<ScheduleResponseDto>> createResponse = restTemplate.exchange(
                "/api/schedules", HttpMethod.POST,
                new HttpEntity<>(createRequest, authHeaders(writer)), createType);
        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();

        // 3) 측정 A: bystander 캐시 키가 몇 개나 살아남았는지
        Set<String> keysAfterWrite = redisTemplate.keys(SCHEDULE_CACHE_NAME + "::*");
        int survivedCount = keysAfterWrite == null ? 0 : keysAfterWrite.size();
        log.info("========== [캐시 쓰기 전략] blast radius 측정 결과 ==========");
        log.info("bystander 수            : " + BYSTANDER_COUNT);
        log.info("쓰기 전 캐시 키 개수     : " + (keysBeforeWrite == null ? 0 : keysBeforeWrite.size()));
        log.info("writer 쓰기 1회 이후 생존 캐시 키 개수 : " + survivedCount + " / " + BYSTANDER_COUNT);

        // 4) 측정 B: bystander 재조회 응답 시간
        List<Long> elapsedMillis = new ArrayList<>();
        for (User bystander : bystanders) {
            long start = System.nanoTime();
            ResponseEntity<ApiResponse<List<ScheduleResponseDto>>> response = restTemplate.exchange(
                    "/api/schedules", HttpMethod.GET, authEntity(bystander), listType);
            elapsedMillis.add((System.nanoTime() - start) / 1_000_000);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getData()).hasSize(SCHEDULES_PER_BYSTANDER);
        }
        printResult(elapsedMillis, survivedCount);
    }

    private HttpEntity<Void> authEntity(User user) {
        return new HttpEntity<>(authHeaders(user));
    }

    private HttpHeaders authHeaders(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtUtil.generateToken(user.getEmail()));
        return headers;
    }

    private void printResult(List<Long> elapsedMillis, int survivedCount) {
        long total = elapsedMillis.stream().mapToLong(Long::longValue).sum();
        double avg = (double) total / elapsedMillis.size();
        long min = elapsedMillis.stream().mapToLong(Long::longValue).min().orElseThrow();
        long max = elapsedMillis.stream().mapToLong(Long::longValue).max().orElseThrow();

        log.info("---- writer 쓰기 이후 bystander 재조회(GET) 응답 시간 ----");
        log.info("생존 캐시 키 비율   : " + survivedCount + " / " + BYSTANDER_COUNT);
        log.info("평균 응답 시간(ms) : " + avg);
        log.info("최소 응답 시간(ms) : " + min);
        log.info("최대 응답 시간(ms) : " + max);
        log.info("================================================================");
    }
}
