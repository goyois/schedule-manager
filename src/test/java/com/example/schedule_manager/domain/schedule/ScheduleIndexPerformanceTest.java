package com.example.schedule_manager.domain.schedule;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.repository.ScheduleRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * (user_id, category_id) 복합 인덱스 적용 전/후 DB 조회 성능을 비교한다. 단일 컬럼 인덱스만으로는
 * 좁혀지지 않는 현실적인 컨텐션을 재현하기 위해:
 *  - targetUser 는 여러 카테고리(CATEGORY_COUNT)에 걸쳐 일정을 갖고 있어 user_id 인덱스만으로는
 *    카테고리 필터링이 안 되고,
 *  - targetCategory(카테고리[0]) 는 다른 여러 유저(NOISE_USER_COUNT)도 공유하고 있어 category_id
 *    인덱스만으로는 유저 필터링이 안 된다.
 * 캐시(ScheduleCacheQueryService)를 거치지 않고 ScheduleRepositoryImpl.searchSchedules() 를 직접 호출해
 * 순수 DB 쿼리 비용만 측정한다. 대량 insert 는 IDENTITY 채번이라 JPA saveAll 이 배치되지 않으므로
 * JdbcTemplate 배치 insert 로 시딩한다.
 */
@Slf4j
@SpringBootTest
class ScheduleIndexPerformanceTest {

    private static final int CATEGORY_COUNT = 5;
    private static final int TARGET_ROWS_PER_CATEGORY = 2_000; // targetUser 가 카테고리마다 갖는 일정 수
    private static final int NOISE_USER_COUNT = 15;             // targetCategory 를 같이 쓰는 다른 유저 수
    private static final int NOISE_ROWS_PER_USER = 3_000;       // 그 다른 유저들이 targetCategory 에 갖는 일정 수

    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURE_ROUNDS = 20;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User targetUser;
    private List<User> noiseUsers;
    private List<Category> testCategories;
    private Long targetCategoryId;

    @BeforeEach
    void setUp() {
        targetUser = createUser("idx-perf-target-user");

        noiseUsers = new ArrayList<>();
        for (int i = 0; i < NOISE_USER_COUNT; i++) {
            noiseUsers.add(createUser("idx-perf-noise-user-" + i));
        }

        testCategories = new ArrayList<>();
        for (int i = 0; i < CATEGORY_COUNT; i++) {
            testCategories.add(categoryRepository.save(Category.builder()
                    .name("idx-perf-category-" + i + "-" + System.nanoTime())
                    .build()));
        }
        targetCategoryId = testCategories.get(0).getId();

        LocalDateTime now = LocalDateTime.now();
        List<Object[]> batchArgs = new ArrayList<>();

        // targetUser: 모든 카테고리에 걸쳐 일정을 가져, user_id 인덱스 단독으로는 카테고리가 안 좁혀진다
        for (Category category : testCategories) {
            for (int i = 0; i < TARGET_ROWS_PER_CATEGORY; i++) {
                batchArgs.add(scheduleRow(category, i, targetUser.getId(), now));
            }
        }

        // noiseUsers: targetCategory 에만 일정을 넣어, category_id 인덱스 단독으로는 유저가 안 좁혀진다
        Category targetCategory = testCategories.get(0);
        for (User noiseUser : noiseUsers) {
            for (int i = 0; i < NOISE_ROWS_PER_USER; i++) {
                batchArgs.add(scheduleRow(targetCategory, i, noiseUser.getId(), now));
            }
        }

        jdbcTemplate.batchUpdate(
                "INSERT INTO schedules (title, content, start_at, end_at, status, user_id, category_id, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batchArgs);
    }

    private User createUser(String prefix) {
        return userRepository.save(User.builder()
                .username(prefix)
                .password(prefix + "-password")
                .email(prefix + "-" + System.nanoTime() + "@test.com")
                .userType(UserType.USER)
                .build());
    }

    private Object[] scheduleRow(Category category, int i, Long userId, LocalDateTime now) {
        return new Object[]{
                category.getName() + "-title-" + i,
                category.getName() + "-content-" + i,
                now.plusMinutes(i),
                now.plusMinutes(i + 30),
                "PENDING",
                userId,
                category.getId(),
                now,
                now
        };
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM schedules WHERE user_id = ?", targetUser.getId());
        userRepository.delete(targetUser);
        for (User noiseUser : noiseUsers) {
            jdbcTemplate.update("DELETE FROM schedules WHERE user_id = ?", noiseUser.getId());
            userRepository.delete(noiseUser);
        }
        categoryRepository.deleteAll(testCategories);
    }

    @Test
    @DisplayName("(user_id, category_id) 복합 인덱스 적용 전/후 대량 데이터 조회 성능 측정")
    void queryPerformanceByUserAndCategory() {
        logExplainPlan();

        // 커넥션 풀/JIT 워밍업 구간은 측정에서 제외한다
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            callAndValidate();
        }

        List<Long> elapsedMillis = new ArrayList<>();
        for (int i = 0; i < MEASURE_ROUNDS; i++) {
            long start = System.nanoTime();
            callAndValidate();
            elapsedMillis.add((System.nanoTime() - start) / 1_000_000);
        }

        printResult(elapsedMillis);
    }

