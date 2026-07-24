package com.example.schedule_manager.global.security.config;

import com.example.schedule_manager.global.response.ApiResponse;
import com.example.schedule_manager.global.security.filter.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // JWT 를 사용하므로 CSRF 토큰 검증은 불필요 → 비활성화
                .csrf(AbstractHttpConfigurer::disable)

                // JWT 는 서버가 세션을 저장하지 않는 Stateless 방식 → 세션 생성 안 함
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // JwtAuthenticationFilter 를 통과하기 전에 걸리는 인증/인가 실패(토큰 없음·무효 토큰으로
                // 인증 필요 API 접근)는 컨트롤러/GlobalExceptionHandler 를 거치지 않고 필터 체인에서 바로
                // 응답이 나가므로, 여기서도 ApiResponse 포맷과 동일한 JSON 을 내려줘야 프론트가 상태코드별로
                // 일관되게 처리할 수 있다
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(jsonAuthenticationEntryPoint())
                        .accessDeniedHandler(jsonAccessDeniedHandler()))

                // 요청별 접근 권한 설정
                .authorizeHttpRequests(auth -> auth
                        // 로그인·로그아웃은 토큰 없이 접근 허용
                        .requestMatchers("/api/auth/**").permitAll()
                        // 회원가입만 토큰 없이 허용, 그 외 /api/users/** 는 인증 필요
                        .requestMatchers(HttpMethod.POST, "/api/users").permitAll()
                        // 정적 리소스·화면 라우트는 토큰 없이 접근 허용 (로그인 전 화면 자체는 볼 수 있어야 함)
                        .requestMatchers(
                                "/", "/login", "/signup", "/dashboard", "/mandalart",
                                "/index.html", "/signup.html", "/dashboard.html", "/mandalart.html",
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

    // 인증 필요 API 에 토큰 없이/무효 토큰으로 접근 → 401
    private AuthenticationEntryPoint jsonAuthenticationEntryPoint() {
        return (request, response, authException) ->
                writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
    }

    // 인증은 됐지만 권한이 없는 접근 → 403 (현재 앱은 메서드 보안을 쓰지 않아 실제로는 거의 타지 않지만,
    // 필터 체인 레벨에서도 GlobalExceptionHandler 와 동일한 JSON 포맷을 보장하기 위해 등록해둔다)
    private AccessDeniedHandler jsonAccessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                writeErrorResponse(response, HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");
    }

    private void writeErrorResponse(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(status.value(), message)));
    }
}
