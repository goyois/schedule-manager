package com.example.schedule_manager.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

// Redis 를 캐시 저장소로 사용하기 위한 설정
// @EnableCaching 으로 서비스 계층의 @Cacheable/@CacheEvict(예: ScheduleService.getSchedules) 를 활성화하고,
// 아래 CacheManager 빈이 그 애노테이션들이 실제로 사용할 Redis 연동 방식을 정의한다
@Configuration
@EnableCaching
public class RedisConfig implements CachingConfigurer {

    // Redis 가 죽어도 @Cacheable/@CacheEvict 가 예외를 던지지 않고 캐시 미스로 취급하도록 하는 핸들러
    // (CacheFailSafeErrorHandler 참고: 없으면 기본 SimpleCacheErrorHandler 가 예외를 그대로 던져 DB 폴백이 불가능하다)
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheFailSafeErrorHandler();
    }

    // #v2
    // GenericJackson2JsonRedisSerializer() 기본 생성자는 내부적으로 새 ObjectMapper 를 만드는데,
    // 여기에는 JavaTimeModule 이 없어 LocalDateTime 필드(ScheduleResponseDto.startAt 등)를 캐싱하려는 순간
    // InvalidDefinitionException 이 터진다 (캐시 미스 시 응답을 Redis 에 쓰는 과정에서 발생하므로 API 자체가 500 이 된다)
    // → JavaTimeModule 을 등록한 ObjectMapper 를 직접 만들어 넘겨준다
    private GenericJackson2JsonRedisSerializer redisJsonSerializer() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }

    // 캐시 애노테이션 없이 Redis 를 직접 다뤄야 할 때(향후 필요 시) 사용할 범용 템플릿
    // 키는 사람이 읽기 쉬운 문자열로, 값은 JSON 으로 직렬화한다
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(redisJsonSerializer()); // #v2: new GenericJackson2JsonRedisSerializer() 에서 교체
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(redisJsonSerializer()); // #v2: new GenericJackson2JsonRedisSerializer() 에서 교체
        return template;
    }

    // @Cacheable/@CacheEvict 가 실제로 사용하는 CacheManager
    // 키/값 직렬화 방식을 위 redisJsonSerializer() 로 통일해, redis-cli 로 봐도 값이 JSON 으로 그대로 보이게 한다
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(redisJsonSerializer())); // #v2: new GenericJackson2JsonRedisSerializer() 에서 교체

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
