package com.example.schedule_manager.global.security.config;

import com.example.schedule_manager.global.security.filter.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // JWT 를 사용하므로 CSRF 토큰 검증은 불필요 → 비활성화
                .csrf(AbstractHttpConfigurer::disable)

                // JWT 는 서버가 세션을 저장하지 않는 Stateless 방식 → 세션 생성 안 함
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 요청별 접근 권한 설정
                .authorizeHttpRequests(auth -> auth
                        // 로그인·로그아웃은 토큰 없이 접근 허용
                        .requestMatchers("/api/auth/**").permitAll()
                        // 회원가입만 토큰 없이 허용, 그 외 /api/users/** 는 인증 필요
                        .requestMatchers(HttpMethod.POST, "/api/users").permitAll()
                        // 정적 리소스·화면 라우트는 토큰 없이 접근 허용 (로그인 전 화면 자체는 볼 수 있어야 함)
                        .requestMatchers(
                                "/", "/login", "/signup", "/dashboard",
                                "/index.html", "/signup.html", "/dashboard.html",
                                "/css/**", "/js/**"
                        ).permitAll()
                        // 모니터링용 actuator 는 인증 없이 스크레이핑 가능해야 함
                        .requestMatchers("/actuator/**").permitAll()
                        // 그 외 모든 요청(일정/카테고리 API 등)은 유효한 토큰 필요
                        .anyRequest().authenticated()
                )

                // Spring Security 기본 로그인 필터 앞에 JWT 필터를 끼워 넣는다
                // → 요청이 들어오면 JWT 필터가 먼저 실행돼 토큰을 검증하고 SecurityContext 에 인증 정보를 저장
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    // 로그인 시 이메일·비밀번호를 검증하는 AuthenticationManager 를 빈으로 등록
    // AuthService 에서 직접 주입받아 사용한다
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // 비밀번호를 BCrypt 해시로 암호화하는 인코더 빈 등록
    // UserService 의 회원가입, CustomUserDetailsService 의 비밀번호 비교에 사용된다
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
