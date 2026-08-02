package com.example.schedule_manager.global.config;

import com.example.schedule_manager.domain.category.dto.CategoryResponseDto;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [fix-10] 의 activateDefaultTyping(EVERYTHING) 이 java.lang.Long 역직렬화를 PolymorphicTypeValidator
 * 가 거부해 캐시 조회 자체가 항상 실패하는 회귀를 유발했던 문제를, RedisConfig#redisJsonSerializer 의
 * 화이트리스트에 java.lang/java.time 을 추가하는 방식으로 고쳤다(중간에 DTO에 @JsonTypeInfo 를 직접
 * 붙이는 방식도 시도했으나, Spring Cache 가 값을 항상 Object 로 저장해 정적 타입이 소실되는 List 컨테이너
 * 안에서는 원소별 타입 태그가 전혀 남지 않아 동작하지 않았다). 이 테스트는 캐시 히트 시
 * List<ScheduleResponseDto>/List<CategoryResponseDto> 가 LinkedHashMap 이 아니라 원래 DTO 타입으로
 * 역직렬화되는지, 그리고 Long 필드(예: id)가 문제없이 캐시를 왕복하는지 검증한다.
 */
@SpringBootTest
class RedisConfigTest {

    @Autowired
    private CacheManager cacheManager;

    @AfterEach
    void tearDown() {
        cacheManager.getCache("schedules").clear();
        cacheManager.getCache("categories").clear();
    }

    @Test
    @DisplayName("schedules 캐시 히트 시 List<ScheduleResponseDto> 가 LinkedHashMap 이 아니라 record 그대로 역직렬화된다")
    void scheduleResponseDtoSurvivesCacheRoundTrip() {
        Cache cache = cacheManager.getCache("schedules");
        assertThat(cache).isNotNull();

        LocalDateTime now = LocalDateTime.now().withNano(0);
        ScheduleResponseDto dto = new ScheduleResponseDto(
                1L, "title", "content", now, now.plusHours(1),
                ScheduleStatus.PENDING, "username", "categoryName", now);

        cache.put("redis-config-test-key", List.of(dto));

        Object cached = cache.get("redis-config-test-key").get();

        assertThat(cached).isInstanceOf(List.class);
        List<?> cachedList = (List<?>) cached;
        assertThat(cachedList).hasSize(1);
        assertThat(cachedList.get(0)).isInstanceOf(ScheduleResponseDto.class);

        ScheduleResponseDto roundTripped = (ScheduleResponseDto) cachedList.get(0);
        assertThat(roundTripped.id()).isEqualTo(1L);
        assertThat(roundTripped.title()).isEqualTo("title");
        assertThat(roundTripped.startAt()).isEqualTo(now);
        assertThat(roundTripped.status()).isEqualTo(ScheduleStatus.PENDING);
    }

    @Test
    @DisplayName("categories 캐시 히트 시 List<CategoryResponseDto> 가 LinkedHashMap 이 아니라 record 그대로 역직렬화된다")
    void categoryResponseDtoSurvivesCacheRoundTrip() {
        Cache cache = cacheManager.getCache("categories");
        assertThat(cache).isNotNull();

        CategoryResponseDto dto = new CategoryResponseDto(1L, "category-name");

        cache.put("redis-config-test-key", List.of(dto));

        Object cached = cache.get("redis-config-test-key").get();

        assertThat(cached).isInstanceOf(List.class);
        List<?> cachedList = (List<?>) cached;
        assertThat(cachedList).hasSize(1);
        assertThat(cachedList.get(0)).isInstanceOf(CategoryResponseDto.class);

        CategoryResponseDto roundTripped = (CategoryResponseDto) cachedList.get(0);
        assertThat(roundTripped.id()).isEqualTo(1L);
        assertThat(roundTripped.name()).isEqualTo("category-name");
    }
}
