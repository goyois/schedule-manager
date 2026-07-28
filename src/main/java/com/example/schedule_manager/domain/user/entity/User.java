package com.example.schedule_manager.domain.user.entity;

import com.example.schedule_manager.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    private String username;
    private String password;
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type")
    private UserType userType;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider")
    @Builder.Default
    private AuthProvider authProvider = AuthProvider.LOCAL;

    // 켜면 ScheduleService.autoTransitionScheduleStatuses()(@Scheduled)가 이 유저의 일정을
    // 시작/종료 시각에 맞춰 서버에서 자동으로 상태 전환한다. 브라우저 localStorage에만 있던 값을
    // 서버로 옮긴 이유는, 이 스케줄러가 탭이 닫혀 있어도(로그아웃 상태에서도) 동작해야 하기 때문
    @Column(name = "auto_status_mode", nullable = false)
    @Builder.Default
    private boolean autoStatusMode = false;

    // 켜면 AiService 의 추천 결과에 title/startAt/categoryId 가 모두 갖춰졌을 때 프론트의
    // "자동 등록" 버튼이 노출된다(꺼져있으면 항상 "수동 등록"으로 검토 후 저장해야 한다). 서버가 직접
    // 일정을 생성하는 건 아니고 어디까지나 프론트의 버튼 노출 여부만 결정한다는 점에서 autoStatusMode
    // 와는 다르다 - 그래도 "로그인한 유저별 설정"이라는 성격이 같아 같은 방식(DB 컬럼)으로 둔다
    @Column(name = "ai_auto_register_enabled", nullable = false)
    @Builder.Default
    private boolean aiAutoRegisterEnabled = false;

    public void update(String username, String email, String encodedPassword) {
        this.username = username;
        this.email = email;
        this.password = encodedPassword;
    }

    public void updateAutoStatusMode(boolean autoStatusMode) {
        this.autoStatusMode = autoStatusMode;
    }

    public void updateAiAutoRegisterEnabled(boolean aiAutoRegisterEnabled) {
        this.aiAutoRegisterEnabled = aiAutoRegisterEnabled;
    }
}
