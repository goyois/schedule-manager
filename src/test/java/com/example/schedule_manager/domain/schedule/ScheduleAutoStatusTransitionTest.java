package com.example.schedule_manager.domain.schedule;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.schedule.entity.Schedule;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.example.schedule_manager.domain.schedule.repository.ScheduleRepository;
import com.example.schedule_manager.domain.schedule.service.ScheduleService;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScheduleService.autoTransitionScheduleStatuses()(@Scheduled) 가 User.autoStatusMode 가 켜진
 * 유저의 일정만 시작/종료 시각에 맞춰 서버에서 직접 상태를 바꾸는지 검증한다.
 * 실제 타이머(fixedRate=60_000)가 자연히 돌기를 기다리지 않고 메서드를 직접 호출해 확정적으로 검증한다.
 */
@SpringBootTest
class ScheduleAutoStatusTransitionTest {

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private Category category;

    @BeforeEach
    void setUp() {
        category = categoryRepository.save(Category.builder()
                .name("auto-status-test-category-" + System.nanoTime())
                .build());
    }

    @AfterEach
    void tearDown() {
        categoryRepository.delete(category);
    }

    @Test
    @DisplayName("자동 모드 유저: 시작 시각이 지난 대기 일정(종료 시각 있음)은 진행중으로 바뀐다")
    void autoMode_startPassed_withEndAt_becomesInProgress() {
        User user = createUser(true);
        Schedule schedule = saveSchedule(user, ScheduleStatus.PENDING,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        scheduleService.autoTransitionScheduleStatuses();

        assertThat(scheduleRepository.findById(schedule.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.IN_PROGRESS);

        cleanup(user);
    }

    @Test
    @DisplayName("자동 모드 유저: 시작 시각이 지난 알림형(종료 시각 없음) 일정은 곧바로 완료로 바뀐다")
    void autoMode_startPassed_noEndAt_becomesCompleted() {
        User user = createUser(true);
        Schedule schedule = saveSchedule(user, ScheduleStatus.PENDING,
                LocalDateTime.now().minusMinutes(1), null);

        scheduleService.autoTransitionScheduleStatuses();

        assertThat(scheduleRepository.findById(schedule.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.COMPLETED);

        cleanup(user);
    }

    @Test
    @DisplayName("자동 모드 유저: 종료 시각이 지난 진행중 일정은 완료로 바뀐다")
    void autoMode_endPassed_inProgress_becomesCompleted() {
        User user = createUser(true);
        Schedule schedule = saveSchedule(user, ScheduleStatus.IN_PROGRESS,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().minusMinutes(1));

        scheduleService.autoTransitionScheduleStatuses();

        assertThat(scheduleRepository.findById(schedule.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.COMPLETED);

        cleanup(user);
    }

    @Test
    @DisplayName("수동 모드 유저: 시작/종료 시각이 지나도 상태를 그대로 둔다")
    void manualMode_timesPassed_statusUnchanged() {
        User user = createUser(false);
        Schedule schedule = saveSchedule(user, ScheduleStatus.PENDING,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().minusMinutes(1));

        scheduleService.autoTransitionScheduleStatuses();

        assertThat(scheduleRepository.findById(schedule.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.PENDING);

        cleanup(user);
    }

    @Test
    @DisplayName("자동 모드 유저라도 취소된 일정은 시간이 지나도 건드리지 않는다")
    void autoMode_cancelledSchedule_untouched() {
        User user = createUser(true);
        Schedule schedule = saveSchedule(user, ScheduleStatus.CANCELLED,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().minusMinutes(1));

        scheduleService.autoTransitionScheduleStatuses();

        assertThat(scheduleRepository.findById(schedule.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.CANCELLED);

        cleanup(user);
    }

    @Test
    @DisplayName("자동 모드 유저라도 아직 시작/종료 시각이 안 지난 일정은 건드리지 않는다")
    void autoMode_futureSchedule_untouched() {
        User user = createUser(true);
        Schedule schedule = saveSchedule(user, ScheduleStatus.PENDING,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));

        scheduleService.autoTransitionScheduleStatuses();

        assertThat(scheduleRepository.findById(schedule.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.PENDING);

        cleanup(user);
    }

    private User createUser(boolean autoStatusMode) {
        return userRepository.save(User.builder()
                .username("auto-status-test-user")
                .password("test-password")
                .email("auto-status-" + System.nanoTime() + "@test.com")
                .userType(UserType.USER)
                .autoStatusMode(autoStatusMode)
                .build());
    }

    private Schedule saveSchedule(User owner, ScheduleStatus status, LocalDateTime startAt, LocalDateTime endAt) {
        return scheduleRepository.save(Schedule.builder()
                .title("auto-status-test-schedule")
                .content("content")
                .startAt(startAt)
                .endAt(endAt)
                .status(status)
                .user(owner)
                .category(category)
                .build());
    }

    private void cleanup(User user) {
        scheduleRepository.deleteAll(scheduleRepository.findAllByUserId(user.getId()));
        userRepository.delete(user);
    }
}
