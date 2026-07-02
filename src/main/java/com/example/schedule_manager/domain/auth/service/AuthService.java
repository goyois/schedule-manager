package com.example.schedule_manager.domain.auth.service;

import com.example.schedule_manager.domain.auth.dto.LoginRequestDto;
import com.example.schedule_manager.domain.auth.dto.LoginResponseDto;
import com.example.schedule_manager.global.security.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, String> redisTemplate;

    public LoginResponseDto login(LoginRequestDto request) {
        // 1. 이메일·비밀번호로 인증 객체를 만들어 AuthenticationManager 에 넘긴다
        //    내부적으로 CustomUserDetailsService.loadUserByUsername() 을 호출해 DB 에서 유저를 조회하고
        //    입력된 비밀번호와 저장된 BCrypt 해시를 비교한다 → 불일치 시 예외 발생
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        // 2. 인증 성공 시 이메일을 subject 로 담은 JWT 액세스 토큰을 발급해 반환한다
        return new LoginResponseDto(jwtUtil.generateToken(request.email()));
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
