package com.example.schedule_manager.domain.user;

import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// email 컬럼에 인덱스가 없어 JwtAuthenticationFilter(인증된 모든 요청마다) + 각 서비스의
// requesterEmail -> User resolve(요청당 최소 1번 더)가 users 테이블 전체를 순차 스캔하는지 확인한다.
// ScheduleIndexPerformanceTest와 동일한 방식(JdbcTemplate 배치 insert로 대량 시딩, EXPLAIN 로그 +
// 반복 호출 응답시간 측정)을 따른다.
@Tag("performance")
@Slf4j
@SpringBootTest
class UserEmailIndexPerformanceTest {

    private static final int USER_COUNT = 50_000;

    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURE_ROUNDS = 20;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String targetEmail;
    private String emailPrefix;

    @BeforeEach
    void setUp() {
        emailPrefix = "email-idx-perf-" + System.nanoTime();
        targetEmail = emailPrefix + "-" + (USER_COUNT - 1) + "@test.com";

        LocalDateTime now = LocalDateTime.now();
        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 0; i < USER_COUNT; i++) {
            batchArgs.add(new Object[]{
                    emailPrefix + "-username-" + i,
                    "password",
                    emailPrefix + "-" + i + "@test.com",
                    "USER",
                    "LOCAL",
                    false,
                    false,
                    now,
                    now
            });
        }

        jdbcTemplate.batchUpdate(
                "INSERT INTO users (username, password, email, user_type, auth_provider, auto_status_mode, " +
                        "ai_auto_register_enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batchArgs);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", emailPrefix + "%");
    }

    @Test
    @DisplayName("email 인덱스 적용 전/후 대량 데이터에서 findByEmail 조회 성능 측정")
    void queryPerformanceByEmail() {
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

    // JwtAuthenticationFilter/각 서비스의 findUserByEmail이 실제로 실행하는 쿼리와 동일한 형태
    private void logExplainPlan() {
        String explainSql = "EXPLAIN SELECT * FROM users WHERE email = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(explainSql, targetEmail);
        log.info("========== EXPLAIN (email={}) ==========", targetEmail);
        for (Map<String, Object> row : rows) {
            log.info("{}", row);
        }
        log.info("================================================================");
    }

    private void callAndValidate() {
        Optional<User> result = userRepository.findByEmail(targetEmail);
        assertThat(result).isPresent();
    }

    private void printResult(List<Long> elapsedMillis) {
        long total = elapsedMillis.stream().mapToLong(Long::longValue).sum();
        double avg = (double) total / elapsedMillis.size();
        long min = elapsedMillis.stream().mapToLong(Long::longValue).min().orElseThrow();
        long max = elapsedMillis.stream().mapToLong(Long::longValue).max().orElseThrow();

        log.info("========== [email 인덱스] DB 조회 성능 측정 결과 ==========");
        log.info("시딩된 users 행 수 : " + USER_COUNT);
        log.info("측정 횟수         : " + MEASURE_ROUNDS + " (워밍업 " + WARMUP_ROUNDS + "회 별도)");
        log.info("평균 응답 시간(ms) : " + avg);
        log.info("최소 응답 시간(ms) : " + min);
        log.info("최대 응답 시간(ms) : " + max);
        log.info("전체 응답 시간(ms) : " + total);
        log.info("================================================================");
    }
}
