package com.example.schedule_manager.domain.category.service;

import com.example.schedule_manager.domain.category.dto.CategoryResponseDto;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// CategoryService.getCategories() 를 같은 클래스 안에서 self-invocation 으로 호출하면 프록시를
// 거치지 않아 캐싱이 동작하지 않으므로, ScheduleCacheQueryService 와 같은 이유로 별도 빈으로 분리한다
@Service
@RequiredArgsConstructor
class CategoryCacheQueryService {

    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CategoryService.CATEGORY_CACHE, key = "#requesterId", unless = "#result.isEmpty()")
    public List<CategoryResponseDto> getCategories(Long requesterId) {
        return categoryRepository.findVisibleTo(requesterId).stream()
                .map(CategoryResponseDto::from)
                .toList();
    }
}
