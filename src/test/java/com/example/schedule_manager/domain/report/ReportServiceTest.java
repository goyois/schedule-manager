package com.example.schedule_manager.domain.report;

import com.example.schedule_manager.domain.ai.service.AiRateLimiter;
import com.example.schedule_manager.domain.report.dto.CategorySeriesDto;
import com.example.schedule_manager.domain.report.dto.CategoryStatDto;
import com.example.schedule_manager.domain.report.dto.CategoryTrendDto;
import com.example.schedule_manager.domain.report.dto.ReportInsightCacheDto;
import com.example.schedule_manager.domain.report.dto.ReportInsightDto;
import com.example.schedule_manager.domain.report.dto.ReportStatsDto;
import com.example.schedule_manager.domain.report.entity.ReportInsightSnapshot;
import com.example.schedule_manager.domain.report.entity.ReportPeriod;
import com.example.schedule_manager.domain.report.repository.ReportInsightSnapshotRepository;
import com.example.schedule_manager.domain.report.service.ReportService;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.example.schedule_manager.domain.schedule.service.ScheduleEmbeddingService;
import com.example.schedule_manager.domain.schedule.service.ScheduleService;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequest chatClientRequest;

    @Mock
    private ChatClient.ChatClientRequest.CallResponseSpec callResponseSpec;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiRateLimiter aiRateLimiter;

    @Mock
    private ScheduleEmbeddingService scheduleEmbeddingService;

    @Mock
    private ReportInsightSnapshotRepository reportInsightSnapshotRepository;

    @InjectMocks
    private ReportService reportService;

    // getInsight()를 호출하는 테스트는 전부 saveSnapshot() 경로도 함께 타므로(더 이상 readOnly가 아님),
    // 리포지토리를 건드리지 않는 테스트(getStats 등)에서도 strict stubbing 오류가 안 나도록 기본값을
    // lenient로 깔아둔다(AiServiceTest.stubDefaults와 같은 이유)
    @BeforeEach
    void stubDefaults() {
        lenient().when(reportInsightSnapshotRepository.findByUserIdAndPeriodAndRangeStart(anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(reportInsightSnapshotRepository.save(any(ReportInsightSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private User user(Long id) {
        return User.builder().id(id).username("tester").email("tester@example.com").userType(UserType.USER).build();
    }

    private ScheduleResponseDto schedule(Long id, String title, LocalDate date, ScheduleStatus status, String category) {
        return schedule(id, title, date, status, category, date.atTime(10, 0));
    }

    private ScheduleResponseDto schedule(Long id, String title, LocalDate date, ScheduleStatus status, String category, LocalDateTime updatedAt) {
        return new ScheduleResponseDto(id, title, "내용", date.atTime(10, 0), date.atTime(11, 0), status, "tester", category, updatedAt);
    }

    private void stubChatClient(ReportInsightDto result) {
        when(chatClient.prompt()).thenReturn(chatClientRequest);
        when(chatClientRequest.system(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.user(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(eq(ReportInsightDto.class))).thenReturn(result);
    }

    @Test
    @DisplayName("통계 조회 성공 - 이번 주(월~일) 밖 일정은 제외하고 카테고리별 비율/완료율/직전 주 대비를 계산한다")
    void getStats_week_computesBreakdownAndPreviousComparison() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDate sunday = monday.plusDays(6);
        LocalDate beforeWeek = monday.minusDays(1);
        LocalDate afterWeek = sunday.plusDays(1);
        LocalDate lastWeekWednesday = monday.minusDays(5);

        List<ScheduleResponseDto> all = List.of(
                schedule(1L, "팀 회의", monday, ScheduleStatus.COMPLETED, "업무"),
                schedule(2L, "운동", monday.plusDays(2), ScheduleStatus.COMPLETED, "운동"),
                schedule(3L, "병원", sunday, ScheduleStatus.CANCELLED, "일상"),
                schedule(4L, "이전 주 일정", beforeWeek, ScheduleStatus.PENDING, "업무"),
                schedule(5L, "다음 주 일정", afterWeek, ScheduleStatus.PENDING, "업무"),
                schedule(6L, "지난 주 완료", lastWeekWednesday, ScheduleStatus.COMPLETED, "업무")
        );
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(all);

        ReportStatsDto stats = reportService.getStats("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        assertThat(stats.period()).isEqualTo(ReportPeriod.WEEK);
        assertThat(stats.rangeStart()).isEqualTo(monday);
        assertThat(stats.rangeEnd()).isEqualTo(sunday);
        assertThat(stats.totalCount()).isEqualTo(3); // id 1,2,3만 이번 주 범위
        // 완료율 = COMPLETED(2) / (전체(3) - CANCELLED(1)) = 2/2 = 1.0
        assertThat(stats.completionRate()).isEqualTo(1.0, within(0.0001));

        assertThat(stats.categoryBreakdown())
                .extracting(CategoryStatDto::categoryName, CategoryStatDto::count)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("업무", 1L),
                        org.assertj.core.groups.Tuple.tuple("운동", 1L),
                        org.assertj.core.groups.Tuple.tuple("일상", 1L));

        // 지난 주 범위(monday-7 ~ monday-1)엔 beforeWeek(id 4, PENDING)와 lastWeekWednesday(id 6, COMPLETED)가
        // 함께 걸린다 - 완료율 = COMPLETED(1) / (전체(2) - CANCELLED(0)) = 0.5
        assertThat(stats.previous().totalCount()).isEqualTo(2);
        assertThat(stats.previous().completionRate()).isEqualTo(0.5, within(0.0001));
        assertThat(stats.previous().totalCountDelta()).isEqualTo(1);
        assertThat(stats.previous().completionRateDelta()).isEqualTo(0.5, within(0.0001));
    }

    @Test
    @DisplayName("통계 조회 성공 - 해당 기간에 일정이 없으면 완료율/비율 계산에서 0으로 나누지 않고 0을 반환한다")
    void getStats_emptyPeriod_returnsZeroRatesWithoutDivideByZero() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of());

        ReportStatsDto stats = reportService.getStats("tester@example.com", ReportPeriod.MONTH, LocalDate.now());

        assertThat(stats.totalCount()).isZero();
        assertThat(stats.completionRate()).isZero();
        assertThat(stats.categoryBreakdown()).isEmpty();
        assertThat(stats.previous().totalCount()).isZero();
    }

    @Test
    @DisplayName("통계 조회 성공 - date 파라미터를 생략하면 오늘 기준으로 계산한다")
    void getStats_nullDate_defaultsToToday() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of());

        ReportStatsDto stats = reportService.getStats("tester@example.com", ReportPeriod.YEAR, null);

        assertThat(stats.rangeStart()).isEqualTo(LocalDate.now().withDayOfYear(1));
    }

    @Test
    @DisplayName("통계 조회 성공 - WEEK 카테고리 추이는 일 단위 7구간이며, 일정이 시작 날짜의 구간에만 집계된다")
    void getStats_week_categoryTrendBucketsByStartDate() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDate sunday = monday.plusDays(6);
        List<ScheduleResponseDto> all = List.of(
                schedule(1L, "회의1", monday, ScheduleStatus.COMPLETED, "업무"),
                schedule(2L, "회의2", monday, ScheduleStatus.COMPLETED, "업무"),
                schedule(3L, "보고서", sunday, ScheduleStatus.PENDING, "업무"),
                schedule(4L, "헬스장", monday.plusDays(2), ScheduleStatus.COMPLETED, "운동")
        );
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(all);

        ReportStatsDto stats = reportService.getStats("tester@example.com", ReportPeriod.WEEK, LocalDate.now());
        CategoryTrendDto trend = stats.categoryTrend();

        assertThat(trend.bucketLabels()).hasSize(7);
        assertThat(trend.bucketLabels().get(0)).isEqualTo(monday.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd")));
        assertThat(trend.bucketLabels().get(6)).isEqualTo(sunday.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd")));

        // categoryBreakdown이 건수 내림차순(업무 3건, 운동 1건)이므로 series 순서도 그와 같아야 한다
        assertThat(trend.series()).extracting(CategorySeriesDto::categoryName).containsExactly("업무", "운동");
        List<Long> workCounts = trend.series().get(0).counts();
        assertThat(workCounts.get(0)).isEqualTo(2L); // 월요일에 회의1+회의2
        assertThat(workCounts.get(6)).isEqualTo(1L); // 일요일에 보고서
        assertThat(workCounts.subList(1, 6)).containsOnly(0L);

        List<Long> exerciseCounts = trend.series().get(1).counts();
        assertThat(exerciseCounts.get(2)).isEqualTo(1L); // 월요일+2일 = 수요일 헬스장
        assertThat(exerciseCounts.get(0)).isEqualTo(0L);
    }

    @Test
    @DisplayName("통계 조회 성공 - YEAR 카테고리 추이는 월 단위 12구간으로 집계된다")
    void getStats_year_categoryTrendBucketsByMonth() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        LocalDate yearStart = LocalDate.now().withDayOfYear(1);
        List<ScheduleResponseDto> all = List.of(
                schedule(1L, "신년 계획", yearStart, ScheduleStatus.COMPLETED, "업무"),
                schedule(2L, "여름 휴가", yearStart.plusMonths(5), ScheduleStatus.COMPLETED, "일상")
        );
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(all);

        ReportStatsDto stats = reportService.getStats("tester@example.com", ReportPeriod.YEAR, LocalDate.now());
        CategoryTrendDto trend = stats.categoryTrend();

        assertThat(trend.bucketLabels()).hasSize(12);
        assertThat(trend.bucketLabels().get(0)).isEqualTo("1월");
        assertThat(trend.bucketLabels().get(11)).isEqualTo("12월");

        List<Long> workCounts = trend.series().stream()
                .filter(s -> s.categoryName().equals("업무")).findFirst().orElseThrow().counts();
        assertThat(workCounts.get(0)).isEqualTo(1L);
        assertThat(workCounts.subList(1, 12)).containsOnly(0L);

        List<Long> dailyCounts = trend.series().stream()
                .filter(s -> s.categoryName().equals("일상")).findFirst().orElseThrow().counts();
        assertThat(dailyCounts.get(5)).isEqualTo(1L);
    }

    @Test
    @DisplayName("통계 조회 성공 - 해당 기간에 일정이 없으면 카테고리 추이는 빈 series로, 구간 라벨은 그대로 채워진다")
    void getStats_emptyPeriod_categoryTrendHasLabelsButNoSeries() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of());

        ReportStatsDto stats = reportService.getStats("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        assertThat(stats.categoryTrend().series()).isEmpty();
        assertThat(stats.categoryTrend().bucketLabels()).hasSize(7);
    }

    @Test
    @DisplayName("통계 조회 실패 - 존재하지 않는 유저면 예외가 발생한다")
    void getStats_userNotFound_throws() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getStats("ghost@example.com", ReportPeriod.WEEK, LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("인사이트 생성 성공 - 통계/일정 목록이 프롬프트에 포함되고, 모델 응답을 그대로 반환한다")
    void getInsight_success_buildsPromptFromStatsAndSchedules() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        List<ScheduleResponseDto> all = List.of(schedule(1L, "팀 회의", monday, ScheduleStatus.COMPLETED, "업무"));
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(all);

        stubChatClient(new ReportInsightDto(
                List.of("완료율이 높아요"), List.of("운동이 부족해요"), "평일 오전에 일정이 몰려있어요", "계획적인 편이에요"));

        ReportInsightDto insight = reportService.getInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        assertThat(insight.strengths()).containsExactly("완료율이 높아요");
        assertThat(insight.improvements()).containsExactly("운동이 부족해요");
        assertThat(insight.behaviorPattern()).isEqualTo("평일 오전에 일정이 몰려있어요");
        assertThat(insight.personalityNote()).isEqualTo("계획적인 편이에요");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequest).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("[기간 통계]")
                .contains("[이번 기간 일정 목록]")
                .contains("팀 회의");
    }

    @Test
    @DisplayName("인사이트 생성 성공 - RAG로 찾은 기간 밖 유사 일정은 [참고: 의미상 비슷한 다른 시기의 활동]으로 포함된다")
    void getInsight_includesRagContextWhenFound() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        ScheduleResponseDto inRange = schedule(1L, "팀 회의", monday, ScheduleStatus.COMPLETED, "업무");
        // 저번 분기(3개월 전)는 이번/직전 기간 범위 밖이라 getSchedulesInRange로는 안 잡힌다 -
        // buildRagContext가 매칭된 id(2L)를 getSchedulesByIds로 targeted 조회해서 가져온다
        ScheduleResponseDto similarPast = schedule(2L, "저번 분기 킥오프", monday.minusMonths(3), ScheduleStatus.COMPLETED, "업무");
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of(inRange));
        when(scheduleEmbeddingService.findSimilarScheduleIds(eq(1L), anyString(), any(), eq(5)))
                .thenReturn(List.of(2L));
        when(scheduleService.getSchedulesByIds(anySet())).thenReturn(List.of(similarPast));

        stubChatClient(new ReportInsightDto(List.of(), List.of(), "패턴", "성향"));

        reportService.getInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequest).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("[참고: 의미상 비슷한 다른 시기의 활동]")
                .contains("저번 분기 킥오프");
    }

    @Test
    @DisplayName("인사이트 생성 성공 - RAG로 찾은 게 없으면 참고 섹션 자체가 생략된다")
    void getInsight_omitsRagContextWhenNoneFound() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(schedule(1L, "팀 회의", monday, ScheduleStatus.COMPLETED, "업무")));
        // findSimilarScheduleIds는 스텁하지 않음 - Mockito 기본값(빈 리스트)을 그대로 씀

        stubChatClient(new ReportInsightDto(List.of(), List.of(), "패턴", "성향"));

        reportService.getInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequest).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).doesNotContain("[참고: 의미상 비슷한 다른 시기의 활동]");
    }

    @Test
    @DisplayName("인사이트 생성 성공 - 모델이 null 리스트나 상한 초과 항목을 보내도 방어적으로 정리한다")
    void getInsight_sanitizesNullAndOversizedLists() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of());

        List<String> sevenItems = List.of("1", "2", "3", "4", "5", "6", "7");
        stubChatClient(new ReportInsightDto(sevenItems, null, null, null));

        ReportInsightDto insight = reportService.getInsight("tester@example.com", ReportPeriod.MONTH, LocalDate.now());

        assertThat(insight.strengths()).hasSize(5).containsExactly("1", "2", "3", "4", "5");
        assertThat(insight.improvements()).isEmpty();
        assertThat(insight.behaviorPattern()).isEmpty();
        assertThat(insight.personalityNote()).isEmpty();
    }

    @Test
    @DisplayName("인사이트 생성 실패 - ChatClient 호출이 실패하면 AI_REQUEST_FAILED로 감싼다")
    void getInsight_chatClientThrows_wrapsAsBusinessException() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of());

        when(chatClient.prompt()).thenReturn(chatClientRequest);
        when(chatClientRequest.system(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.user(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.call()).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> reportService.getInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_REQUEST_FAILED);
    }

    @Test
    @DisplayName("인사이트 생성 실패 - 분당 호출 제한에 걸리면 ChatClient를 호출하지 않고 예외를 그대로 전파한다")
    void getInsight_rateLimited_doesNotCallChatClient() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        doThrow(new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED))
                .when(aiRateLimiter).checkLimit(1L, UserType.USER);

        assertThatThrownBy(() -> reportService.getInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_RATE_LIMIT_EXCEEDED);

        verifyNoInteractions(chatClient);
    }

    @Test
    @DisplayName("인사이트 생성 실패 - 존재하지 않는 유저면 예외가 발생한다")
    void getInsight_userNotFound_throws() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getInsight("ghost@example.com", ReportPeriod.WEEK, LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("인사이트 생성 성공 - 결과를 스냅샷으로 저장한다(이번 기간 일정 건수 + 그중 가장 최근 수정 시각 포함)")
    void getInsight_success_savesSnapshotWithScheduleFingerprint() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDateTime earlierUpdatedAt = monday.atTime(9, 0);
        LocalDateTime laterUpdatedAt = monday.plusDays(1).atTime(15, 30);
        List<ScheduleResponseDto> all = List.of(
                schedule(1L, "회의", monday, ScheduleStatus.COMPLETED, "업무", earlierUpdatedAt),
                schedule(2L, "운동", monday.plusDays(1), ScheduleStatus.COMPLETED, "운동", laterUpdatedAt));
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(all);
        stubChatClient(new ReportInsightDto(List.of("잘함"), List.of("아쉬움"), "패턴", "성향"));

        reportService.getInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        ArgumentCaptor<ReportInsightSnapshot> captor = ArgumentCaptor.forClass(ReportInsightSnapshot.class);
        verify(reportInsightSnapshotRepository).save(captor.capture());
        ReportInsightSnapshot saved = captor.getValue();
        assertThat(saved.getScheduleCount()).isEqualTo(2);
        assertThat(saved.getLatestScheduleUpdatedAt()).isEqualTo(laterUpdatedAt); // 둘 중 더 최근 것
        assertThat(saved.getBehaviorPattern()).isEqualTo("패턴");
        assertThat(saved.getPersonalityNote()).isEqualTo("성향");
    }

    @Test
    @DisplayName("캐시된 인사이트 조회 - 저장된 스냅샷이 없으면 insight는 null, stale은 false다(AI를 부르지 않는다)")
    void getCachedInsight_noSnapshot_returnsNullInsightNotStale() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        ReportInsightCacheDto result = reportService.getCachedInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        assertThat(result.insight()).isNull();
        assertThat(result.stale()).isFalse();
        verifyNoInteractions(chatClient);
    }

    @Test
    @DisplayName("캐시된 인사이트 조회 - 생성 시점 이후 이 기간 일정이 그대로면 stale이 false이고 저장된 결과를 그대로 반환한다")
    void getCachedInsight_unchanged_returnsSavedInsightNotStale() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        List<ScheduleResponseDto> all = List.of(
                schedule(1L, "회의", monday, ScheduleStatus.COMPLETED, "업무", monday.atTime(9, 0)));
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(all);
        stubChatClient(new ReportInsightDto(List.of("잘함1", "잘함2"), List.of("아쉬움"), "패턴", "성향"));
        reportService.getInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        ArgumentCaptor<ReportInsightSnapshot> captor = ArgumentCaptor.forClass(ReportInsightSnapshot.class);
        verify(reportInsightSnapshotRepository).save(captor.capture());
        when(reportInsightSnapshotRepository.findByUserIdAndPeriodAndRangeStart(eq(1L), eq(ReportPeriod.WEEK), any()))
                .thenReturn(Optional.of(captor.getValue()));

        ReportInsightCacheDto cached = reportService.getCachedInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        assertThat(cached.stale()).isFalse();
        assertThat(cached.insight().strengths()).containsExactly("잘함1", "잘함2");
        assertThat(cached.insight().improvements()).containsExactly("아쉬움");
        assertThat(cached.insight().behaviorPattern()).isEqualTo("패턴");
        assertThat(cached.insight().personalityNote()).isEqualTo("성향");
    }

    @Test
    @DisplayName("캐시된 인사이트 조회 - 생성 이후 이 기간 일정 건수가 바뀌면(추가/삭제) stale이 true다")
    void getCachedInsight_scheduleCountChanged_returnsStale() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        ScheduleResponseDto original = schedule(1L, "회의", monday, ScheduleStatus.COMPLETED, "업무", monday.atTime(9, 0));
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of(original));
        stubChatClient(new ReportInsightDto(List.of("잘함"), List.of(), "패턴", "성향"));
        reportService.getInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        ArgumentCaptor<ReportInsightSnapshot> captor = ArgumentCaptor.forClass(ReportInsightSnapshot.class);
        verify(reportInsightSnapshotRepository).save(captor.capture());
        when(reportInsightSnapshotRepository.findByUserIdAndPeriodAndRangeStart(eq(1L), eq(ReportPeriod.WEEK), any()))
                .thenReturn(Optional.of(captor.getValue()));

        // 그 사이 같은 주에 일정이 하나 더 생김
        List<ScheduleResponseDto> changed = List.of(
                original,
                schedule(2L, "새 일정", monday.plusDays(1), ScheduleStatus.PENDING, "일상", monday.plusDays(1).atTime(10, 0)));
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(changed);

        ReportInsightCacheDto cached = reportService.getCachedInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        assertThat(cached.stale()).isTrue();
        assertThat(cached.insight()).isNotNull(); // 오래된 결과라도 일단 그대로 보여준다(재생성은 프론트의 선택)
    }

    @Test
    @DisplayName("캐시된 인사이트 조회 - 일정 건수는 그대로여도 그중 하나가 수정되면(최신 수정 시각 변경) stale이 true다")
    void getCachedInsight_scheduleContentUpdated_returnsStale() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        ScheduleResponseDto original = schedule(1L, "회의", monday, ScheduleStatus.PENDING, "업무", monday.atTime(9, 0));
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of(original));
        stubChatClient(new ReportInsightDto(List.of("잘함"), List.of(), "패턴", "성향"));
        reportService.getInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        ArgumentCaptor<ReportInsightSnapshot> captor = ArgumentCaptor.forClass(ReportInsightSnapshot.class);
        verify(reportInsightSnapshotRepository).save(captor.capture());
        when(reportInsightSnapshotRepository.findByUserIdAndPeriodAndRangeStart(eq(1L), eq(ReportPeriod.WEEK), any()))
                .thenReturn(Optional.of(captor.getValue()));

        // 같은 일정(건수 그대로 1건)이지만 상태가 바뀌어 updatedAt만 갱신됨
        ScheduleResponseDto updated = schedule(1L, "회의", monday, ScheduleStatus.COMPLETED, "업무", monday.atTime(18, 0));
        when(scheduleService.getSchedulesInRange(eq("tester@example.com"), eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of(updated));

        ReportInsightCacheDto cached = reportService.getCachedInsight("tester@example.com", ReportPeriod.WEEK, LocalDate.now());

        assertThat(cached.stale()).isTrue();
    }
}
