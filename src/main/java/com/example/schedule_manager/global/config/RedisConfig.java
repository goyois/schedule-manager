package com.example.schedule_manager.global.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
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

import java.time.Duration;

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
    //
    // #v3
    // GenericJackson2JsonRedisSerializer(ObjectMapper) 생성자는 기본(no-arg) 생성자와 달리
    // 넘겨받은 ObjectMapper 에 default typing 을 활성화해주지 않는다. 그 결과 캐시에 쓰는 JSON 에
    // "@class" 타입 정보가 전혀 남지 않아서, 캐시 히트 시 List<ScheduleResponseDto> 같은 값이
    // record 가 아니라 LinkedHashMap 리스트로 역직렬화된다 — ScheduleController 처럼 그대로
    // 재직렬화만 하는 코드는 겉으로 문제가 없어 보이지만, AiService 처럼 DTO 필드에 타입으로
    // 접근하는 코드는 ClassCastException 이 터진다.
    //
    // [시행착오 1] 캐시되는 DTO(ScheduleResponseDto 등)에만 @JsonTypeInfo 를 붙여 ObjectMapper 는
    // 건드리지 않는 방법을 시도했으나 실패했다 — Spring 의 Cache.put(Object, Object) 은 값을 항상
    // Object 로 저장하므로, 캐시에 실제로 들어가는 값(예: List<ScheduleResponseDto>)의 "정적 타입"은
    // 소실된 상태다. Jackson 은 리스트처럼 정적 타입이 소실된 컨테이너의 원소를 직렬화할 때, 그
    // 원소 클래스 자체에 붙은 @JsonTypeInfo 를 참고하지 않는다(원소 클래스에 대한 직렬화 자체는
    // 문제없이 되지만 타입 태그 없이 됨) — 즉 List 안에 담긴 record 는 여전히 태그가 안 남아
    // LinkedHashMap 으로 역직렬화된다. @JsonTypeInfo 는 필드/프로퍼티가 "이 타입으로 선언돼 있다"는
    // 정적 문맥이 있을 때만 활성화되는데, Object 로 소거된 컨테이너 원소에는 그 문맥이 없다.
    //
    // [시행착오 2] ObjectMapper 에 activateDefaultTyping(EVERYTHING) 을 걸었더니 컨테이너(List)
    // 자체와 그 안의 record 원소 모두 태그가 남아 역직렬화가 성공했다 — EVERYTHING 은 유일하게
    // "정적 타입이 Object 인 값"의 런타임 타입까지 전부 태깅하는 모드라서, 정적 타입 정보가 없는
    // 캐시 값에도 강제로 타입을 남긴다. 그런데 이 모드는 Long/String 같은 필드 값 하나하나까지도
    // "@class":"java.lang.Long" 식으로 태깅해버려서, 이 프로젝트 패키지와 java.util 만 허용하던
    // PolymorphicTypeValidator 가 그 타입들을 전부 거부해 캐시 조회 자체가 항상 실패하는 회귀를
    // 유발했다(이전 실패의 원인).
    //
    // → 최종: EVERYTHING 은 유지하되, PolymorphicTypeValidator 화이트리스트에 이 프로젝트가 실제로
    // 캐싱하는 DTO 필드 타입인 java.lang(Long/String 등 boxed 타입)과 java.time(LocalDateTime)을
    // 추가로 허용한다. String 은 Jackson 이 애초에 태깅 대상에서 제외하므로 실제로 태그가 붙는 건
    // Long 정도지만, 화이트리스트는 이 프로젝트가 캐싱할 만한 다른 boxed 타입(Integer, Boolean 등)도
    // 함께 커버해둔다.
    private GenericJackson2JsonRedisSerializer redisJsonSerializer() {
        BasicPolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.example.schedule_manager.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.time.")
                .build();

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .activateDefaultTyping(typeValidator, ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
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
                // ScheduleService.evictScheduleCacheForUser() 의 SCAN 패턴 evict 가 못 걷어내는 경로가 있다
                // (예: ADMIN 이 userId 없이 전체 조회한 캐시 키, 카테고리 이름 변경 시 그 카테고리를 참조하는
                // 스케줄 캐시는 evict되지 않음) — 이런 경우에도 무한정 stale 로 남지 않도록 기본 TTL을 건다.
                // evict 가 정상 동작하는 일반적인 경우엔 그 전에 이미 지워지므로 체감되지 않고, evict 를
                // 놓친 경우에만 "영원히 stale" 대신 "최대 5분 후 자연 회복"으로 바뀐다
                .entryTtl(Duration.ofMinutes(5))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(redisJsonSerializer())); // #v2: new GenericJackson2JsonRedisSerializer() 에서 교체

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
