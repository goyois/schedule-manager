package com.example.schedule_manager.domain.ai.service;

import com.example.schedule_manager.domain.ai.dto.ScheduleSuggestResponseDto;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.service.ScheduleService;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiService {

    // 토큰 비용 때문에 전체 기간을 다 넣지 않고 최근 2주 + 향후 2주로 컨텍스트 범위를 제한한다(AI_STRATEGY.md 3단계)
    private static final int CONTEXT_WINDOW_WEEKS = 2;

    private final ChatClient chatClient;
    private final ScheduleService scheduleService;
    private final UserRepository userRepository;

    public ScheduleSuggestResponseDto suggestSchedule(String requesterEmail, String userPrompt) {
        User requester = findUserByEmail(requesterEmail);
        String context = buildScheduleContext(requesterEmail, requester.getId());

        String suggestion;
        try {
            suggestion = chatClient.prompt()
                    .system("당신은 일정 관리 도우미입니다. 사용자의 기존 일정을 참고해 실행 가능한 일정을 추천하세요.")
                    .user(userPrompt + "\n\n[기존 일정]\n" + context)
                    .call()
                    .content();
        } catch (Exception e) {
            // Claude API 타임아웃·레이트리밋 등 실패 시 원인과 무관하게 BusinessException 으로 감싸
            // 기존 GlobalExceptionHandler 가 일관된 응답 포맷으로 처리하게 한다(AI_STRATEGY.md 6단계)
            throw new BusinessException(ErrorCode.AI_REQUEST_FAILED, e);
        }
        return new ScheduleSuggestResponseDto(suggestion);
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
