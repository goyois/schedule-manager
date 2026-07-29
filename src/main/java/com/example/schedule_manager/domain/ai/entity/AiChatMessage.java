package com.example.schedule_manager.domain.ai.entity;

import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "ai_chat_messages")
public class AiChatMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ai_chat_message_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiChatRole role;

    // USER: 사용자가 입력한 원문 프롬프트 / ASSISTANT: AI가 준 추천 이유(reason) 텍스트
    @Column(name = "message_text", columnDefinition = "TEXT")
    private String messageText;

    // 아래 필드들은 ASSISTANT 메시지에만 값이 있다(USER 메시지는 전부 null) - AiScheduleSuggestion 구조화 응답을 그대로 옮겨 담는다.
    // category는 프론트가 등록/수정 UI를 보여줄지, 보여준다면 어느 쪽인지 결정하는 값 - GENERAL이면
    // suggestedTitle 등 나머지 필드는 항상 null이다(AiService가 저장 전에 강제로 비운다)
    @Enumerated(EnumType.STRING)
    private AiResponseCategory category;

    // SCHEDULE_UPDATE일 때만 값이 있다 - 이 제안이 어느 기존 일정을 바꾸자는 것인지
    private Long targetScheduleId;

    private String suggestedTitle;

    @Column(columnDefinition = "TEXT")
    private String suggestedContent;

    private LocalDateTime suggestedStartAt;
    private LocalDateTime suggestedEndAt;
    private Long suggestedCategoryId;

    // 이 추천/수정 제안이 실제로 반영된 일정의 id - 아직 반영 전이면 null. SCHEDULE_RECOMMENDATION이면
    // 새로 만들어진 일정의 id, SCHEDULE_UPDATE면 수정된 기존 일정의 id(=targetScheduleId와 같은 값)가 된다.
    // Schedule과 하드 FK로 묶지 않는다(나중에 그 일정이 삭제돼도 채팅 기록 자체는 깨지지 않게 하기 위해)
    private Long registeredScheduleId;

    public void markRegistered(Long scheduleId) {
        this.registeredScheduleId = scheduleId;
    }
}
