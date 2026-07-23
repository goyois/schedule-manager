package com.example.schedule_manager.domain.category.service;

import com.example.schedule_manager.domain.category.dto.CategoryRequestDto;
import com.example.schedule_manager.domain.category.dto.CategoryResponseDto;
import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.schedule.repository.ScheduleRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CategoryService {

    // CategoryCacheQueryService.getCategories() 의 @Cacheable 과 evictCategoryCache() 가 모두
    // 이 이름을 공유해야 무효화가 실제로 캐싱된 항목에 적용된다
    static final String CATEGORY_CACHE = "categories";

    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final ScheduleRepository scheduleRepository;
    private final CategoryCacheQueryService categoryCacheQueryService;
    private final CacheManager cacheManager;

    public CategoryResponseDto createCategory(String requesterEmail, CategoryRequestDto request) {
        User requester = findUserByEmail(requesterEmail);
        if (categoryRepository.existsVisibleDuplicateName(request.name(), requester.getId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_CATEGORY);
        }
        Category category = Category.builder()
                .name(request.name())
                .user(requester)
                .build();
        CategoryResponseDto response = CategoryResponseDto.from(categoryRepository.save(category));
        evictCategoryCache(requester);
        return response;
    }

    // 다른 USER 가 만든 비공개 카테고리는 요청자에게 존재 자체가 드러나면 안 되므로, 보이지 않는 카테고리는
    // CATEGORY_NOT_FOUND 와 동일하게 처리한다(ScheduleService.getSchedule 의 소유권 검사와 같은 취지)
    @Transactional(readOnly = true)
    public CategoryResponseDto getCategory(String requesterEmail, Long id) {
        User requester = findUserByEmail(requesterEmail);
        Category category = findCategory(id);
        if (!isVisibleTo(category, requester)) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        return CategoryResponseDto.from(category);
    }

    // 목록도 같은 기준으로 필터링한다: 소유자가 없는 레거시 카테고리, ADMIN 이 만든 기본 카테고리(전체 공개),
    // 본인이 만든 카테고리만 보인다 — 다른 USER 가 만든 카테고리는 그 유저 전용이라 보이지 않는다
    @Transactional(readOnly = true)
    public List<CategoryResponseDto> getCategories(String requesterEmail) {
        User requester = findUserByEmail(requesterEmail);
        return categoryCacheQueryService.getCategories(requester.getId());
    }

    private boolean isVisibleTo(Category category, User requester) {
        User owner = category.getUser();
        return owner == null || owner.getUserType() == UserType.ADMIN || owner.getId().equals(requester.getId());
    }

    private boolean isAdminOwned(Category category) {
        return category.getUser() != null && category.getUser().getUserType() == UserType.ADMIN;
    }

    // 다른 USER 의 비공개 카테고리는 애초에 보이지 않아야 하므로, 수정/삭제 요청도 조회와 같은 기준으로
    // 먼저 걸러 CATEGORY_NOT_FOUND 로 응답한다(무엇이 존재하는지조차 드러내지 않는다).
    // ADMIN 이 만든 기본 카테고리, 소유자 없는 레거시 카테고리, 본인 카테고리는 여기서 걸리지 않는다
    private void assertVisible(Category category, User requester) {
        if (!isVisibleTo(category, requester)) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
    }

    public CategoryResponseDto updateCategory(String requesterEmail, Long id, CategoryRequestDto request) {
        User requester = findUserByEmail(requesterEmail);
        Category category = findCategory(id);
        assertVisible(category, requester);
        if (isAdminOwned(category)) {
            throw new BusinessException(ErrorCode.DEFAULT_CATEGORY_UPDATE_FORBIDDEN);
        }
        category.update(request.name());
        // 여기 도달했다면 소유자는 null(레거시, 전체 공개) 이거나 requester 본인뿐이다(ADMIN 소유는 위에서 걸러짐)
        evictCategoryCache(category.getUser());
        return CategoryResponseDto.from(category);
    }

    public void deleteCategory(String requesterEmail, Long id) {
        User requester = findUserByEmail(requesterEmail);
        Category category = findCategory(id);
        assertVisible(category, requester);
        // ADMIN 이 만든 기본 설정 카테고리뿐 아니라, 아직 이 카테고리를 쓰는 일정이 남아있는 경우도 삭제를 막는다 —
        // 그대로 두면 categories.category_id 를 참조하는 schedules FK 제약에 걸려 DB 예외가 그대로 500 으로 새어나간다
        boolean hasSchedules = scheduleRepository.existsByCategoryId(id);
        if (isAdminOwned(category) || hasSchedules) {
            throw new BusinessException(ErrorCode.DEFAULT_CATEGORY_DELETE_FORBIDDEN);
        }
        User owner = category.getUser();
        categoryRepository.delete(category);
        evictCategoryCache(owner);
    }

    // 카테고리 목록 캐시는 요청자 id 를 키로 캐싱된다(CategoryCacheQueryService 참고). 소유자가 없거나(레거시)
    // ADMIN 소유(기본 카테고리)면 그 카테고리는 모든 유저의 목록에 나타나므로 캐시 전체를 비운다 — 반대로
    // 특정 USER 소유(본인 전용 카테고리)면 그 유저의 캐시 키 하나만 지우면 된다.
    // Redis 장애 시에도 생성/수정/삭제 자체가 500 으로 번지지 않도록 fail-open 한다
    // (ScheduleService.evictScheduleCacheForUser() 와 같은 이유 — CacheManager 를 직접 다루는 이 경로는
    // CacheFailSafeErrorHandler 가 적용되는 @Cacheable/@CacheEvict 애노테이션 경로가 아니기 때문)
    private void evictCategoryCache(User owner) {
        Cache cache = cacheManager.getCache(CATEGORY_CACHE);
        if (cache == null) return;
        try {
            if (owner == null || owner.getUserType() == UserType.ADMIN) {
                cache.clear();
            } else {
                cache.evict(String.valueOf(owner.getId()));
            }
        } catch (DataAccessException e) {
            log.warn("카테고리 캐시 삭제 실패 - ownerId={}", owner == null ? null : owner.getId(), e);
        }
    }

    private Category findCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
