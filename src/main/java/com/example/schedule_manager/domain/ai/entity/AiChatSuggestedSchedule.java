package com.example.schedule_manager.domain.ai.entity;

import com.example.schedule_manager.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// AiChatMessage 하나가 SCHEDULE_RECOMMENDATION로 분류될 때, 한 번에 여러 일정을 제안할 수 있도록
// 제안 하나당 한 행씩 담는 자식 테이블. SCHEDULE_UPDATE/MANDALART_FILL은 여전히 AiChatMessage의
// 단일 suggested* 컬럼(과거부터 있던 필드)을 그대로 쓴다 - 대상이 항상 하나뿐이라 리스트가 필요 없다.
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "ai_chat_suggested_schedules")
public class AiChatSuggestedSchedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ai_chat_suggested_schedule_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_chat_message_id", nullable = false)
    private AiChatMessage message;

    // 같은 메시지 안에서 제안된 순서 - 프론트에 항상 모델이 답한 순서 그대로 보여주기 위함
    @Column(name = "item_order", nullable = false)
    private int itemOrder;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Long categoryId;

    // 이 항목이 실제로 등록/수정된 일정의 id - 아직이면 null. AiChatMessage.registeredScheduleId와 같은
    // 이유로 Schedule과 하드 FK로 묶지 않는다(그 일정이 나중에 삭제돼도 채팅 기록은 깨지지 않게)
    private Long registeredScheduleId;

    public void markRegistered(Long scheduleId) {
        this.registeredScheduleId = scheduleId;
    }
}
