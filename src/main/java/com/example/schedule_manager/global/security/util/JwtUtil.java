package com.example.schedule_manager.global.security.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    // yml 에 설정한 서명용 비밀키 (최소 256bit = 32자 이상)
    @Value("${spring.jwt.secret}")
    private String secret;

    // 토큰 유효 시간 (ms 단위, yml 기본값 = 1시간)
    @Value("${spring.jwt.expiration}")
    private long expiration;

    // 로그인 성공 시 이메일을 subject 로 담아 JWT 를 생성한다
    public String generateToken(String email) {
        return Jwts.builder()
                .subject(email)               // 토큰 소유자 식별값
                .issuedAt(new Date())         // 발급 시각
                .expiration(new Date(System.currentTimeMillis() + expiration)) // 만료 시각
                .signWith(getSigningKey())     // 비밀키로 서명 → 위변조 방지
                .compact();
    }

    // 토큰에서 이메일(subject)을 꺼낸다 → 어떤 유저의 요청인지 식별할 때 사용
    public String extractEmail(String token) {
        return getClaims(token).getSubject();
    }

    // 로그아웃 시 토큰의 남은 유효 시간을 계산한다 → Redis TTL 에 사용
    public long getRemainingExpiration(String token) {
        return getClaims(token).getExpiration().getTime() - System.currentTimeMillis();
    }

    // 토큰의 서명과 만료 여부를 검증한다
    // 파싱 자체가 실패(위변조·만료)하면 예외가 발생하므로 catch 해서 false 반환
    public boolean isTokenValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    // 비밀키로 서명을 검증하면서 토큰 내부 데이터(Claims)를 파싱한다
    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey()) // 서명 검증
                .build()
                .parseSignedClaims(token)
                .getPayload();              // 실제 데이터(subject, 만료시각 등)
    }

    // yml 의 문자열 비밀키를 HMAC-SHA 알고리즘용 SecretKey 객체로 변환한다
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
