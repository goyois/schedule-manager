package com.example.schedule_manager.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

// Redis 장애 시 @Cacheable/@CacheEvict 가 예외를 그대로 던지면 캐시 조회 실패가 API 자체의 실패로 번진다
// (예: getSchedules() 는 DB 조회 로직에 도달하지도 못하고 RedisConnectionFailureException 으로 500)
// 이 핸들러는 캐시 read/write 오류를 삼키고 로그만 남겨서, 캐시 미스로 취급되어 원본 메서드(DB 조회)가 계속 실행되게 한다
@Slf4j
public class CacheFailSafeErrorHandler implements CacheErrorHandler {

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("캐시 조회 실패 - cache={}, key={}, 원본 로직으로 대체합니다.", cache.getName(), key, exception);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("캐시 저장 실패 - cache={}, key={}", cache.getName(), key, exception);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("캐시 삭제 실패 - cache={}, key={}", cache.getName(), key, exception);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("캐시 전체 삭제 실패 - cache={}", cache.getName(), exception);
    }
}
