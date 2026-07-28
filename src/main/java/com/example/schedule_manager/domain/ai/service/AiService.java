package com.example.schedule_manager.domain.ai.service;

import com.example.schedule_manager.domain.ai.dto.AiChatExchangeDto;
import com.example.schedule_manager.domain.ai.dto.AiChatMessageDto;
import com.example.schedule_manager.domain.ai.dto.AiScheduleSuggestion;
import com.example.schedule_manager.domain.ai.entity.AiChatMessage;
import com.example.schedule_manager.domain.ai.entity.AiChatRole;
import com.example.schedule_manager.domain.ai.repository.AiChatMessageRepository;
import com.example.schedule_manager.domain.category.dto.CategoryResponseDto;
import com.example.schedule_manager.domain.category.service.CategoryService;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AiService {

    // 토큰 비용 때문에 일정 컨텍스트는 최근 2주 + 향후 2주로 제한한다(AI_STRATEGY.md 3단계)
    private static final int CONTEXT_WINDOW_WEEKS = 2;

    // 대화가 길어질수록 매 턴 다시 실어 보내는 토큰이 늘어나므로, LLM에 넘기는 과거 대화는
    // 최근 이만큼만으로 제한한다(채팅창 자체의 표시 기록은 이 제한과 무관하게 전부 보여준다)
    private static final int MAX_HISTORY_MESSAGES = 20;

    private static final String SYSTEM_PROMPT = """
            당신은 일정 관리 도우미입니다. 사용자의 기존 일정과 카테고리, 그리고 이전 대화 맥락을 참고해
            실행 가능한 일정 하나를 추천하세요. 대화는 계속 이어질 수 있으니 이전에 추천한 내용을 기억하고
            사용자의 후속 질문(수정 요청, 추가 질문 등)에 자연스럽게 이어서 답하세요.
            - categoryId는 [사용 가능한 카테고리]에 나열된 id 중 하나만 쓰고, 적절한 카테고리가 없으면 null로 두세요.
            - startAt/endAt은 "yyyy-MM-ddTHH:mm:ss" 형식(타임존 없음)으로 쓰세요.
            - 종료 시각이 필요 없는 알림형 일정이면 endAt을 null로 두세요.
            - reason에는 왜 이 일정을 추천하는지 한두 문장으로 설명하세요.
            """;

    private final ChatClient chatClient;
    private final ScheduleService scheduleService;
    private final CategoryService categoryService;
    private final UserRepository userRepository;
    private final AiChatMessageRepository aiChatMessageRepository;

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

    // 프론트의 "일정 추가" 폼에 바로 채워 넣을 수 있도록 title/startAt/endAt/categoryId 등 구조화된 값으로
    // 받는다. 다만 모델이 지어낸 값을 그대로 믿지 않고 - 날짜 형식이 깨지거나 존재하지 않는 categoryId면
    // 해당 필드만 비워서 응답한다. 실제 저장은 항상 이 메서드 밖(수동 등록은 폼 검토 후, 자동 등록은
    // 설정이 켜져 있을 때만)에서 기존 POST /api/schedules로 하고, 여기서 직접 저장하지 않는다
    public AiChatExchangeDto sendMessage(String requesterEmail, String userText) {
        User requester = findUserByEmail(requesterEmail);

        List<AiChatMessage> recentHistory = aiChatMessageRepository
                .findByUserIdOrderByCreatedAtDesc(requester.getId(), PageRequest.of(0, MAX_HISTORY_MESSAGES));
        Collections.reverse(recentHistory); // 최신순으로 가져온 걸 시간순으로 뒤집는다

        String scheduleContext = buildScheduleContext(requesterEmail, requester.getId());
        List<CategoryResponseDto> categories = categoryService.getCategories(requesterEmail);
        String categoryContext = buildCategoryContext(categories);

        List<Message> conversationHistory = recentHistory.stream().map(this::toSpringAiMessage).toList();

        AiScheduleSuggestion suggestion;
        try {
            suggestion = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .messages(conversationHistory)
                    .user(userText + "\n\n[기존 일정]\n" + scheduleContext + "\n\n[사용 가능한 카테고리]\n" + categoryContext)
                    .call()
                    .entity(AiScheduleSuggestion.class);
        } catch (Exception e) {
            // Claude API 타임아웃·레이트리밋·응답 파싱 실패 등 원인과 무관하게 BusinessException 으로 감싸
            // 기존 GlobalExceptionHandler 가 일관된 응답 포맷으로 처리하게 한다(AI_STRATEGY.md 6단계)
            throw new BusinessException(ErrorCode.AI_REQUEST_FAILED, e);
        }

        Set<Long> validCategoryIds = categories.stream().map(CategoryResponseDto::id).collect(Collectors.toSet());
        Long categoryId = suggestion.categoryId() != null && validCategoryIds.contains(suggestion.categoryId())
                ? suggestion.categoryId()
                : null;

        // 모델은 컨텍스트로 준 최근 2주~향후 2주 일정을 보고 다른 날짜(예: "이번 주 목요일")를 추천할 수도
        // 있는데, 실제로 등록될 일정은 항상 오늘 날짜여야 한다는 요구사항이 있어 시각(시:분)만 남기고
        // 날짜는 오늘로 강제한다 - reason 텍스트는 원래 추천 맥락(다른 요일 언급 등)을 그대로 담고 있을 수
        // 있지만, 실제로 등록되는 startAt/endAt은 항상 오늘 기준이다
        LocalDateTime startAt = toToday(parseDateTimeSafely(suggestion.startAt()));
        LocalDateTime endAt = toToday(parseDateTimeSafely(suggestion.endAt()));
        // 종료 시각이 시작 시각보다 앞서면(예: 23:00~00:30처럼 자정을 걸치는 시간대) 같은 날로 만들어버린
        // 탓에 순서가 뒤집힌 것이므로, 다음날로 하루 밀어 원래 지속 시간 관계를 유지한다
        if (startAt != null && endAt != null && !endAt.isAfter(startAt)) {
            endAt = endAt.plusDays(1);
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
                .suggestedTitle(suggestion.title())
                .suggestedContent(suggestion.content())
                .suggestedStartAt(startAt)
                .suggestedEndAt(endAt)
                .suggestedCategoryId(categoryId)
                .build());

        return new AiChatExchangeDto(AiChatMessageDto.from(userMessage), AiChatMessageDto.from(assistantMessage));
    }

    // 과거 ASSISTANT 턴을 LLM에 그대로 재현하기보다(구조화된 필드를 다시 JSON으로 만들어 넣는 건 과함),
    // 모델이 대화 맥락을 이해할 정도로만 요약해서 넣는다
    private Message toSpringAiMessage(AiChatMessage message) {
        if (message.getRole() == AiChatRole.USER) {
            return new UserMessage(message.getMessageText());
        }
        String summary = message.getSuggestedTitle() == null
                ? message.getMessageText()
                : "[추천: %s%s] %s".formatted(
                        message.getSuggestedTitle(),
                        message.getSuggestedStartAt() != null ? " (" + message.getSuggestedStartAt() + ")" : "",
                        message.getMessageText());
        return new AssistantMessage(summary);
    }

    private LocalDateTime toToday(LocalDateTime value) {
        return value == null ? null : value.toLocalTime().atDate(LocalDate.now());
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

    // 항상 요청자 본인의 일정만 컨텍스트로 쓴다 - ADMIN 이 호출하더라도 전체 유저 일정이 아니라 본인 일정
    // 기준으로 추천해야 하므로, ScheduleService.getSchedules 의 "USER 는 본인 id 로 강제" 규칙에 기대지 않고
    // 여기서 직접 requester.getId() 를 명시적으로 넘긴다
    private String buildScheduleContext(String requesterEmail, Long requesterId) {
        List<ScheduleResponseDto> schedules = scheduleService.getSchedules(requesterEmail, requesterId, null);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusWeeks(CONTEXT_WINDOW_WEEKS);
        LocalDateTime windowEnd = now.plusWeeks(CONTEXT_WINDOW_WEEKS);

        // 알림형(종료 시각 없음) 일정은 시작 시각을 종료 시각 대신 써서 윈도우 필터를 통과시킨다
        List<ScheduleResponseDto> windowed = schedules.stream()
                .filter(s -> {
                    LocalDateTime effectiveEnd = s.endAt() != null ? s.endAt() : s.startAt();
                    return !effectiveEnd.isBefore(windowStart) && !s.startAt().isAfter(windowEnd);
                })
                .sorted(Comparator.comparing(ScheduleResponseDto::startAt))
                .toList();

        if (windowed.isEmpty()) {
            return "(최근 2주~향후 2주 사이 등록된 일정 없음)";
        }

        return windowed.stream()
                .map(s -> s.endAt() != null
                        ? "- [%s] %s (%s ~ %s, 카테고리: %s)".formatted(
                                s.status(), s.title(), s.startAt(), s.endAt(), s.categoryName())
                        : "- [%s] %s (%s, 카테고리: %s)".formatted(
                                s.status(), s.title(), s.startAt(), s.categoryName()))
                .collect(Collectors.joining("\n"));
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
