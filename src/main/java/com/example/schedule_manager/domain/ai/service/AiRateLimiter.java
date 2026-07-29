package com.example.schedule_manager.domain.ai.service;

import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

// AiService.sendMessage() 가 실제 Anthropic API를 호출하기 전에 거치는 유저별 분당 호출 횟수 제한.
// ADMIN은 제외하고 USER만 분당 AI_CHAT_LIMIT_PER_MINUTE 회로 제한한다. Redis INCR은 원자적이라
// 동시 요청이 몰려도 카운트가 유실되지 않는다 - 키가 없을 때 첫 INCR은 1을 반환하는데, 그 시점에만
// 1분 TTL을 걸어 고정 윈도우(fixed window)를 만든다
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRateLimiter {

    static final int LIMIT_PER_MINUTE = 5;
    private static final String KEY_PREFIX = "ai-rate-limit:";

    private final RedisTemplate<String, String> redisTemplate;

    // Redis 장애 시에도 AI 챗봇 자체가 막히면 안 되므로 fail-open 한다(JwtAuthenticationFilter의
    // 블랙리스트 확인, ScheduleService의 캐시 무효화와 같은 패턴) - 제한을 못 걸 뿐 요청은 통과시킨다
    public void checkLimit(Long userId, UserType userType) {
        if (userType == UserType.ADMIN) {
            return;
        }

        String key = KEY_PREFIX + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofMinutes(1));
            }
            if (count != null && count > LIMIT_PER_MINUTE) {
                throw new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED);
            }
        } catch (DataAccessException e) {
            log.warn("AI 요청 빈도 제한 확인 실패 - Redis 장애로 통과 처리, userId={}", userId, e);
        }
    }
}
