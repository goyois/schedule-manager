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
import java.util.ArrayList;
import java.util.List;

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

    // MANDALART_FILL일 때만 값이 있다 - 어느 만다라트 보드를 채웠는지(이미 채우기가 끝난 뒤의 기록용 -
    // AiService가 이 카테고리를 감지한 시점에 MandalartAiService를 직접 호출해 그 자리에서 채운다)
    private Long targetMandalartBoardId;

    private String suggestedTitle;

    @Column(columnDefinition = "TEXT")
    private String suggestedContent;

    private LocalDateTime suggestedStartAt;
    private LocalDateTime suggestedEndAt;
    private Long suggestedCategoryId;

    // 이 추천/수정 제안이 실제로 반영된 일정의 id - 아직 반영 전이면 null. SCHEDULE_UPDATE면 수정된 기존
    // 일정의 id(=targetScheduleId와 같은 값). SCHEDULE_RECOMMENDATION은 더 이상 이 필드를 쓰지 않고(위
    // suggested* 필드들과 함께 비어 있다) suggestedSchedules 각 항목의 registeredScheduleId를 대신 쓴다 -
    // 다만 이 스키마 변경 이전에 저장된 옛 SCHEDULE_RECOMMENDATION 행은 suggestedSchedules가 비어 있는
    // 채로 이 필드에 값이 남아 있을 수 있다(프론트가 그 경우엔 예전 방식으로 표시하도록 하위호환 처리).
    // Schedule과 하드 FK로 묶지 않는다(나중에 그 일정이 삭제돼도 채팅 기록 자체는 깨지지 않게 하기 위해)
    private Long registeredScheduleId;

    // SCHEDULE_RECOMMENDATION일 때만 채워진다 - 한 번에 여러 일정을 제안할 수 있어 항목마다 하나씩 담는다.
    // 등록 순서 그대로 보여주기 위해 itemOrder로 정렬한다. cascade로 메시지와 생명주기를 같이한다
    // (메시지 저장 시 함께 저장, 대화 초기화로 메시지가 지워질 때 함께 지워짐).
    @Builder.Default
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("itemOrder ASC")
    private List<AiChatSuggestedSchedule> suggestedSchedules = new ArrayList<>();

    public void markRegistered(Long scheduleId) {
        this.registeredScheduleId = scheduleId;
    }

    public void addSuggestedSchedule(AiChatSuggestedSchedule item) {
        suggestedSchedules.add(item);
    }
}