    // searchSchedules() 가 실제로 실행하는 쿼리와 동일한 접근 패턴(user_id + category_id 필터, users/categories join)에
    // 대해 EXPLAIN 을 떠서 실행계획을 로그로 남긴다 — 인덱스 적용 전후 비교의 근거 자료
    private void logExplainPlan() {
        String explainSql = "EXPLAIN SELECT s.schedule_id, s.title FROM schedules s " +
                "JOIN users u ON s.user_id = u.user_id " +
                "JOIN categories c ON s.category_id = c.category_id " +
                "WHERE s.user_id = ? AND s.category_id = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(explainSql, targetUser.getId(), targetCategoryId);
        log.info("========== EXPLAIN (user_id={}, category_id={}) ==========", targetUser.getId(), targetCategoryId);
        for (Map<String, Object> row : rows) {
            log.info("{}", row);
        }
        log.info("================================================================");
    }

    private void callAndValidate() {
        List<ScheduleResponseDto> result = scheduleRepository.searchSchedules(targetUser.getId(), targetCategoryId);
        assertThat(result).hasSize(TARGET_ROWS_PER_CATEGORY);
    }

    private void printResult(List<Long> elapsedMillis) {
        long total = elapsedMillis.stream().mapToLong(Long::longValue).sum();
        double avg = (double) total / elapsedMillis.size();
        long min = elapsedMillis.stream().mapToLong(Long::longValue).min().orElseThrow();
        long max = elapsedMillis.stream().mapToLong(Long::longValue).max().orElseThrow();
        long categoryTotalRows = (long) TARGET_ROWS_PER_CATEGORY + (long) NOISE_USER_COUNT * NOISE_ROWS_PER_USER;
        long userTotalRows = (long) CATEGORY_COUNT * TARGET_ROWS_PER_CATEGORY;

        log.info("========== [(user_id, category_id) 인덱스] DB 조회 성능 측정 결과 ==========");
        log.info("targetCategory 전체 건수(다른 유저 포함) : " + categoryTotalRows);
        log.info("targetUser 전체 건수(다른 카테고리 포함)  : " + userTotalRows);
        log.info("실제 정답 건수(user_id AND category_id)   : " + TARGET_ROWS_PER_CATEGORY);
        log.info("측정 횟수        : " + MEASURE_ROUNDS + " (워밍업 " + WARMUP_ROUNDS + "회 별도)");
        log.info("평균 응답 시간(ms) : " + avg);
        log.info("최소 응답 시간(ms) : " + min);
        log.info("최대 응답 시간(ms) : " + max);
        log.info("전체 응답 시간(ms) : " + total);
        log.info("================================================================");
    }
}
