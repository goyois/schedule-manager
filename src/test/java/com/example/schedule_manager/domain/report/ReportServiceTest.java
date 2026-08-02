package com.example.schedule_manager.domain.report;

import com.example.schedule_manager.domain.ai.service.AiRateLimiter;
import com.example.schedule_manager.domain.report.dto.CategoryStatDto;
import com.example.schedule_manager.domain.report.dto.ReportInsightDto;
import com.example.schedule_manager.domain.report.dto.ReportStatsDto;
import com.example.schedule_manager.domain.report.entity.ReportPeriod;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

    @InjectMocks
    private ReportService reportService;

    private User user(Long id) {
        return User.builder().id(id).username("tester").email("tester@example.com").userType(UserType.USER).build();
    }

    private ScheduleResponseDto schedule(Long id, String title, LocalDate date, ScheduleStatus status, String category) {
        return new ScheduleResponseDto(id, title, "내용", date.atTime(10, 0), date.atTime(11, 0), status, "tester", category);
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
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(all);

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
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());

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
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());

        ReportStatsDto stats = reportService.getStats("tester@example.com", ReportPeriod.YEAR, null);

        assertThat(stats.rangeStart()).isEqualTo(LocalDate.now().withDayOfYear(1));
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
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(all);

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
        ScheduleResponseDto similarPast = schedule(2L, "저번 분기 킥오프", monday.minusMonths(3), ScheduleStatus.COMPLETED, "업무");
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of(inRange, similarPast));
        when(scheduleEmbeddingService.findSimilarScheduleIds(eq(1L), anyString(), any(), eq(5)))
                .thenReturn(List.of(2L));

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
        when(scheduleService.getSchedules("tester@example.com", 1L, null))
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
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());

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
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());

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
}
