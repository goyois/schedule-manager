package com.example.schedule_manager.domain.schedule;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// RecurringScheduleService.materializeOccurrences가 규칙 하나당 최대 수십 건을 ScheduleService.
// createSchedules(saveAll)로 한 번에 저장하는데, IDENTITY 채번은 Hibernate가 매 행마다 INSERT를 실행하고
// 그 즉시 생성된 키를 읽어와야 해서 JDBC 배치가 되지 않는다(saveAll이 "한 번에"가 아니라 N번의 개별
// 왕복이 된다) - 이 테스트로 그 저장 시간을 측정한다. SEQUENCE(+ batch_size) 전환 전/후 숫자를
// 이 테스트를 각각 실행해 비교한다(entity/설정을 바꾸는 커밋 전후로 한 번씩 실행).
@Tag("performance")
@Slf4j
@SpringBootTest
class ScheduleBulkInsertPerformanceTest {

    private static final int ROW_COUNT = 1_000;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User user;
    private Category category;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .username("bulk-insert-perf-user")
                .password("password")
                .email("bulk-insert-perf-" + System.nanoTime() + "@test.com")
                .userType(UserType.USER)
                .build());
        category = categoryRepository.save(Category.builder()
                .name("bulk-insert-perf-category-" + System.nanoTime())
                .build());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM schedules WHERE user_id = ?", user.getId());
        userRepository.delete(user);
        categoryRepository.delete(category);
    }

    @Test
    @DisplayName("saveAll로 다건 저장 시 응답 시간 측정 (IDENTITY vs SEQUENCE 배치 여부에 따라 달라짐)")
    void bulkInsertPerformance() {
        LocalDateTime now = LocalDateTime.now();
        List<Schedule> schedules = new ArrayList<>();
        for (int i = 0; i < ROW_COUNT; i++) {
            schedules.add(Schedule.builder()
                    .title("일정-" + i)
                    .content("내용")
                    .startAt(now.plusMinutes(i))
                    .endAt(now.plusMinutes(i + 30))
                    .status(ScheduleStatus.PENDING)
                    .user(user)
                    .category(category)
                    .build());
        }

        long start = System.nanoTime();
        List<Schedule> saved = scheduleRepository.saveAll(schedules);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        List<Long> ids = saved.stream().map(Schedule::getId).collect(Collectors.toList());
        assertThat(ids).doesNotContainNull();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).hasSize(ROW_COUNT);

        log.info("========== [Schedule saveAll] 다건 저장 성능 측정 ==========");
        log.info("저장 건수      : " + ROW_COUNT);
        log.info("소요 시간(ms)  : " + elapsedMillis);
        log.info("건당 평균(ms)  : " + (elapsedMillis / (double) ROW_COUNT));
        log.info("================================================================");
    }
}
