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

    public void update(String username, String email, String encodedPassword) {
        this.username = username;
        this.email = email;
        this.password = encodedPassword;
    }

    public void updateAutoStatusMode(boolean autoStatusMode) {
        this.autoStatusMode = autoStatusMode;
    }
}
