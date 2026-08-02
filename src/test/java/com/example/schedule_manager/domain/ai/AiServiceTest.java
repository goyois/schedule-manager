package com.example.schedule_manager.domain.ai;

import com.example.schedule_manager.domain.ai.dto.AiChatExchangeDto;
import com.example.schedule_manager.domain.ai.dto.AiChatMessageDto;
import com.example.schedule_manager.domain.ai.dto.AiScheduleSuggestion;
import com.example.schedule_manager.domain.ai.entity.AiChatMessage;
import com.example.schedule_manager.domain.ai.entity.AiChatRole;
import com.example.schedule_manager.domain.ai.entity.AiChatSuggestedSchedule;
import com.example.schedule_manager.domain.ai.entity.AiResponseCategory;
import com.example.schedule_manager.domain.ai.repository.AiChatMessageRepository;
import com.example.schedule_manager.domain.ai.repository.AiChatSuggestedScheduleRepository;
import com.example.schedule_manager.domain.ai.service.AiRateLimiter;
import com.example.schedule_manager.domain.ai.service.AiService;
import com.example.schedule_manager.domain.category.dto.CategoryResponseDto;
import com.example.schedule_manager.domain.category.service.CategoryService;
import com.example.schedule_manager.domain.mandalart.entity.MandalartBoard;
import com.example.schedule_manager.domain.mandalart.entity.MandalartCell;
import com.example.schedule_manager.domain.mandalart.repository.MandalartBoardRepository;
import com.example.schedule_manager.domain.mandalart.repository.MandalartCellRepository;
import com.example.schedule_manager.domain.mandalart.service.MandalartAiService;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequest chatClientRequest;

    @Mock
    private ChatClient.ChatClientRequest.CallResponseSpec callResponseSpec;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    @Mock
    private AiChatSuggestedScheduleRepository aiChatSuggestedScheduleRepository;

    @Mock
    private AiRateLimiter aiRateLimiter;

    @Mock
    private MandalartBoardRepository mandalartBoardRepository;

    @Mock
    private MandalartCellRepository mandalartCellRepository;

    @Mock
    private MandalartAiService mandalartAiService;

    @Mock
    private ScheduleEmbeddingService scheduleEmbeddingService;

    @InjectMocks
    private AiService aiService;

    // sendMessage()는 매번 [만다라트 보드] 컨텍스트를 위해 이 목록을 조회한다 - 만다라트와 무관한
    // 기존 테스트들이 전부 이 스텁을 반복할 필요가 없도록 "보드 없음"을 기본값으로 깔아둔다.
    // lenient인 이유: sendMessage()를 호출하지 않는 테스트(getConversation 등)에서는 이 스텁이
    // 실제로 쓰이지 않는데, MockitoExtension의 기본 strict stubbing이 "쓰이지 않은 스텁"을 오류로
    // 보기 때문
    @BeforeEach
    void stubDefaults() {
        lenient().when(mandalartBoardRepository.findByUserIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of());
        // 실제 DB의 IDENTITY 채번을 흉내내 저장할 때마다 새 id를 붙여 돌려준다 - markItemRegistered 등이
        // 항목 id로 찾아야 하는 흐름을 테스트하려면 저장 직후에도 id가 있어야 한다
        java.util.concurrent.atomic.AtomicLong nextItemId = new java.util.concurrent.atomic.AtomicLong(1);
        lenient().when(aiChatSuggestedScheduleRepository.save(any(AiChatSuggestedSchedule.class)))
                .thenAnswer(invocation -> {
                    AiChatSuggestedSchedule arg = invocation.getArgument(0);
                    return arg.getId() != null ? arg : AiChatSuggestedSchedule.builder()
                            .id(nextItemId.getAndIncrement())
                            .message(arg.getMessage())
                            .itemOrder(arg.getItemOrder())
                            .title(arg.getTitle())
                            .content(arg.getContent())
                            .startAt(arg.getStartAt())
                            .endAt(arg.getEndAt())
                            .categoryId(arg.getCategoryId())
                            .registeredScheduleId(arg.getRegisteredScheduleId())
                            .build();
                });
    }

    private User user(Long id) {
        return User.builder().id(id).username("tester").email("tester@example.com").userType(UserType.USER).build();
    }

    private AiChatMessage savedMessage(Long id, User user, AiChatRole role) {
        return AiChatMessage.builder().id(id).user(user).role(role).messageText("x").build();
    }

    private void stubChatClient(AiScheduleSuggestion suggestion) {
        when(chatClient.prompt()).thenReturn(chatClientRequest);
        when(chatClientRequest.system(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.messages(org.mockito.ArgumentMatchers.<List<Message>>any())).thenReturn(chatClientRequest);
        when(chatClientRequest.user(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(eq(AiScheduleSuggestion.class))).thenReturn(suggestion);
    }

    private void stubEmptyHistoryAndSave(User requester) {
        when(aiChatMessageRepository.findByUserIdOrderByCreatedAtDesc(eq(requester.getId()), any(Pageable.class)))
                .thenReturn(List.of());
        when(aiChatMessageRepository.save(any(AiChatMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("메시지 전송 성공 - 최근 2주~향후 2주 밖의 일정은 프롬프트 컨텍스트에서 제외되고, 구조화된 필드가 그대로 저장/응답된다")
    void sendMessage_success_buildsContextFromRecentSchedulesOnly() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);

        LocalDateTime now = LocalDateTime.now();
        ScheduleResponseDto inWindow = new ScheduleResponseDto(
                1L, "팀 회의", "주간 회의", now.plusDays(1), now.plusDays(1).plusHours(1),
                ScheduleStatus.PENDING, "tester", "업무", now);
        ScheduleResponseDto outOfWindow = new ScheduleResponseDto(
                2L, "먼 미래 일정", "내용", now.plusWeeks(5), now.plusWeeks(5).plusHours(1),
                ScheduleStatus.PENDING, "tester", "업무", now);
        when(scheduleService.getSchedules("tester@example.com", 1L, null))
                .thenReturn(List.of(inWindow, outOfWindow));
        when(categoryService.getCategories("tester@example.com"))
                .thenReturn(List.of(new CategoryResponseDto(10L, "운동")));

        // SCHEDULE_UPDATE와 마찬가지로 모델이 계산한 날짜를 그대로 신뢰한다(오늘로 바꿔치기하지 않는다)
        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_RECOMMENDATION, null, null,
                null, null, null, null, null,
                List.of(new AiScheduleSuggestion.ScheduleItemSuggestion(
                        "저녁 러닝", "30분 조깅", "2026-07-29T19:00:00", "2026-07-29T19:30:00", 10L)),
                "추천 이유"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "이번 주 운동 일정 추천해줘");

        assertThat(exchange.userMessage().role()).isEqualTo("USER");
        assertThat(exchange.userMessage().message()).isEqualTo("이번 주 운동 일정 추천해줘");
        assertThat(exchange.assistantMessage().role()).isEqualTo("ASSISTANT");
        assertThat(exchange.assistantMessage().category()).isEqualTo("SCHEDULE_RECOMMENDATION");
        assertThat(exchange.assistantMessage().message()).isEqualTo("추천 이유");
        // 단일 필드(suggestedTitle 등)는 더 이상 SCHEDULE_RECOMMENDATION에 쓰이지 않고 suggestedItems로 대체됐다
        assertThat(exchange.assistantMessage().suggestedTitle()).isNull();
        assertThat(exchange.assistantMessage().suggestedItems()).hasSize(1);
        AiChatMessageDto.SuggestedScheduleItemDto item = exchange.assistantMessage().suggestedItems().get(0);
        assertThat(item.title()).isEqualTo("저녁 러닝");
        assertThat(item.content()).isEqualTo("30분 조깅");
        assertThat(item.startAt()).isEqualTo(LocalDateTime.of(2026, 7, 29, 19, 0));
        assertThat(item.endAt()).isEqualTo(LocalDateTime.of(2026, 7, 29, 19, 30));
        assertThat(item.categoryId()).isEqualTo(10L);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequest).user(userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue())
                .contains("팀 회의")
                .doesNotContain("먼 미래 일정")
                .contains("운동");
    }

    @Test
    @DisplayName("메시지 전송 성공 - 모델이 상대적 날짜(오늘/내일 등)를 스스로 지어내지 않도록 현재 날짜/시각을 프롬프트에 함께 실어 보낸다")
    void sendMessage_success_includesCurrentDateTimeInPrompt() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.GENERAL, null, null,
                null, null, null, null, null, null, "답변"));

        aiService.sendMessage("tester@example.com", "오늘 뭐 해야 돼?");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequest).user(userPromptCaptor.capture());
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        assertThat(userPromptCaptor.getValue())
                .contains("[현재 날짜/시각]")
                .contains(today);
    }

    @Test
    @DisplayName("메시지 전송 성공 - 이전 대화가 있으면 요약된 형태로 함께 실어 보낸다")
    void sendMessage_success_includesRecentHistory() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());

        AiChatMessage priorUser = AiChatMessage.builder().id(1L).user(requester).role(AiChatRole.USER)
                .messageText("어제 뭐 추천했었지?").build();
        AiChatMessage priorAssistant = AiChatMessage.builder().id(2L).user(requester).role(AiChatRole.ASSISTANT)
                .messageText("가벼운 산책을 추천드려요").suggestedTitle("저녁 산책")
                .suggestedStartAt(LocalDateTime.of(2026, 7, 28, 19, 0)).build();
        // 서비스가 Collections.reverse()로 뒤집으므로 List.of()의 불변 리스트가 아닌 가변 리스트를 넘겨야 한다
        // (실제 JpaRepository 조회 결과는 가변 리스트라 프로덕션 코드 자체엔 문제가 없다)
        when(aiChatMessageRepository.findByUserIdOrderByCreatedAtDesc(eq(1L), any(Pageable.class)))
                .thenReturn(new java.util.ArrayList<>(List.of(priorAssistant, priorUser))); // 최신순으로 반환 - 서비스가 뒤집어야 함
        when(aiChatMessageRepository.save(any(AiChatMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_RECOMMENDATION, null, null,
                null, null, null, null, null,
                List.of(new AiScheduleSuggestion.ScheduleItemSuggestion("제목", "내용", null, null, null)),
                "이유"));

        aiService.sendMessage("tester@example.com", "그거 말고 다른 거 추천해줘");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatClientRequest).messages(historyCaptor.capture());
        List<Message> passedHistory = historyCaptor.getValue();
        assertThat(passedHistory).hasSize(2);
        assertThat(passedHistory.get(0).getContent()).contains("어제 뭐 추천했었지?");
        assertThat(passedHistory.get(1).getContent()).contains("저녁 산책").contains("가벼운 산책을 추천드려요");
    }

    @Test
    @DisplayName("메시지 전송 성공 - 자정을 걸치는 시간대(종료 시각이 시작 시각보다 이름)는 종료를 다음날로 하루 밀어 순서를 유지한다")
    void sendMessage_success_pushesEndToNextDayWhenCrossingMidnight() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com"))
                .thenReturn(List.of(new CategoryResponseDto(10L, "일상")));

        // 모델이 같은 날짜에 종료 시각만 시작 시각보다 이르게(자정을 걸치는 시간대를 같은 날짜로) 줬다면
        // 순서가 뒤집힌 것이므로 종료를 다음날로 하루 밀어야 한다
        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_RECOMMENDATION, null, null,
                null, null, null, null, null,
                List.of(new AiScheduleSuggestion.ScheduleItemSuggestion(
                        "야간 근무", "당직", "2026-07-29T23:00:00", "2026-07-29T00:30:00", 10L)),
                "추천 이유"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "추천해줘");

        AiChatMessageDto.SuggestedScheduleItemDto item = exchange.assistantMessage().suggestedItems().get(0);
        assertThat(item.startAt()).isEqualTo(LocalDateTime.of(2026, 7, 29, 23, 0));
        assertThat(item.endAt()).isEqualTo(LocalDateTime.of(2026, 7, 30, 0, 30));
    }

    @Test
    @DisplayName("메시지 전송 성공 - 존재하지 않는 categoryId나 형식이 깨진 시각은 비워서 응답한다")
    void sendMessage_success_dropsInvalidCategoryAndUnparsableDates() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com"))
                .thenReturn(List.of(new CategoryResponseDto(10L, "운동")));

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_RECOMMENDATION, null, null,
                null, null, null, null, null,
                List.of(new AiScheduleSuggestion.ScheduleItemSuggestion("제목", "내용", "이상한 형식", null, 999L)),
                "추천 이유"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "추천해줘");

        AiChatMessageDto.SuggestedScheduleItemDto item = exchange.assistantMessage().suggestedItems().get(0);
        assertThat(item.startAt()).isNull();
        assertThat(item.endAt()).isNull();
        assertThat(item.categoryId()).isNull();
    }

    @Test
    @DisplayName("메시지 전송 성공 - 한 번에 여러 일정을 요청하면 scheduleItems 각 원소가 순서대로 항목화되어 저장/응답된다")
    void sendMessage_success_scheduleRecommendation_multipleItemsInOrder() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com"))
                .thenReturn(List.of(new CategoryResponseDto(10L, "운동"), new CategoryResponseDto(20L, "업무")));

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_RECOMMENDATION, null, null,
                null, null, null, null, null,
                List.of(
                        new AiScheduleSuggestion.ScheduleItemSuggestion("헬스장 운동", null, "2026-07-31T07:00:00", "2026-07-31T08:00:00", 10L),
                        new AiScheduleSuggestion.ScheduleItemSuggestion("팀 회의", "주간 회의", "2026-07-31T14:00:00", "2026-07-31T15:00:00", 20L),
                        new AiScheduleSuggestion.ScheduleItemSuggestion("병원 예약", null, "2026-08-01T10:00:00", "2026-08-01T10:30:00", null)),
                "요청하신 3개 일정을 추천드려요"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "운동, 팀 회의, 병원 예약 등록해줘");

        List<AiChatMessageDto.SuggestedScheduleItemDto> items = exchange.assistantMessage().suggestedItems();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).title()).isEqualTo("헬스장 운동");
        assertThat(items.get(0).categoryId()).isEqualTo(10L);
        assertThat(items.get(1).title()).isEqualTo("팀 회의");
        assertThat(items.get(1).categoryId()).isEqualTo(20L);
        assertThat(items.get(2).title()).isEqualTo("병원 예약");
        assertThat(items.get(2).categoryId()).isNull(); // categoryId 없음 -> null 그대로
        // 각 원소는 독립적으로 처리되며, 서로 다른(미래) 날짜를 요청했다면 항목마다 그 날짜가 그대로 유지된다
        assertThat(items.get(0).startAt()).isEqualTo(LocalDateTime.of(2026, 7, 31, 7, 0));
        assertThat(items.get(1).startAt()).isEqualTo(LocalDateTime.of(2026, 7, 31, 14, 0));
        assertThat(items.get(2).startAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0));
    }

    @Test
    @DisplayName("메시지 전송 성공 - scheduleItems 중 title이 빈 항목은 방어적으로 버려진다")
    void sendMessage_success_scheduleRecommendation_dropsBlankTitleItems() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_RECOMMENDATION, null, null,
                null, null, null, null, null,
                List.of(
                        new AiScheduleSuggestion.ScheduleItemSuggestion("정상 제목", null, null, null, null),
                        new AiScheduleSuggestion.ScheduleItemSuggestion("  ", null, null, null, null),
                        new AiScheduleSuggestion.ScheduleItemSuggestion(null, null, null, null, null)),
                "이유"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "추천해줘");

        assertThat(exchange.assistantMessage().suggestedItems())
                .hasSize(1)
                .extracting(AiChatMessageDto.SuggestedScheduleItemDto::title)
                .containsExactly("정상 제목");
    }

    @Test
    @DisplayName("메시지 전송 성공 - scheduleItems가 상한(10개)을 넘으면 앞에서부터 10개만 저장한다")
    void sendMessage_success_scheduleRecommendation_truncatesOverCapItems() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());

        List<AiScheduleSuggestion.ScheduleItemSuggestion> elevenItems = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> new AiScheduleSuggestion.ScheduleItemSuggestion("일정 " + i, null, null, null, null))
                .toList();
        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_RECOMMENDATION, null, null,
                null, null, null, null, null, elevenItems, "이유"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "일정 11개 등록해줘");

        assertThat(exchange.assistantMessage().suggestedItems()).hasSize(10);
        assertThat(exchange.assistantMessage().suggestedItems().get(9).title()).isEqualTo("일정 10");
    }

    @Test
    @DisplayName("메시지 전송 성공 - GENERAL로 분류된 답변은 등록 UI에 쓰이는 필드 없이 답변 텍스트만 채운다")
    void sendMessage_success_generalCategory_hasNoSuggestionFields() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());

        stubChatClient(new AiScheduleSuggestion(
                AiResponseCategory.GENERAL, null, null, null, null, null, null, null, null, "오늘은 회의가 2건 있어요."));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "오늘 일정 몇 개야?");

        assertThat(exchange.assistantMessage().category()).isEqualTo("GENERAL");
        assertThat(exchange.assistantMessage().message()).isEqualTo("오늘은 회의가 2건 있어요.");
        assertThat(exchange.assistantMessage().suggestedTitle()).isNull();
        assertThat(exchange.assistantMessage().suggestedContent()).isNull();
        assertThat(exchange.assistantMessage().suggestedStartAt()).isNull();
        assertThat(exchange.assistantMessage().suggestedEndAt()).isNull();
        assertThat(exchange.assistantMessage().suggestedCategoryId()).isNull();
    }

    @Test
    @DisplayName("메시지 전송 성공 - GENERAL인데 모델이 추천 필드를 채워 보내도 방어적으로 전부 비운다")
    void sendMessage_success_generalCategory_ignoresLeftoverSuggestionFieldsFromModel() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com"))
                .thenReturn(List.of(new CategoryResponseDto(10L, "운동")));

        // category는 GENERAL인데 모델이 지시를 어기고 title/startAt/categoryId를 채워 보낸 경우
        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.GENERAL, null, null,
                "저녁 러닝", "30분 조깅", "2026-07-29T19:00:00", "2026-07-29T19:30:00", 10L, null, "참고로 이런 것도 있어요"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "운동 종류 뭐가 있어?");

        assertThat(exchange.assistantMessage().suggestedTitle()).isNull();
        assertThat(exchange.assistantMessage().suggestedContent()).isNull();
        assertThat(exchange.assistantMessage().suggestedStartAt()).isNull();
        assertThat(exchange.assistantMessage().suggestedEndAt()).isNull();
        assertThat(exchange.assistantMessage().suggestedCategoryId()).isNull();
        assertThat(exchange.assistantMessage().suggestedItems()).isEmpty();
    }

    @Test
    @DisplayName("메시지 전송 성공 - category를 비워 보내면 GENERAL로 취급해 등록 UI 필드를 비운다")
    void sendMessage_success_nullCategory_treatedAsGeneral() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());

        stubChatClient(new AiScheduleSuggestion(null, null, null, "제목", null, null, null, null, null, "답변"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "질문");

        assertThat(exchange.assistantMessage().category()).isEqualTo("GENERAL");
        assertThat(exchange.assistantMessage().suggestedTitle()).isNull();
    }

    @Test
    @DisplayName("메시지 전송 성공 - SCHEDULE_UPDATE는 컨텍스트에 있던 일정 id를 targetScheduleId로 채택하고, 날짜를 오늘로 강제하지 않는다")
    void sendMessage_success_scheduleUpdate_appliesTargetScheduleIdWithoutForcingToday() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);

        LocalDateTime now = LocalDateTime.now();
        ScheduleResponseDto existing = new ScheduleResponseDto(
                5L, "팀 회의", "주간 회의", now.plusDays(1), now.plusDays(1).plusHours(1),
                ScheduleStatus.PENDING, "tester", "업무", now);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of(existing));
        when(categoryService.getCategories("tester@example.com"))
                .thenReturn(List.of(new CategoryResponseDto(10L, "업무")));

        // 모레(오늘이 아닌 날짜)로 미뤄달라는 요청 - SCHEDULE_RECOMMENDATION과 달리 날짜까지 그대로 신뢰해야 한다
        String movedStartAt = now.plusDays(2).withHour(15).withMinute(0).withSecond(0).withNano(0).toString();
        String movedEndAt = now.plusDays(2).withHour(16).withMinute(0).withSecond(0).withNano(0).toString();
        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_UPDATE, 5L, null,
                "팀 회의", "주간 회의", movedStartAt, movedEndAt, 10L, null, "모레 오후로 옮겼어요"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "그 회의 모레 오후 3시로 옮겨줘");

        assertThat(exchange.assistantMessage().category()).isEqualTo("SCHEDULE_UPDATE");
        assertThat(exchange.assistantMessage().targetScheduleId()).isEqualTo(5L);
        assertThat(exchange.assistantMessage().suggestedStartAt()).isEqualTo(now.plusDays(2).withHour(15).withMinute(0).withSecond(0).withNano(0));
        assertThat(exchange.assistantMessage().suggestedEndAt()).isEqualTo(now.plusDays(2).withHour(16).withMinute(0).withSecond(0).withNano(0));

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequest).user(userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue()).contains("id:5");
    }

    @Test
    @DisplayName("메시지 전송 성공 - SCHEDULE_UPDATE인데 컨텍스트에 없는 targetScheduleId를 주면 검증에 실패해 null로 비운다")
    void sendMessage_success_scheduleUpdate_rejectsUnknownTargetScheduleId() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());

        // 컨텍스트로 보여준 적 없는(지어낸) id
        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_UPDATE, 999L, null,
                "제목", "내용", null, null, null, null, "이유"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "그 일정 바꿔줘");

        assertThat(exchange.assistantMessage().targetScheduleId()).isNull();
    }

    @Test
    @DisplayName("메시지 전송 성공 - 고정 윈도우 밖이라도 RAG로 찾은 일정은 [참고: 의미상 비슷한 과거/예정 일정] 섹션으로 프롬프트에 포함된다")
    void sendMessage_success_includesRagScheduleContextWhenFound() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);

        LocalDateTime now = LocalDateTime.now();
        ScheduleResponseDto farInThePast = new ScheduleResponseDto(
                7L, "저번 분기 킥오프", "분기 목표 논의", now.minusMonths(3), now.minusMonths(3).plusHours(1),
                ScheduleStatus.COMPLETED, "tester", "업무", now);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of(farInThePast));
        when(categoryService.getCategories("tester@example.com"))
                .thenReturn(List.of(new CategoryResponseDto(10L, "업무")));
        when(scheduleEmbeddingService.findSimilarScheduleIds(eq(1L), anyString(), any(), eq(5)))
                .thenReturn(List.of(7L));

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_RECOMMENDATION, null, null,
                null, null, null, null, null,
                List.of(new AiScheduleSuggestion.ScheduleItemSuggestion("제목", "내용", null, null, 10L)),
                "추천 이유"));

        aiService.sendMessage("tester@example.com", "저번 분기 킥오프처럼 잡아줘");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequest).user(userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue())
                .contains("[참고: 의미상 비슷한 과거/예정 일정]")
                .contains("저번 분기 킥오프");
    }

    @Test
    @DisplayName("메시지 전송 성공 - RAG로 찾은 게 없으면 [참고: 의미상 비슷한 과거/예정 일정] 섹션 자체가 생략된다")
    void sendMessage_success_omitsRagScheduleContextWhenNoneFound() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());
        // scheduleEmbeddingService.findSimilarScheduleIds는 스텁하지 않음 - Mockito 기본값(빈 리스트)을 그대로 씀

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_RECOMMENDATION, null, null,
                null, null, null, null, null,
                List.of(new AiScheduleSuggestion.ScheduleItemSuggestion("제목", "내용", null, null, null)),
                "이유"));

        aiService.sendMessage("tester@example.com", "추천해줘");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequest).user(userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue()).doesNotContain("[참고: 의미상 비슷한 과거/예정 일정]");
    }

    @Test
    @DisplayName("메시지 전송 성공 - RAG로 찾은, 고정 윈도우 밖 일정도 SCHEDULE_UPDATE의 targetScheduleId로 신뢰한다")
    void sendMessage_success_scheduleUpdate_trustsRagFoundScheduleIdOutsideWindow() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);

        LocalDateTime now = LocalDateTime.now();
        ScheduleResponseDto farInThePast = new ScheduleResponseDto(
                7L, "저번 분기 킥오프", "분기 목표 논의", now.minusMonths(3), now.minusMonths(3).plusHours(1),
                ScheduleStatus.COMPLETED, "tester", "업무", now);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of(farInThePast));
        when(categoryService.getCategories("tester@example.com"))
                .thenReturn(List.of(new CategoryResponseDto(10L, "업무")));
        when(scheduleEmbeddingService.findSimilarScheduleIds(eq(1L), anyString(), any(), eq(5)))
                .thenReturn(List.of(7L));

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_UPDATE, 7L, null,
                "저번 분기 킥오프", "분기 목표 논의", now.minusMonths(3).toString(), now.minusMonths(3).plusHours(1).toString(),
                10L, null, "그거 다음 주로 옮겼어요"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "저번 분기 킥오프 다음 주로 옮겨줘");

        assertThat(exchange.assistantMessage().targetScheduleId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("메시지 전송 성공 - MANDALART_FILL은 컨텍스트에 있던 보드 id를 targetMandalartBoardId로 채택하고 실제로 채우기를 수행한다")
    void sendMessage_success_mandalartFill_callsMandalartAiServiceWithValidBoardId() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());

        MandalartBoard board = MandalartBoard.builder().id(50L).title("2026년 목표").user(requester).build();
        when(mandalartBoardRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(board));
        when(mandalartCellRepository.findByBoardIdAndRowIndexAndColIndex(50L, 4, 4)).thenReturn(Optional.empty());

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.MANDALART_FILL, null, 50L,
                null, null, null, null, null, null, "이 보드를 채워드릴게요"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "이 만다라트 채워줘");

        assertThat(exchange.assistantMessage().category()).isEqualTo("MANDALART_FILL");
        assertThat(exchange.assistantMessage().targetMandalartBoardId()).isEqualTo(50L);
        verify(mandalartAiService).fillWithAi("tester@example.com", 50L);
    }

    @Test
    @DisplayName("메시지 전송 성공 - MANDALART_FILL인데 컨텍스트에 없는 targetMandalartBoardId를 주면 검증에 실패해 채우기를 실행하지 않는다")
    void sendMessage_success_mandalartFill_rejectsUnknownBoardId() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());
        // mandalartBoardRepository는 @BeforeEach 기본값(빈 목록)을 그대로 쓴다 - 컨텍스트에 어떤 보드도 없는 상태

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.MANDALART_FILL, null, 999L,
                null, null, null, null, null, null, "이유"));

        AiChatExchangeDto exchange = aiService.sendMessage("tester@example.com", "만다라트 채워줘");

        assertThat(exchange.assistantMessage().targetMandalartBoardId()).isNull();
        verifyNoInteractions(mandalartAiService);
    }

    @Test
    @DisplayName("메시지 전송 성공 - 적용된 만다라트 보드가 있으면 핵심/세부 목표를 [나의 목표(만다라트)] 컨텍스트로 프롬프트에 포함한다")
    void sendMessage_success_includesActiveMandalartGoalContext() {
        User requester = user(1L);
        requester.updateActiveMandalartBoardId(10L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com"))
                .thenReturn(List.of(new CategoryResponseDto(10L, "운동")));

        MandalartBoard board = MandalartBoard.builder().id(10L).title("2026년 목표").user(requester).build();
        when(mandalartBoardRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(board));
        when(mandalartBoardRepository.findById(10L)).thenReturn(Optional.of(board));
        List<MandalartCell> cells = List.of(
                MandalartCell.builder().board(board).rowIndex(4).colIndex(4).content("건강한 삶").build(),
                MandalartCell.builder().board(board).rowIndex(3).colIndex(3).content("규칙적인 운동").build(),
                MandalartCell.builder().board(board).rowIndex(3).colIndex(4).content("균형 잡힌 식단").build()
        );
        when(mandalartCellRepository.findByBoardIdOrderByRowIndexAscColIndexAsc(10L)).thenReturn(cells);

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_RECOMMENDATION, null, null,
                null, null, null, null, null,
                List.of(new AiScheduleSuggestion.ScheduleItemSuggestion(
                        "저녁 러닝", "30분 조깅", "2026-07-29T19:00:00", "2026-07-29T19:30:00", 10L)),
                "추천 이유"));

        aiService.sendMessage("tester@example.com", "운동 일정 추천해줘");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequest).user(userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue())
                .contains("[나의 목표(만다라트)]")
                .contains("건강한 삶")
                .contains("규칙적인 운동")
                .contains("균형 잡힌 식단");
    }

    @Test
    @DisplayName("메시지 전송 성공 - 적용된 만다라트 보드가 없으면 [나의 목표(만다라트)] 컨텍스트를 프롬프트에서 생략한다")
    void sendMessage_success_omitsMandalartGoalContextWhenNoneActive() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_RECOMMENDATION, null, null,
                null, null, null, null, null,
                List.of(new AiScheduleSuggestion.ScheduleItemSuggestion("제목", "내용", null, null, null)),
                "이유"));

        aiService.sendMessage("tester@example.com", "추천해줘");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequest).user(userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue()).doesNotContain("[나의 목표(만다라트)]");
        verify(mandalartBoardRepository, org.mockito.Mockito.never()).findById(anyLong());
    }

    @Test
    @DisplayName("메시지 전송 성공 - 적용된 만다라트 보드 id가 이미 삭제되어 조회되지 않으면 목표 컨텍스트를 생략한다")
    void sendMessage_success_omitsMandalartGoalContextWhenActiveBoardMissing() {
        User requester = user(1L);
        requester.updateActiveMandalartBoardId(999L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        stubEmptyHistoryAndSave(requester);
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());
        when(mandalartBoardRepository.findById(999L)).thenReturn(Optional.empty());

        stubChatClient(new AiScheduleSuggestion(AiResponseCategory.SCHEDULE_RECOMMENDATION, null, null,
                null, null, null, null, null,
                List.of(new AiScheduleSuggestion.ScheduleItemSuggestion("제목", "내용", null, null, null)),
                "이유"));

        aiService.sendMessage("tester@example.com", "추천해줘");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequest).user(userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue()).doesNotContain("[나의 목표(만다라트)]");
    }

    @Test
    @DisplayName("메시지 전송 실패 - ChatClient 호출이 실패하면 AI_REQUEST_FAILED 로 감싼다")
    void sendMessage_chatClientThrows_wrapsAsBusinessException() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());
        when(categoryService.getCategories("tester@example.com")).thenReturn(List.of());
        when(aiChatMessageRepository.findByUserIdOrderByCreatedAtDesc(eq(1L), any(Pageable.class)))
                .thenReturn(List.of());

        when(chatClient.prompt()).thenReturn(chatClientRequest);
        when(chatClientRequest.system(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.messages(org.mockito.ArgumentMatchers.<List<Message>>any())).thenReturn(chatClientRequest);
        when(chatClientRequest.user(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.call()).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> aiService.sendMessage("tester@example.com", "추천해줘"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_REQUEST_FAILED);
    }

    @Test
    @DisplayName("메시지 전송 실패 - 분당 호출 제한에 걸리면 ChatClient를 호출하지 않고 예외를 그대로 전파한다")
    void sendMessage_rateLimited_doesNotCallChatClientAndPropagates() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED))
                .when(aiRateLimiter).checkLimit(1L, UserType.USER);

        assertThatThrownBy(() -> aiService.sendMessage("tester@example.com", "추천해줘"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_RATE_LIMIT_EXCEEDED);

        org.mockito.Mockito.verifyNoInteractions(chatClient);
    }

    @Test
    @DisplayName("메시지 전송 실패 - 존재하지 않는 유저면 예외가 발생한다")
    void sendMessage_userNotFound_throws() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> aiService.sendMessage("ghost@example.com", "추천해줘"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("대화 조회 성공 - 오래된 순서 그대로 DTO로 변환한다")
    void getConversation_success() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        AiChatMessage m1 = savedMessage(1L, requester, AiChatRole.USER);
        AiChatMessage m2 = savedMessage(2L, requester, AiChatRole.ASSISTANT);
        when(aiChatMessageRepository.findByUserIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(m1, m2));

        List<AiChatMessageDto> result = aiService.getConversation("tester@example.com");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(1).id()).isEqualTo(2L);
    }

    @Test
    @DisplayName("대화 조회 실패 - 존재하지 않는 유저면 예외가 발생한다")
    void getConversation_userNotFound_throws() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> aiService.getConversation("ghost@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("등록 표시 성공 - 본인 메시지에 등록된 일정 id를 남긴다")
    void markRegistered_success() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        AiChatMessage message = savedMessage(5L, requester, AiChatRole.ASSISTANT);
        when(aiChatMessageRepository.findById(5L)).thenReturn(Optional.of(message));

        AiChatMessageDto result = aiService.markRegistered("tester@example.com", 5L, 100L);

        assertThat(result.registeredScheduleId()).isEqualTo(100L);
        assertThat(message.getRegisteredScheduleId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("등록 표시 실패 - 다른 유저 소유 메시지면 존재하지 않는 것처럼 예외가 발생한다")
    void markRegistered_otherUsersMessage_throwsNotFound() {
        User requester = user(1L);
        User owner = user(2L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        AiChatMessage message = savedMessage(5L, owner, AiChatRole.ASSISTANT);
        when(aiChatMessageRepository.findById(5L)).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> aiService.markRegistered("tester@example.com", 5L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_CHAT_MESSAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("등록 표시 실패 - 존재하지 않는 메시지면 예외가 발생한다")
    void markRegistered_messageNotFound_throws() {
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(user(1L)));
        when(aiChatMessageRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> aiService.markRegistered("tester@example.com", 999L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_CHAT_MESSAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("항목 등록 표시 성공 - SCHEDULE_RECOMMENDATION 메시지의 특정 항목에만 등록된 일정 id를 남긴다")
    void markItemRegistered_success() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        AiChatMessage message = savedMessage(5L, requester, AiChatRole.ASSISTANT);
        AiChatSuggestedSchedule item1 = AiChatSuggestedSchedule.builder().id(50L).message(message).itemOrder(0).title("헬스장 운동").build();
        AiChatSuggestedSchedule item2 = AiChatSuggestedSchedule.builder().id(51L).message(message).itemOrder(1).title("팀 회의").build();
        message.addSuggestedSchedule(item1);
        message.addSuggestedSchedule(item2);
        when(aiChatMessageRepository.findById(5L)).thenReturn(Optional.of(message));

        AiChatMessageDto result = aiService.markItemRegistered("tester@example.com", 5L, 50L, 100L);

        assertThat(item1.getRegisteredScheduleId()).isEqualTo(100L);
        assertThat(item2.getRegisteredScheduleId()).isNull(); // 다른 항목은 그대로 미등록 상태
        assertThat(result.suggestedItems())
                .filteredOn(dto -> dto.id().equals(50L))
                .extracting(AiChatMessageDto.SuggestedScheduleItemDto::registeredScheduleId)
                .containsExactly(100L);
    }

    @Test
    @DisplayName("항목 등록 표시 실패 - 다른 유저 소유 메시지면 존재하지 않는 것처럼 예외가 발생한다")
    void markItemRegistered_otherUsersMessage_throwsNotFound() {
        User requester = user(1L);
        User owner = user(2L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        AiChatMessage message = savedMessage(5L, owner, AiChatRole.ASSISTANT);
        when(aiChatMessageRepository.findById(5L)).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> aiService.markItemRegistered("tester@example.com", 5L, 50L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_CHAT_MESSAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("항목 등록 표시 실패 - 메시지에 없는 itemId면 예외가 발생한다")
    void markItemRegistered_itemNotFound_throws() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        AiChatMessage message = savedMessage(5L, requester, AiChatRole.ASSISTANT);
        when(aiChatMessageRepository.findById(5L)).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> aiService.markItemRegistered("tester@example.com", 5L, 999L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_CHAT_SUGGESTED_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("대화 초기화 성공 - 본인 유저 id로 전체 삭제를 위임한다")
    void clearConversation_success() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        aiService.clearConversation("tester@example.com");

        verify(aiChatMessageRepository).deleteByUserId(1L);
    }
}
