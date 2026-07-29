package com.example.schedule_manager.domain.ai.service;

import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiRateLimiter - USER는 분당 AiRateLimiter.LIMIT_PER_MINUTE 회로 제한하고, ADMIN은 예외인지,
 * 그리고 Redis 장애 시 fail-open(요청 통과)하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AiRateLimiterTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AiRateLimiter aiRateLimiter;

    @BeforeEach
    void setUp() {
        aiRateLimiter = new AiRateLimiter(redisTemplate);
    }

    @Test
    @DisplayName("ADMIN은 Redis를 조회하지 않고 항상 통과한다")
    void admin_bypassesLimitWithoutTouchingRedis() {
        aiRateLimiter.checkLimit(1L, UserType.ADMIN);

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("USER의 첫 호출은 카운트를 1로 만들고 1분 TTL을 건다")
    void user_firstCall_setsExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ai-rate-limit:1")).thenReturn(1L);

        aiRateLimiter.checkLimit(1L, UserType.USER);

        verify(redisTemplate).expire("ai-rate-limit:1", Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("USER의 두 번째 이후 호출은 TTL을 다시 걸지 않는다(고정 윈도우 유지)")
    void user_subsequentCall_doesNotResetExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ai-rate-limit:1")).thenReturn(3L);

        aiRateLimiter.checkLimit(1L, UserType.USER);

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("정확히 분당 제한 횟수까지는 통과한다")
    void user_exactlyAtLimit_passes() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ai-rate-limit:1")).thenReturn((long) AiRateLimiter.LIMIT_PER_MINUTE);

        assertThatCode(() -> aiRateLimiter.checkLimit(1L, UserType.USER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("USER가 분당 제한 횟수를 초과하면 AI_RATE_LIMIT_EXCEEDED 예외를 던진다")
    void user_overLimit_throws() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ai-rate-limit:1")).thenReturn((long) AiRateLimiter.LIMIT_PER_MINUTE + 1);

        assertThatThrownBy(() -> aiRateLimiter.checkLimit(1L, UserType.USER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("Redis 장애 시 제한을 걸지 못해도 요청은 통과시킨다(fail-open)")
    void redisFailure_failsOpen() {
        when(redisTemplate.opsForValue()).thenThrow(new QueryTimeoutException("redis timeout"));

        assertThatCode(() -> aiRateLimiter.checkLimit(1L, UserType.USER)).doesNotThrowAnyException();
    }
}
