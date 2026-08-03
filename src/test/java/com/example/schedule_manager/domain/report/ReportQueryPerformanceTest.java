package com.example.schedule_manager.domain.report;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// ReportService.getStats/getInsight가 "이번 주 리포트"를 계산할 때도 유저 전체 이력을 무제한으로
// 가져온 뒤(구 ScheduleService.getSchedules(...,null) 경유) 메모리에서 걸러내던 것을,
// searchSchedulesInRange로 DB 단에서 [prevRange, range] 범위만 좁혀 가져오도록 바꾼 효과를 측정한다.
// AS-IS는 searchSchedules(userId, null)(범위 없음 - 개선 전 getStats가 실제로 호출하던 것과 동일한
// 쿼리 형태), TO-BE는 searchSchedulesInRange(userId, null, rangeStart, rangeEnd)로 재현한다.
@Tag("performance")
@Slf4j
@SpringBootTest
class ReportQueryPerformanceTest {

    // 유저가 3년 넘게 매일 일정을 기록해온 상황을 재현 - 오늘 기준 WEEK 리포트가 필요로 하는 건
    // 이 중 극히 일부(이번 주+지난 주, 최대 14일치)뿐이다
    private static final int TOTAL_DAYS = 3 * 365;

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
    private Category category;
    private LocalDateTime rangeStart;
    private LocalDateTime rangeEnd;

    @BeforeEach
    void setUp() {
        targetUser = userRepository.save(User.builder()
                .username("report-perf-user")
                .password("password")
                .email("report-perf-" + System.nanoTime() + "@test.com")
                .userType(UserType.USER)
                .build());
        category = categoryRepository.save(Category.builder()
                .name("report-perf-category-" + System.nanoTime())
                .build());

        LocalDateTime today = LocalDateTime.now().toLocalDate().atStartOfDay();
        // WEEK 리포트가 실제로 필요로 하는 [지난 주 시작, 이번 주 끝] 범위 - resolveRange/previousRange와
        // 동일한 폭(최대 14일)을 넉넉히 잡는다
        rangeStart = today.minusDays(14);
        rangeEnd = today.plusDays(7);

        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 0; i < TOTAL_DAYS; i++) {
            LocalDateTime start = today.plusDays(i - TOTAL_DAYS / 2);
            batchArgs.add(new Object[]{
                    "일정-" + i, "내용", start, start.plusHours(1), "PENDING",
                    targetUser.getId(), category.getId(), start, start
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO schedules (title, content, start_at, end_at, status, user_id, category_id, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batchArgs);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM schedules WHERE user_id = ?", targetUser.getId());
        userRepository.delete(targetUser);
        categoryRepository.delete(category);
    }

    @Test
    @DisplayName("AS-IS(전체 이력 무제한 조회) vs TO-BE(기간 bounded 조회) - 응답 시간 및 반환 건수 비교")
    void asIsVsToBe() {
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            scheduleRepository.searchSchedules(targetUser.getId(), null);
            scheduleRepository.searchSchedulesInRange(targetUser.getId(), null, rangeStart, rangeEnd);
        }

        List<Long> asIsMillis = new ArrayList<>();
        int asIsRows = 0;
        for (int i = 0; i < MEASURE_ROUNDS; i++) {
            long start = System.nanoTime();
            List<ScheduleResponseDto> result = scheduleRepository.searchSchedules(targetUser.getId(), null);
            asIsMillis.add((System.nanoTime() - start) / 1_000_000);
            asIsRows = result.size();
        }

        List<Long> toBeMillis = new ArrayList<>();
        int toBeRows = 0;
        for (int i = 0; i < MEASURE_ROUNDS; i++) {
            long start = System.nanoTime();
            List<ScheduleResponseDto> result = scheduleRepository.searchSchedulesInRange(targetUser.getId(), null, rangeStart, rangeEnd);
            toBeMillis.add((System.nanoTime() - start) / 1_000_000);
            toBeRows = result.size();
        }

        assertThat(asIsRows).isEqualTo(TOTAL_DAYS);
        assertThat(toBeRows).isEqualTo(21); // rangeStart~rangeEnd 21일치(오늘 기준 -14 ~ +7, 하루 1건)

        log.info("========== [ReportService] 전체 이력 조회 vs 기간 bounded 조회 ==========");
        log.info("시딩된 전체 일정 건수 : " + TOTAL_DAYS);
        log.info("AS-IS  반환 건수     : " + asIsRows + " / 평균 응답시간(ms): " + avg(asIsMillis));
        log.info("TO-BE  반환 건수     : " + toBeRows + " / 평균 응답시간(ms): " + avg(toBeMillis));
        log.info("================================================================");
    }

    private double avg(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElseThrow();
    }
}
