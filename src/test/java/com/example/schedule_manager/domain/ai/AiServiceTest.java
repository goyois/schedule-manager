package com.example.schedule_manager.domain.ai;

import com.example.schedule_manager.domain.ai.dto.ScheduleSuggestResponseDto;
import com.example.schedule_manager.domain.ai.service.AiService;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

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
    private UserRepository userRepository;

    @InjectMocks
    private AiService aiService;

    private User user(Long id) {
        return User.builder().id(id).username("tester").email("tester@example.com").userType(UserType.USER).build();
    }

    private void stubChatClient(String responseText) {
        when(chatClient.prompt()).thenReturn(chatClientRequest);
        when(chatClientRequest.system(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.user(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(responseText);
    }

    @Test
    @DisplayName("일정 추천 성공 - 최근 2주~향후 2주 밖의 일정은 프롬프트 컨텍스트에서 제외된다")
    void suggestSchedule_success_buildsContextFromRecentSchedulesOnly() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));

        LocalDateTime now = LocalDateTime.now();
        ScheduleResponseDto inWindow = new ScheduleResponseDto(
                1L, "팀 회의", "주간 회의", now.plusDays(1), now.plusDays(1).plusHours(1),
                ScheduleStatus.PENDING, "tester", "업무");
        ScheduleResponseDto outOfWindow = new ScheduleResponseDto(
                2L, "먼 미래 일정", "내용", now.plusWeeks(5), now.plusWeeks(5).plusHours(1),
                ScheduleStatus.PENDING, "tester", "업무");
        when(scheduleService.getSchedules("tester@example.com", 1L, null))
                .thenReturn(List.of(inWindow, outOfWindow));

        stubChatClient("추천 결과 텍스트");

        ScheduleSuggestResponseDto response = aiService.suggestSchedule("tester@example.com", "이번 주 운동 일정 추천해줘");

        assertThat(response.suggestion()).isEqualTo("추천 결과 텍스트");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientRequest).user(userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue())
                .contains("팀 회의")
                .doesNotContain("먼 미래 일정");
    }

    @Test
    @DisplayName("일정 추천 실패 - ChatClient 호출이 실패하면 AI_REQUEST_FAILED 로 감싼다")
    void suggestSchedule_chatClientThrows_wrapsAsBusinessException() {
        User requester = user(1L);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(scheduleService.getSchedules("tester@example.com", 1L, null)).thenReturn(List.of());

        when(chatClient.prompt()).thenReturn(chatClientRequest);
        when(chatClientRequest.system(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.user(anyString())).thenReturn(chatClientRequest);
        when(chatClientRequest.call()).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> aiService.suggestSchedule("tester@example.com", "추천해줘"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_REQUEST_FAILED);
    }

    @Test
    @DisplayName("일정 추천 실패 - 존재하지 않는 유저면 예외가 발생한다")
    void suggestSchedule_userNotFound_throws() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> aiService.suggestSchedule("ghost@example.com", "추천해줘"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}
