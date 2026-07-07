package com.example.schedule_manager.domain.schedule;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.example.schedule_manager.domain.schedule.repository.ScheduleRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.response.ApiResponse;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시 적용 전, GET /api/schedules (일정 목록 조회 API)의 baseline 성능을 측정한다.
 * 이미 저장돼 있는 실제 카테고리(work/life/health/growth/admin)를 기준으로
 * 조회 성능 차이를 체감할 수 있는 수준의 일정 데이터를 만든 뒤 측정한다.
 * 캐시 적용 후 동일한 방식으로 측정해 두 결과를 비교하기 위한 기준값을 남기는 것이 목적이다.
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleServiceTest {

    // ScheduleService 에서 사용하는 캐시 이름과 동일해야 한다
    private static final String SCHEDULE_CACHE_NAME = "schedules";

    // 이미 저장되어 있는 카테고리 이름들 (사전 조건)
    private static final List<String> CATEGORY_NAMES = List.of("work", "life", "health", "growth", "admin");

    private static final int SCHEDULE_COUNT_PER_CATEGORY = 500; // 카테고리별 생성할 일정 건수
    private static final int SCHEDULE_COUNT = SCHEDULE_COUNT_PER_CATEGORY * CATEGORY_NAMES.size(); // 총 조회 대상 건수

    private static final int WARMUP_ROUNDS = 3;   // 측정 제외 워밍업 횟수
    private static final int MEASURE_ROUNDS = 20; // 실제 측정 횟수

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

    private User testUser;
    private List<Schedule> testSchedules;


    @BeforeEach
    void setUp() {

        clearScheduleCache();

        List<Category> categories = CATEGORY_NAMES.stream()
                .map(name -> categoryRepository.findByName(name)
                        .orElseThrow(() -> new IllegalStateException("사전에 저장되어 있어야 하는 카테고리가 없습니다: " + name)))
                .toList();

        testUser = userRepository.save(User.builder()
                .username("perf-test-user")
                .password("perf-test-password")
                .email("perf-test-" + System.nanoTime() + "@test.com")
                .userType(UserType.USER)
                .build());

        LocalDateTime now = LocalDateTime.now();
        List<Schedule> schedules = new ArrayList<>();

        for (Category category : categories) {
            for (int i = 0; i < SCHEDULE_COUNT_PER_CATEGORY; i++) {
                schedules.add(Schedule.builder()
                        .title(category.getName() + "-title-" + i)
                        .content(category.getName() + "-content-" + i)
                        .startAt(now.plusMinutes(i))
                        .endAt(now.plusMinutes(i + 30))
                        .status(ScheduleStatus.PENDING)
                        .user(testUser)
                        .category(category)
                        .build());
            }
        }
        testSchedules = scheduleRepository.saveAll(schedules);
    }


    @AfterEach
    void tearDown() {
        scheduleRepository.deleteAll(testSchedules);
        userRepository.delete(testUser);
        clearScheduleCache();

        var cache = cacheManager.getCache(SCHEDULE_CACHE_NAME);
        if (cache != null) cache.clear();

    }

    //캐시 클리어
    private void clearScheduleCache() {
        var cache = cacheManager.getCache(SCHEDULE_CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
    }



    @Test
    @DisplayName("개시 적용 후 성능 측정")
    void apiPerformance() {
        String url = "/api/schedules?userId=" + testUser.getId();
        ParameterizedTypeReference<ApiResponse<List<ScheduleResponseDto>>> responseType =
                new ParameterizedTypeReference<>() {};

        // 커넥션 풀/JIT 워밍업 구간은 측정에서 제외한다
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            callAndValidate(url, responseType);
        }

        List<Long> elapsedMillis = new ArrayList<>();
        for (int i = 0; i < MEASURE_ROUNDS; i++) {
            long start = System.nanoTime();
            callAndValidate(url, responseType);
            elapsedMillis.add((System.nanoTime() - start) / 1_000_000);
        }

        printResult(elapsedMillis);
    }

    private void callAndValidate(String url, ParameterizedTypeReference<ApiResponse<List<ScheduleResponseDto>>> responseType) {
        ResponseEntity<ApiResponse<List<ScheduleResponseDto>>> response = restTemplate.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, responseType);
        if (!response.getStatusCode().is2xxSuccessful()) log.error("일정 조회 API 호출 실패: status={}, body={}", response.getStatusCode(), response.getBody());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).hasSize(SCHEDULE_COUNT);
    }

    private void printResult(List<Long> elapsedMillis) {
        long total = elapsedMillis.stream().mapToLong(Long::longValue).sum();
        double avg = (double) total / elapsedMillis.size();
        long min = elapsedMillis.stream().mapToLong(Long::longValue).min().orElseThrow();
        long max = elapsedMillis.stream().mapToLong(Long::longValue).max().orElseThrow();

        log.info("========== [캐시 적용 후] 일정 조회 API 성능 측정 결과 ==========");
        log.info("대상 카테고리     : " + CATEGORY_NAMES);
        log.info("대상 데이터 건수 : " + SCHEDULE_COUNT + " (카테고리당 " + SCHEDULE_COUNT_PER_CATEGORY + "건)");
        log.info("측정 횟수        : " + MEASURE_ROUNDS + " (워밍업 " + WARMUP_ROUNDS + "회 별도)");
        log.info("평균 응답 시간(ms) : " + avg);
        log.info("최소 응답 시간(ms) : " + min);
        log.info("최대 응답 시간(ms) : " + max);
        log.info("전체 응답 시간(ms) : " + total);
        log.info("================================================================");
    }
}
