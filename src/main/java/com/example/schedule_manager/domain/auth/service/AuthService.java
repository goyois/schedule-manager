package com.example.schedule_manager.domain.auth.service;

import com.example.schedule_manager.domain.auth.dto.GoogleLoginRequestDto;
import com.example.schedule_manager.domain.auth.dto.LoginRequestDto;
import com.example.schedule_manager.domain.auth.dto.LoginResponseDto;
import com.example.schedule_manager.domain.user.entity.AuthProvider;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.security.util.JwtUtil;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.GeneralSecurityException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, String> redisTemplate;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public LoginResponseDto login(LoginRequestDto request) {
        // 1. 이메일·비밀번호로 인증 객체를 만들어 AuthenticationManager 에 넘긴다
        //    내부적으로 CustomUserDetailsService.loadUserByUsername() 을 호출해 DB 에서 유저를 조회하고
        //    입력된 비밀번호와 저장된 BCrypt 해시를 비교한다 → 불일치 시 예외 발생
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        // 2. 인증 성공 시 이메일을 subject 로 담은 JWT 액세스 토큰을 발급해 반환한다
        return new LoginResponseDto(jwtUtil.generateToken(request.email()));
    }

    @Transactional
    public LoginResponseDto loginWithGoogle(GoogleLoginRequestDto request) {
        // 1. 프론트(Google Identity Services)가 넘겨준 ID 토큰을 구글 공개키로 검증한다
        //    서명/발급자/audience(clientId)/만료를 모두 확인하며, 위조되었거나 우리 앱용이 아니면 null 을 돌려준다
        GoogleIdToken idToken = verify(request.idToken());
        GoogleIdToken.Payload payload = idToken.getPayload();

        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new IllegalArgumentException("이메일이 인증되지 않은 구글 계정입니다.");
        }
        String email = payload.getEmail();

        // 2. 이미 가입된 이메일이면(로컬 가입이었더라도) 그 계정으로 로그인시키고,
        //    없으면 구글 프로필 정보로 새 계정을 만든다 — 구글이 이메일 소유를 이미 검증했으므로 안전하다
        User user = userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(User.builder()
                        .username(StringUtils.hasText((String) payload.get("name")) ? (String) payload.get("name") : email)
                        .email(email)
                        // 이 계정으로는 비밀번호 로그인을 쓰지 않지만, UserDetails(비밀번호 not-null)를 만족시키기 위해
                        // 아무도 알 수 없는 랜덤 값을 인코딩해 채워둔다
                        .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                        .userType(UserType.USER)
                        .authProvider(AuthProvider.GOOGLE)
                        .build()));

        return new LoginResponseDto(jwtUtil.generateToken(user.getEmail()));
    }

    private GoogleIdToken verify(String rawIdToken) {
        GoogleIdToken idToken;
        try {
            // GoogleIdToken.parse() 는 형식이 아예 JWT 가 아닌 값(점 3개짜리 구조가 아닌 문자열 등)을 받으면
            // 서명 검증까지 가지도 않고 IllegalArgumentException 을 바로 던진다 — 서명/발급자 실패(null 리턴)와 함께 묶어서 처리
            idToken = googleIdTokenVerifier.verify(rawIdToken);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("유효하지 않은 구글 로그인 토큰입니다.", e);
        }
        if (idToken == null) {
            throw new IllegalArgumentException("유효하지 않은 구글 로그인 토큰입니다.");
        }
        return idToken;
    }

    public void logout(String token) {
        // 1. 토큰의 남은 유효 시간을 계산한다
        long remainingMs = jwtUtil.getRemainingExpiration(token);

        // 2. 아직 만료되지 않은 토큰만 블랙리스트에 등록한다
        //    Redis 에 "blacklist:{token}" 키를 남은 시간만큼만 유지 → 만료되면 자동 삭제
        //    JwtAuthenticationFilter 에서 이 키를 확인해 해당 토큰으로 인증을 거부한다
        if (remainingMs > 0) redisTemplate.opsForValue().set("blacklist:" + token, "logout", remainingMs, TimeUnit.MILLISECONDS);

    }
}
