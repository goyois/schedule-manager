package com.example.schedule_manager.domain.ai.service;

import com.example.schedule_manager.domain.ai.dto.AiChatExchangeDto;
import com.example.schedule_manager.domain.ai.dto.AiChatMessageDto;
import com.example.schedule_manager.domain.ai.dto.AiScheduleSuggestion;
import com.example.schedule_manager.domain.ai.entity.AiChatMessage;
import com.example.schedule_manager.domain.ai.entity.AiChatRole;
import com.example.schedule_manager.domain.ai.entity.AiChatSuggestedSchedule;
import com.example.schedule_manager.domain.ai.entity.AiResponseCategory;
import com.example.schedule_manager.domain.ai.repository.AiChatMessageRepository;
import com.example.schedule_manager.domain.ai.repository.AiChatSuggestedScheduleRepository;
import com.example.schedule_manager.domain.category.dto.CategoryResponseDto;
import com.example.schedule_manager.domain.category.service.CategoryService;
import com.example.schedule_manager.domain.mandalart.entity.MandalartBoard;
import com.example.schedule_manager.domain.mandalart.entity.MandalartCell;
import com.example.schedule_manager.domain.mandalart.repository.MandalartBoardRepository;
import com.example.schedule_manager.domain.mandalart.repository.MandalartCellRepository;
import com.example.schedule_manager.domain.mandalart.service.MandalartAiService;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.service.ScheduleEmbeddingService;
import com.example.schedule_manager.domain.schedule.service.ScheduleService;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AiService {

    // 토큰 비용 때문에 일정 컨텍스트는 최근 2주 + 향후 2주로 제한한다(AI_STRATEGY.md 3단계)
    private static final int CONTEXT_WINDOW_WEEKS = 2;

    // 고정 윈도우 밖(더 예전/훨씬 나중) 일정 중, 지금 대화와 의미적으로 비슷한 것만 보강으로 몇 개
    // 더 보여준다 - 프롬프트 크기/비용을 제한하기 위해 작게 잡는다
    private static final int RAG_SCHEDULE_TOP_K = 5;

    // 대화가 길어질수록 매 턴 다시 실어 보내는 토큰이 늘어나므로, LLM에 넘기는 과거 대화는
    // 최근 이만큼만으로 제한한다(채팅창 자체의 표시 기록은 이 제한과 무관하게 전부 보여준다)
    private static final int MAX_HISTORY_MESSAGES = 20;

    // SCHEDULE_RECOMMENDATION 한 턴에 모델이 scheduleItems를 과도하게 많이 채워 보내는 걸 막는 상한
    // (토큰 비용/UI 렌더링 부담 방어) - 이보다 많이 오면 앞에서부터 이 개수만큼만 자르고 나머지는 버린다
    private static final int MAX_SCHEDULE_ITEMS_PER_RECOMMENDATION = 10;

    // 만다라트 중앙 3x3 블록 좌표 - (4,4)는 핵심 목표, 나머지 8칸은 세부 목표(MandalartAiService의
    // OUTER_BLOCKS 계산과 같은 "핵심 목표를 둘러싼 중앙 블록" 정의). 일정 추천 컨텍스트는 토큰 비용 때문에
    // 실행항목 64칸까지는 넣지 않고 이 9칸까지만 사용한다
    private static final int[][] SUB_GOAL_COORDS = {
            {3, 3}, {3, 4}, {3, 5}, {4, 3}, {4, 5}, {5, 3}, {5, 4}, {5, 5},
    };

    private static final String SYSTEM_PROMPT = """
            당신은 일정 관리 도우미입니다. 사용자의 기존 일정과 카테고리, 그리고 이전 대화 맥락을 참고해
            대화에 답하세요. 대화는 계속 이어질 수 있으니 이전에 추천/제안한 내용을 기억하고 사용자의 후속
            질문(수정 요청, 추가 질문 등)에 자연스럽게 이어서 답하세요.

            먼저 이번 답변을 아래 네 카테고리 중 하나로 분류해 category에 쓰세요. 판단 순서가 중요합니다 -
            아래 순서 그대로 확인하세요.

            1) 사용자의 요청이 [만다라트 보드] 목록에 있는 보드 하나를 채워달라는 것(예: "이 목표 만다라트
               채워줘", "OO 보드 빈 칸 채워줘")이면 MANDALART_FILL로 분류하세요.
               - targetMandalartBoardId에 [만다라트 보드] 목록의 "id:숫자" 중 그 보드의 id를 정확히 쓰세요.
                 title/content/startAt/endAt/categoryId/targetScheduleId는 전부 null로 두세요.
               - reason에는 어느 보드를 채우는지 한 문장으로 답하세요.
               - 어느 보드인지 [만다라트 보드] 목록에서 특정할 수 없으면(목록이 비어있거나 여러 개라
                 애매하면) MANDALART_FILL로 분류하지 말고 GENERAL로 답하며 어느 보드인지 되물으세요.
            2) 사용자의 요청이 [기존 일정] 목록에 있는 특정 일정 하나를 바꾸는 것(시간/날짜 변경, 미루기,
               제목·내용 수정 등)이면 무조건 SCHEDULE_UPDATE로 분류하세요. "옮겨줘", "미뤄줘", "바꿔줘",
               "수정해줘" 같은 말은 거의 항상 이 경우입니다 - 이미 존재하는 그 일정을 다시 추천하듯
               SCHEDULE_RECOMMENDATION으로 분류하면 안 됩니다.
               - targetScheduleId에 [기존 일정] 목록의 "id:숫자" 중 그 일정의 id를 정확히 쓰세요.
               - title/content/startAt/endAt/categoryId에는 "바뀐 뒤의 최종 값"을 채우세요 - 사용자가 특정
                 필드만 바꿔달라고 했어도, 바뀌지 않는 나머지 필드는 [기존 일정]에 나온 그 일정의 현재 값을
                 그대로 옮겨 쓰세요(예: 시간만 바꿔달라고 하면 title/categoryId는 기존 값을 그대로 반복).
                 startAt/endAt은 사용자가 말한 날짜를 그대로 반영하세요(오늘로 바꿔치기하지 마세요) - 예를
                 들어 "내일로 옮겨줘"면 실제로 내일 날짜를 쓰세요.
               - reason에는 무엇을 어떻게 바꾸는지 한두 문장으로 설명하세요.
               - 어느 일정을 말하는지 [기존 일정] 목록에서 특정할 수 없으면 SCHEDULE_UPDATE로 분류하지 말고
                 GENERAL로 답하며 어느 일정인지 되물으세요.
            3) 그게 아니라 [기존 일정]에 없는 완전히 새로운 일정을 만들어 추천받고 싶어하는 경우면
               SCHEDULE_RECOMMENDATION으로 분류하세요. 사용자가 한 번에 여러 일정을 요청했다면(예: "이번
               주에 운동, 팀 회의, 병원 예약 등록해줘") scheduleItems 배열에 요청받은 일정 하나마다 원소
               하나씩 채우세요 - 일정이 하나뿐이어도 항상 원소 1개짜리 배열로 채우세요(최상위
               title/content/startAt/endAt/categoryId는 SCHEDULE_UPDATE 전용이니 이때는 전부 null로
               두세요). targetScheduleId/targetMandalartBoardId도 null로 두세요.
               - scheduleItems는 최대 10개까지만 채우세요. 그보다 많이 요청받았어도 10개로 줄이고,
                 reason에서 나머지는 이후 대화로 나눠 요청해달라고 안내하세요.
               - 각 원소의 categoryId는 [사용 가능한 카테고리]에 나열된 id 중 하나만 쓰고, 적절한
                 카테고리가 없으면 null로 두세요.
               - 종료 시각이 필요 없는 알림형 일정이면 그 원소의 endAt을 null로 두세요.
               - [나의 목표(만다라트)]가 주어져 있으면, 사용자의 요청과 관련이 있는 범위에서 그 핵심
                 목표/세부 목표를 달성하는 데 도움이 되는 일정을 우선 추천하고, reason에 어느 목표와
                 관련 있는지 짧게 언급하세요. 다만 사용자가 이미 구체적으로 무엇을 원하는지 말했다면
                 그 요청을 우선하고, 목표와 무관해 보여도 억지로 끼워 맞추지 마세요.
               - reason에는 왜 이 일정(들)을 추천하는지 한두 문장으로 설명하세요.
            4) 그 외 모든 경우(기존 일정 조회/설명, 잡담, 대상을 특정할 수 없는 수정/채우기 요청 등)는
               GENERAL로 분류하세요. targetScheduleId/targetMandalartBoardId, scheduleItems,
               title/content/startAt/endAt/categoryId를 전부 null(또는 빈 배열)로 두고, reason에 답변
               내용을 쓰세요.

            SCHEDULE_UPDATE의 startAt/endAt, SCHEDULE_RECOMMENDATION의 scheduleItems 각 원소의
            startAt/endAt은 모두 "yyyy-MM-ddTHH:mm:ss" 형식(타임존 없음)으로 쓰세요.
            """;

    private final ChatClient chatClient;
    private final ScheduleService scheduleService;
    private final CategoryService categoryService;
    private final UserRepository userRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiChatSuggestedScheduleRepository aiChatSuggestedScheduleRepository;
    private final AiRateLimiter aiRateLimiter;
    private final MandalartBoardRepository mandalartBoardRepository;
    private final MandalartCellRepository mandalartCellRepository;
    private final MandalartAiService mandalartAiService;
    private final ScheduleEmbeddingService scheduleEmbeddingService;

    @Transactional(readOnly = true)
    public List<AiChatMessageDto> getConversation(String requesterEmail) {
        User requester = findUserByEmail(requesterEmail);
        return aiChatMessageRepository.findByUserIdOrderByCreatedAtAsc(requester.getId()).stream()
                .map(AiChatMessageDto::from)
                .toList();
    }

    public void clearConversation(String requesterEmail) {
        User requester = findUserByEmail(requesterEmail);
        aiChatMessageRepository.deleteByUserId(requester.getId());
    }

    // 이 추천으로 실제 일정이 등록된 뒤(수동/자동 등록 모두 결국 기존 POST /api/schedules를 그대로 타므로,
    // 여기서 직접 저장하지 않는다) 프론트가 호출해서 채팅 메시지와 생성된 일정을 연결해둔다 -
    // 같은 추천을 중복 등록하지 않도록 UI에서 "등록됨" 여부를 판단하는 데 쓰인다
    public AiChatMessageDto markRegistered(String requesterEmail, Long messageId, Long scheduleId) {
        User requester = findUserByEmail(requesterEmail);
        AiChatMessage message = aiChatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_CHAT_MESSAGE_NOT_FOUND));
        // 다른 유저의 메시지 id를 넣어 시도해도 존재 자체를 드러내지 않는다(다른 도메인의 소유권 검사와 동일한 패턴)
        if (!message.getUser().getId().equals(requester.getId())) {
            throw new BusinessException(ErrorCode.AI_CHAT_MESSAGE_NOT_FOUND);
        }
        message.markRegistered(scheduleId);
        return AiChatMessageDto.from(message);
    }

    // markRegistered와 같은 목적이지만 SCHEDULE_RECOMMENDATION이 한 번에 여러 일정을 제안했을 때, 그중
    // 어느 항목이 등록됐는지까지 구분해서 기록한다 - 소유권 검증은 항목이 아니라 부모 메시지 기준으로 한다
    // (항목 자체는 유저를 직접 들고 있지 않으므로)
    public AiChatMessageDto markItemRegistered(String requesterEmail, Long messageId, Long itemId, Long scheduleId) {
        User requester = findUserByEmail(requesterEmail);
        AiChatMessage message = aiChatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_CHAT_MESSAGE_NOT_FOUND));
        if (!message.getUser().getId().equals(requester.getId())) {
            throw new BusinessException(ErrorCode.AI_CHAT_MESSAGE_NOT_FOUND);
        }
        AiChatSuggestedSchedule item = message.getSuggestedSchedules().stream()
                .filter(s -> s.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_CHAT_SUGGESTED_ITEM_NOT_FOUND));
        item.markRegistered(scheduleId);
        return AiChatMessageDto.from(message);
    }

    // 프론트의 "일정 추가" 폼에 바로 채워 넣을 수 있도록 title/startAt/endAt/categoryId 등 구조화된 값으로
    // 받는다. 다만 모델이 지어낸 값을 그대로 믿지 않고 - 날짜 형식이 깨지거나 존재하지 않는 categoryId면
    // 해당 필드만 비워서 응답한다. 실제 저장은 항상 이 메서드 밖(수동 등록은 폼 검토 후, 자동 등록은
    // 설정이 켜져 있을 때만)에서 기존 POST /api/schedules로 하고, 여기서 직접 저장하지 않는다
    public AiChatExchangeDto sendMessage(String requesterEmail, String userText) {
        User requester = findUserByEmail(requesterEmail);
        aiRateLimiter.checkLimit(requester.getId(), requester.getUserType());

        List<AiChatMessage> recentHistory = aiChatMessageRepository
                .findByUserIdOrderByCreatedAtDesc(requester.getId(), PageRequest.of(0, MAX_HISTORY_MESSAGES));
        Collections.reverse(recentHistory); // 최신순으로 가져온 걸 시간순으로 뒤집는다

        List<ScheduleResponseDto> allSchedules = scheduleService.getSchedules(requesterEmail, requester.getId(), null);
        List<ScheduleResponseDto> windowedSchedules = filterToWindow(allSchedules);
        String scheduleContext = buildScheduleContext(windowedSchedules);
        Set<Long> windowedScheduleIds = windowedSchedules.stream().map(ScheduleResponseDto::id).collect(Collectors.toSet());

        // 고정 윈도우 밖이라도 지금 대화 내용과 의미적으로 비슷한 과거/예정 일정을 보강으로 찾는다
        // (예: "저번 달에 했던 것처럼" 같은 요청은 2주 윈도우만으론 절대 못 찾음). 실패해도 빈 목록이라
        // 조용히 원래 흐름(고정 윈도우만)으로 진행된다(fail-open, ScheduleEmbeddingService 참고)
        List<Long> ragScheduleIds = scheduleEmbeddingService.findSimilarScheduleIds(
                requester.getId(), userText, windowedScheduleIds, RAG_SCHEDULE_TOP_K);
        Map<Long, ScheduleResponseDto> scheduleById = allSchedules.stream()
                .collect(Collectors.toMap(ScheduleResponseDto::id, dto -> dto, (a, b) -> a));
        List<ScheduleResponseDto> ragSchedules = ragScheduleIds.stream()
                .map(scheduleById::get)
                .filter(Objects::nonNull)
                .toList();
        String ragScheduleContext = ragSchedules.isEmpty()
                ? ""
                : "\n\n[참고: 의미상 비슷한 과거/예정 일정]\n" + buildScheduleContext(ragSchedules);

        // SCHEDULE_UPDATE의 targetScheduleId 검증(환각 방지)에 쓰는 "이번 턴에 실제로 보여준 일정" 집합에
        // RAG로 찾은 것도 포함시킨다 - 윈도우 밖 일정도 이제 "그거 옮겨줘" 식으로 참조할 수 있어야 하므로
        Set<Long> trustedScheduleIds = new HashSet<>(windowedScheduleIds);
        trustedScheduleIds.addAll(ragSchedules.stream().map(ScheduleResponseDto::id).toList());

        List<CategoryResponseDto> categories = categoryService.getCategories(requesterEmail);
        String categoryContext = buildCategoryContext(categories);
        List<MandalartBoard> mandalartBoards = mandalartBoardRepository.findByUserIdOrderByCreatedAtDesc(requester.getId());
        String mandalartContext = buildMandalartContext(mandalartBoards);
        Set<Long> ownedMandalartBoardIds = mandalartBoards.stream().map(MandalartBoard::getId).collect(Collectors.toSet());
        String activeGoalContext = buildActiveMandalartGoalContext(requester);

        List<Message> conversationHistory = recentHistory.stream().map(this::toSpringAiMessage).toList();

        String userPrompt = userText + "\n\n[기존 일정]\n" + scheduleContext + ragScheduleContext + "\n\n[사용 가능한 카테고리]\n" + categoryContext
                + "\n\n[만다라트 보드]\n" + mandalartContext
                + (activeGoalContext != null ? "\n\n[나의 목표(만다라트)]\n" + activeGoalContext : "");

        AiScheduleSuggestion suggestion;
        try {
            suggestion = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .messages(conversationHistory)
                    .user(userPrompt)
                    .call()
                    .entity(AiScheduleSuggestion.class);
        } catch (Exception e) {
            // Claude API 타임아웃·레이트리밋·응답 파싱 실패 등 원인과 무관하게 BusinessException 으로 감싸
            // 기존 GlobalExceptionHandler 가 일관된 응답 포맷으로 처리하게 한다(AI_STRATEGY.md 6단계)
            throw new BusinessException(ErrorCode.AI_REQUEST_FAILED, e);
        }

        // 모델이 category를 비워 보내는 등 응답을 신뢰할 수 없는 경우 GENERAL로 취급한다(등록/수정 UI가
        // 잘못 노출되는 쪽보다 안 보이는 쪽이 안전하다) - SCHEDULE_RECOMMENDATION/SCHEDULE_UPDATE/
        // MANDALART_FILL일 때만 나머지 필드를 채우고, 그 외에는 모델이 뭘 채워 보냈든 전부 무시하고
        // 강제로 비운다(방어적 처리)
        AiResponseCategory category = suggestion.category() != null ? suggestion.category() : AiResponseCategory.GENERAL;
        boolean isRecommendation = category == AiResponseCategory.SCHEDULE_RECOMMENDATION;
        boolean isUpdate = category == AiResponseCategory.SCHEDULE_UPDATE;
        boolean isMandalartFill = category == AiResponseCategory.MANDALART_FILL;

        Long categoryId = null;
        LocalDateTime startAt = null;
        LocalDateTime endAt = null;
        String suggestedTitle = null;
        String suggestedContent = null;
        Long targetScheduleId = null;
        Long targetMandalartBoardId = null;
        List<SanitizedScheduleItem> sanitizedItems = List.of();

        if (isRecommendation) {
            Set<Long> validCategoryIds = categories.stream().map(CategoryResponseDto::id).collect(Collectors.toSet());
            List<AiScheduleSuggestion.ScheduleItemSuggestion> rawItems =
                    suggestion.scheduleItems() != null ? suggestion.scheduleItems() : List.of();
            if (rawItems.size() > MAX_SCHEDULE_ITEMS_PER_RECOMMENDATION) {
                log.warn("AI가 scheduleItems를 {}개 반환해 상한({}개)으로 잘라냄", rawItems.size(), MAX_SCHEDULE_ITEMS_PER_RECOMMENDATION);
                rawItems = rawItems.subList(0, MAX_SCHEDULE_ITEMS_PER_RECOMMENDATION);
            }
            // title이 아예 없는 원소는 모델이 지어낸 빈 항목일 가능성이 높아 버린다(방어적 처리) -
            // 나머지 필드(시간/카테고리)는 sanitizeRecommendationItem에서 개별적으로 검증/보정한다
            sanitizedItems = rawItems.stream()
                    .map(item -> sanitizeRecommendationItem(item, validCategoryIds))
                    .filter(item -> item.title() != null && !item.title().isBlank())
                    .toList();
        } else if (isUpdate) {
            // 모델이 [기존 일정] 컨텍스트에 없는(또는 지어낸) id를 targetScheduleId로 줄 수 있으므로,
            // 실제로 이번 턴에 컨텍스트로 보여준 id 집합에 있을 때만 신뢰한다 - 검증에 실패하면 targetScheduleId가
            // null로 남아 프론트가 "수정 반영" 버튼을 아예 보여주지 않는다(엉뚱한 일정을 덮어쓸 위험 차단)
            targetScheduleId = suggestion.targetScheduleId() != null && trustedScheduleIds.contains(suggestion.targetScheduleId())
                    ? suggestion.targetScheduleId()
                    : null;

            Set<Long> validCategoryIds = categories.stream().map(CategoryResponseDto::id).collect(Collectors.toSet());
            categoryId = suggestion.categoryId() != null && validCategoryIds.contains(suggestion.categoryId())
                    ? suggestion.categoryId()
                    : null;
            // 수정 제안은 추천과 달리 "오늘"로 강제하지 않는다 - 기존 일정을 다른 날짜로 미루는 요청(예: "내일로
            // 옮겨줘")도 있을 수 있으므로, 모델이 계산한 날짜를 시:분뿐 아니라 날짜까지 그대로 신뢰한다
            startAt = parseDateTimeSafely(suggestion.startAt());
            endAt = parseDateTimeSafely(suggestion.endAt());
            if (startAt != null && endAt != null && !endAt.isAfter(startAt)) {
                endAt = endAt.plusDays(1);
            }
            suggestedTitle = suggestion.title();
            suggestedContent = suggestion.content();
        } else if (isMandalartFill) {
            // 모델이 [만다라트 보드] 컨텍스트에 없는(또는 지어낸) id를 줄 수 있으므로, 실제로 이번 턴에
            // 보여준 본인 소유 보드 id 집합에 있을 때만 신뢰한다(targetScheduleId 검증과 같은 이유)
            targetMandalartBoardId = suggestion.targetMandalartBoardId() != null
                    && ownedMandalartBoardIds.contains(suggestion.targetMandalartBoardId())
                    ? suggestion.targetMandalartBoardId()
                    : null;
            if (targetMandalartBoardId != null) {
                // 스케줄 추천/수정과 달리 별도의 "반영" 버튼 없이 바로 채운다 - 빈 칸만 채우고 이미 채워진
                // 칸은 절대 건드리지 않으므로(MandalartAiService) 검토 단계 없이 자동 적용해도 안전하다
                mandalartAiService.fillWithAi(requesterEmail, targetMandalartBoardId);
            }
        }

        AiChatMessage userMessage = aiChatMessageRepository.save(AiChatMessage.builder()
                .user(requester)
                .role(AiChatRole.USER)
                .messageText(userText)
                .build());

        AiChatMessage assistantMessage = aiChatMessageRepository.save(AiChatMessage.builder()
                .user(requester)
                .role(AiChatRole.ASSISTANT)
                .messageText(suggestion.reason())
                .category(category)
                .targetScheduleId(targetScheduleId)
                .targetMandalartBoardId(targetMandalartBoardId)
                .suggestedTitle(suggestedTitle)
                .suggestedContent(suggestedContent)
                .suggestedStartAt(startAt)
                .suggestedEndAt(endAt)
                .suggestedCategoryId(categoryId)
                .build());

        // 반환하는 AiChatMessageDto가 각 항목의 id(등록 시 PATCH .../items/{itemId}/register에 필요)를
        // 바로 담을 수 있어야 하므로, cascade에 맡기지 않고 각 항목을 직접 save해 IDENTITY id를 즉시 받는다
        int order = 0;
        for (SanitizedScheduleItem item : sanitizedItems) {
            AiChatSuggestedSchedule saved = aiChatSuggestedScheduleRepository.save(AiChatSuggestedSchedule.builder()
                    .message(assistantMessage)
                    .itemOrder(order++)
                    .title(item.title())
                    .content(item.content())
                    .startAt(item.startAt())
                    .endAt(item.endAt())
                    .categoryId(item.categoryId())
                    .build());
            assistantMessage.addSuggestedSchedule(saved);
        }

        return new AiChatExchangeDto(AiChatMessageDto.from(userMessage), AiChatMessageDto.from(assistantMessage));
    }

    // 모델이 MANDALART_FILL의 targetMandalartBoardId로 어느 보드를 가리키는지 밝힐 수 있도록 보드
    // id와 핵심 목표(4,4 칸)를 함께 보여준다 - 제목만으로는 어느 보드인지 구분하기 어려울 수 있어서
    // (예: 여러 보드를 "커리어"라고 비슷하게 지었어도 핵심 목표 내용은 다를 수 있음)
    private String buildMandalartContext(List<MandalartBoard> boards) {
        if (boards.isEmpty()) {
            return "(등록된 만다라트 보드 없음)";
        }
        return boards.stream()
                .map(b -> {
                    String mainGoal = mandalartCellRepository.findByBoardIdAndRowIndexAndColIndex(b.getId(), 4, 4)
                            .map(MandalartCell::getContent)
                            .filter(content -> content != null && !content.isBlank())
                            .orElse("(핵심 목표 미입력)");
                    return "- [id:%d] %s (핵심 목표: %s)".formatted(b.getId(), b.getTitle(), mainGoal);
                })
                .collect(Collectors.joining("\n"));
    }

    // 유저가 "적용 중"으로 지정한 보드(User.activeMandalartBoardId)의 핵심 목표 + 세부 목표 8개만 골라
    // SCHEDULE_RECOMMENDATION 프롬프트에 참고 컨텍스트로 얹는다 - buildMandalartContext([만다라트 보드])와
    // 달리 실행항목 64칸은 넣지 않고(토큰 비용), 적용된 보드 하나만 다룬다. 적용된 보드가 없거나(null),
    // 있어도 이미 삭제됐거나 더 이상 본인 소유가 아니면(끊긴 포인터 방어) null을 반환해 프롬프트에서
    // 이 블록 자체를 생략한다
    private String buildActiveMandalartGoalContext(User requester) {
        Long activeBoardId = requester.getActiveMandalartBoardId();
        if (activeBoardId == null) {
            return null;
        }

        MandalartBoard board = mandalartBoardRepository.findById(activeBoardId).orElse(null);
        if (board == null || !board.getUser().getId().equals(requester.getId())) {
            return null;
        }

        List<MandalartCell> cells = mandalartCellRepository.findByBoardIdOrderByRowIndexAscColIndexAsc(activeBoardId);
        Map<String, String> contentByCoord = cells.stream()
                .collect(Collectors.toMap(c -> c.getRowIndex() + "-" + c.getColIndex(), MandalartCell::getContent, (a, b) -> a));

        String mainGoal = contentByCoord.getOrDefault("4-4", "");
        String subGoals = Arrays.stream(SUB_GOAL_COORDS)
                .map(coord -> contentByCoord.getOrDefault(coord[0] + "-" + coord[1], ""))
                .filter(content -> content != null && !content.isBlank())
                .collect(Collectors.joining(", "));

        return "- 보드: %s\n- 핵심 목표: %s\n- 세부 목표: %s".formatted(
                board.getTitle(),
                mainGoal != null && !mainGoal.isBlank() ? mainGoal : "(핵심 목표 미입력)",
                subGoals.isBlank() ? "(세부 목표 미입력)" : subGoals);
    }

    // 과거 ASSISTANT 턴을 LLM에 그대로 재현하기보다(구조화된 필드를 다시 JSON으로 만들어 넣는 건 과함),
    // 모델이 대화 맥락을 이해할 정도로만 요약해서 넣는다
    private Message toSpringAiMessage(AiChatMessage message) {
        if (message.getRole() == AiChatRole.USER) {
            return new UserMessage(message.getMessageText());
        }
        String summary;
        if (!message.getSuggestedSchedules().isEmpty()) {
            String titles = message.getSuggestedSchedules().stream()
                    .map(AiChatSuggestedSchedule::getTitle)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));
            summary = "[추천: %s] %s".formatted(titles, message.getMessageText());
        } else if (message.getSuggestedTitle() != null) {
            summary = "[추천: %s%s] %s".formatted(
                    message.getSuggestedTitle(),
                    message.getSuggestedStartAt() != null ? " (" + message.getSuggestedStartAt() + ")" : "",
                    message.getMessageText());
        } else {
            summary = message.getMessageText();
        }
        return new AssistantMessage(summary);
    }

    private LocalDateTime toToday(LocalDateTime value) {
        return value == null ? null : value.toLocalTime().atDate(LocalDate.now());
    }

    // scheduleItems 원소 하나를 검증/보정한다 - 단일 추천이던 시절의 로직(오늘 날짜 강제, categoryId 검증,
    // endAt이 startAt보다 앞서면 다음날로 보정)을 항목 개수와 무관하게 그대로 적용한다
    private record SanitizedScheduleItem(String title, String content, LocalDateTime startAt, LocalDateTime endAt, Long categoryId) {
    }

    private SanitizedScheduleItem sanitizeRecommendationItem(
            AiScheduleSuggestion.ScheduleItemSuggestion item, Set<Long> validCategoryIds) {
        Long categoryId = item.categoryId() != null && validCategoryIds.contains(item.categoryId())
                ? item.categoryId()
                : null;

        // 모델은 컨텍스트로 준 최근 2주~향후 2주 일정을 보고 다른 날짜(예: "이번 주 목요일")를 추천할 수도
        // 있는데, 실제로 등록될 일정은 항상 오늘 날짜여야 한다는 요구사항이 있어 시각(시:분)만 남기고
        // 날짜는 오늘로 강제한다 - reason 텍스트는 원래 추천 맥락(다른 요일 언급 등)을 그대로 담고 있을 수
        // 있지만, 실제로 등록되는 startAt/endAt은 항상 오늘 기준이다
        LocalDateTime startAt = toToday(parseDateTimeSafely(item.startAt()));
        LocalDateTime endAt = toToday(parseDateTimeSafely(item.endAt()));
        if (startAt != null && endAt != null && !endAt.isAfter(startAt)) {
            endAt = endAt.plusDays(1);
        }
        return new SanitizedScheduleItem(item.title(), item.content(), startAt, endAt, categoryId);
    }

    private String buildCategoryContext(List<CategoryResponseDto> categories) {
        if (categories.isEmpty()) {
            return "(등록된 카테고리 없음)";
        }
        return categories.stream()
                .map(c -> "- id: %d, name: %s".formatted(c.id(), c.name()))
                .collect(Collectors.joining("\n"));
    }

    private LocalDateTime parseDateTimeSafely(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            log.warn("AI가 반환한 시각을 파싱할 수 없어 비워둠 - value={}", value);
            return null;
        }
    }

    // 이미 불러온 전체 일정 목록(sendMessage에서 requester.getId()를 명시적으로 넘겨 조회한 것 - ADMIN이
    // 호출하더라도 전체 유저 일정이 아니라 본인 일정 기준으로 추천해야 하므로 ScheduleService.getSchedules의
    // "USER는 본인 id로 강제" 규칙에 기대지 않음)에서 고정 윈도우(최근 2주~향후 2주)만 걸러낸다
    private List<ScheduleResponseDto> filterToWindow(List<ScheduleResponseDto> schedules) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusWeeks(CONTEXT_WINDOW_WEEKS);
        LocalDateTime windowEnd = now.plusWeeks(CONTEXT_WINDOW_WEEKS);

        // 알림형(종료 시각 없음) 일정은 시작 시각을 종료 시각 대신 써서 윈도우 필터를 통과시킨다
        return schedules.stream()
                .filter(s -> {
                    LocalDateTime effectiveEnd = s.endAt() != null ? s.endAt() : s.startAt();
                    return !effectiveEnd.isBefore(windowStart) && !s.startAt().isAfter(windowEnd);
                })
                .sorted(Comparator.comparing(ScheduleResponseDto::startAt))
                .toList();
    }

    // id를 함께 실어 보내야 SCHEDULE_UPDATE 응답의 targetScheduleId로 모델이 정확히 어느 일정을 가리키는지
    // 밝힐 수 있다 - windowedScheduleIds(sendMessage에서 이 메서드에 넘긴 것과 같은 목록의 id 집합)로
    // 그 값이 실제로 이번 턴에 보여준 일정인지 검증한다
    private String buildScheduleContext(List<ScheduleResponseDto> windowed) {
        if (windowed.isEmpty()) {
            return "(최근 2주~향후 2주 사이 등록된 일정 없음)";
        }

        return windowed.stream()
                .map(s -> s.endAt() != null
                        ? "- [id:%d][%s] %s (%s ~ %s, 카테고리: %s)".formatted(
                                s.id(), s.status(), s.title(), s.startAt(), s.endAt(), s.categoryName())
                        : "- [id:%d][%s] %s (%s, 카테고리: %s)".formatted(
                                s.id(), s.status(), s.title(), s.startAt(), s.categoryName()))
                .collect(Collectors.joining("\n"));
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
