package com.example.schedule_manager.global.security.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-256-bits-long-for-hmac";

    private final JwtUtil jwtUtil = new JwtUtil();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 1_209_600_000L);
    }

    @Test
    @DisplayName("액세스 토큰을 생성하면 subject 에 이메일이 담기고 access 토큰으로 판별된다")
    void generateToken_createsAccessToken() {
        String token = jwtUtil.generateToken("tester@example.com");

        assertThat(jwtUtil.isTokenValid(token)).isTrue();
        assertThat(jwtUtil.extractEmail(token)).isEqualTo("tester@example.com");
        assertThat(jwtUtil.isAccessToken(token)).isTrue();
        assertThat(jwtUtil.isRefreshToken(token)).isFalse();
    }

    @Test
    @DisplayName("리프레시 토큰을 생성하면 subject 에 이메일이 담기고 refresh 토큰으로 판별된다")
    void generateRefreshToken_createsRefreshToken() {
        String token = jwtUtil.generateRefreshToken("tester@example.com");

        assertThat(jwtUtil.isTokenValid(token)).isTrue();
        assertThat(jwtUtil.extractEmail(token)).isEqualTo("tester@example.com");
        assertThat(jwtUtil.isRefreshToken(token)).isTrue();
        assertThat(jwtUtil.isAccessToken(token)).isFalse();
    }

    @Test
    @DisplayName("리프레시 토큰은 액세스 토큰보다 남은 유효시간이 길다")
    void refreshToken_hasLongerExpirationThanAccessToken() {
        String accessToken = jwtUtil.generateToken("tester@example.com");
        String refreshToken = jwtUtil.generateRefreshToken("tester@example.com");

        assertThat(jwtUtil.getRemainingExpiration(refreshToken))
                .isGreaterThan(jwtUtil.getRemainingExpiration(accessToken));
    }

    @Test
    @DisplayName("서명 키가 다른 토큰은 유효하지 않다")
    void isTokenValid_wrongSignature_returnsFalse() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "other-secret-key-must-be-at-least-256-bits-long!!".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .subject("tester@example.com")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(otherKey)
                .compact();

        assertThat(jwtUtil.isTokenValid(forged)).isFalse();
    }

    @Test
    @DisplayName("만료된 토큰은 유효하지 않다")
    void isTokenValid_expiredToken_returnsFalse() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject("tester@example.com")
                .expiration(new Date(System.currentTimeMillis() - 1_000))
                .signWith(key)
                .compact();

        assertThat(jwtUtil.isTokenValid(expired)).isFalse();
    }

    @Test
    @DisplayName("형식이 잘못된 토큰은 유효하지 않다")
    void isTokenValid_malformedToken_returnsFalse() {
        assertThat(jwtUtil.isTokenValid("not-a-jwt")).isFalse();
    }
}
