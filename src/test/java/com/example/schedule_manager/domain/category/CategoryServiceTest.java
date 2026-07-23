package com.example.schedule_manager.domain.category;

import com.example.schedule_manager.domain.category.dto.CategoryRequestDto;
import com.example.schedule_manager.domain.category.dto.CategoryResponseDto;
import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.category.service.CategoryService;
import com.example.schedule_manager.domain.schedule.repository.ScheduleRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.QueryTimeoutException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    private static final String CATEGORY_CACHE_NAME = "categories";

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    // CategoryCacheQueryService(패키지 프라이빗)는 CategoryService 와 같은 패키지에 있어 이 테스트
    // 클래스(domain.category)에서는 참조할 수 없다 — ScheduleServiceTest 도 같은 이유로
    // ScheduleCacheQueryService 위임 자체는 테스트하지 않는 것과 동일한 제약. getCategories() 는
    // 그 빈에 위임하는 한 줄짜리 배선이라 여기서는 캐시 무효화 로직(evictCategoryCache)만 검증한다
    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    @InjectMocks
    private CategoryService categoryService;

    private User user(Long id, UserType userType) {
        return User.builder()
                .id(id)
                .username("tester")
                .email("tester@example.com")
                .userType(userType)
                .build();
    }

    @Test
    @DisplayName("카테고리 생성 성공 - 요청자가 소유자로 저장된다")
    void createCategory_success_setsRequesterAsOwner() {
        CategoryRequestDto request = new CategoryRequestDto("업무");
        User requester = user(1L, UserType.USER);

        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.existsVisibleDuplicateName("업무", 1L)).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> {
            Category saved = invocation.getArgument(0);
            return Category.builder().id(10L).name(saved.getName()).user(saved.getUser()).build();
        });

        CategoryResponseDto response = categoryService.createCategory("tester@example.com", request);

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.name()).isEqualTo("업무");
        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(requester);
    }

    @Test
    @DisplayName("카테고리 생성 실패 - 본인에게 보이는 범위(레거시/기본/본인 카테고리)에 같은 이름이 있으면 예외가 발생한다")
    void createCategory_duplicateVisibleName_throws() {
        CategoryRequestDto request = new CategoryRequestDto("업무");
        User requester = user(1L, UserType.USER);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.existsVisibleDuplicateName("업무", 1L)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.createCategory("tester@example.com", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_CATEGORY);

        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("카테고리 단건 조회 성공 - ADMIN 이 만든 기본 카테고리는 다른 유저도 볼 수 있다")
    void getCategory_adminOwned_visibleToOtherUser() {
        User requester = user(1L, UserType.USER);
        Category adminCategory = Category.builder().id(1L).name("기본").user(user(99L, UserType.ADMIN)).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(adminCategory));

        CategoryResponseDto response = categoryService.getCategory("tester@example.com", 1L);

        assertThat(response.name()).isEqualTo("기본");
    }

    @Test
    @DisplayName("카테고리 단건 조회 실패 - 다른 USER 가 만든 비공개 카테고리는 존재하지 않는 것과 동일하게 처리한다")
    void getCategory_otherUsersPrivateCategory_treatedAsNotFound() {
        User requester = user(1L, UserType.USER);
        Category othersCategory = Category.builder().id(2L).name("남의 것").user(user(2L, UserType.USER)).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(othersCategory));

        assertThatThrownBy(() -> categoryService.getCategory("tester@example.com", 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    @DisplayName("카테고리 삭제 실패 - ADMIN 이 만든 기본 설정 카테고리는 삭제할 수 없다")
    void deleteCategory_ownedByAdmin_throwsForbidden() {
        User requester = user(1L, UserType.USER);
        Category adminCategory = Category.builder().id(1L).name("기본").user(user(99L, UserType.ADMIN)).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(adminCategory));

        assertThatThrownBy(() -> categoryService.deleteCategory("tester@example.com", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("기본 설정 카테고리는 삭제 불가합니다.")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEFAULT_CATEGORY_DELETE_FORBIDDEN);

        verify(categoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("카테고리 삭제 실패 - 아직 이 카테고리를 쓰는 일정이 있으면 삭제할 수 없다")
    void deleteCategory_stillUsedBySchedules_throwsForbidden() {
        User requester = user(5L, UserType.USER);
        Category userCategory = Category.builder().id(4L).name("사용중").user(requester).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(4L)).thenReturn(Optional.of(userCategory));
        when(scheduleRepository.existsByCategoryId(4L)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.deleteCategory("tester@example.com", 4L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("기본 설정 카테고리는 삭제 불가합니다.")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEFAULT_CATEGORY_DELETE_FORBIDDEN);

        verify(categoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("카테고리 삭제 성공 - 본인이 만든 카테고리는 삭제할 수 있다")
    void deleteCategory_ownedByRequester_succeeds() {
        User requester = user(5L, UserType.USER);
        Category userCategory = Category.builder().id(2L).name("개인").user(requester).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(userCategory));

        categoryService.deleteCategory("tester@example.com", 2L);

        verify(categoryRepository).delete(userCategory);
    }

    @Test
    @DisplayName("카테고리 삭제 실패 - 다른 USER 가 만든 비공개 카테고리는 존재하지 않는 것과 동일하게 처리한다")
    void deleteCategory_otherUsersPrivateCategory_treatedAsNotFound() {
        User requester = user(1L, UserType.USER);
        Category othersCategory = Category.builder().id(6L).name("남의 것").user(user(2L, UserType.USER)).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(6L)).thenReturn(Optional.of(othersCategory));

        assertThatThrownBy(() -> categoryService.deleteCategory("tester@example.com", 6L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);

        verify(categoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("카테고리 삭제 성공 - 소유자 정보가 없는 레거시 카테고리는 삭제할 수 있다")
    void deleteCategory_legacyNullOwner_succeeds() {
        User requester = user(1L, UserType.USER);
        Category legacyCategory = Category.builder().id(3L).name("레거시").build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(legacyCategory));

        categoryService.deleteCategory("tester@example.com", 3L);

        verify(categoryRepository).delete(legacyCategory);
    }

    @Test
    @DisplayName("카테고리 삭제 실패 - 존재하지 않는 카테고리면 예외가 발생한다")
    void deleteCategory_notFound_throws() {
        User requester = user(1L, UserType.USER);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.deleteCategory("tester@example.com", 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    @DisplayName("카테고리 수정 성공 - 본인이 만든 카테고리는 수정할 수 있다")
    void updateCategory_ownedByRequester_succeeds() {
        User requester = user(5L, UserType.USER);
        Category userCategory = Category.builder().id(7L).name("이전 이름").user(requester).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(7L)).thenReturn(Optional.of(userCategory));

        CategoryResponseDto response = categoryService.updateCategory("tester@example.com", 7L, new CategoryRequestDto("새 이름"));

        assertThat(response.name()).isEqualTo("새 이름");
    }

    @Test
    @DisplayName("카테고리 수정 실패 - ADMIN 이 만든 기본 설정 카테고리는 수정할 수 없다")
    void updateCategory_adminOwned_throwsForbidden() {
        User requester = user(1L, UserType.USER);
        Category adminCategory = Category.builder().id(8L).name("기본").user(user(99L, UserType.ADMIN)).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(8L)).thenReturn(Optional.of(adminCategory));

        assertThatThrownBy(() -> categoryService.updateCategory("tester@example.com", 8L, new CategoryRequestDto("새 이름")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("기본 설정 카테고리는 수정 불가합니다.")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEFAULT_CATEGORY_UPDATE_FORBIDDEN);

        assertThat(adminCategory.getName()).isEqualTo("기본");
    }

    @Test
    @DisplayName("카테고리 수정 실패 - 다른 USER 가 만든 비공개 카테고리는 존재하지 않는 것과 동일하게 처리한다")
    void updateCategory_otherUsersPrivateCategory_treatedAsNotFound() {
        User requester = user(1L, UserType.USER);
        Category othersCategory = Category.builder().id(9L).name("남의 것").user(user(2L, UserType.USER)).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(9L)).thenReturn(Optional.of(othersCategory));

        assertThatThrownBy(() -> categoryService.updateCategory("tester@example.com", 9L, new CategoryRequestDto("새 이름")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);

        assertThat(othersCategory.getName()).isEqualTo("남의 것");
    }

    @Test
    @DisplayName("카테고리 수정 성공 - 소유자 정보가 없는 레거시 카테고리는 수정할 수 있다")
    void updateCategory_legacyNullOwner_succeeds() {
        User requester = user(1L, UserType.USER);
        Category legacyCategory = Category.builder().id(10L).name("레거시").build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(legacyCategory));

        CategoryResponseDto response = categoryService.updateCategory("tester@example.com", 10L, new CategoryRequestDto("새 이름"));

        assertThat(response.name()).isEqualTo("새 이름");
    }

    // ---------- 캐시 무효화 ----------
    // ADMIN 소유(기본 카테고리)나 소유자 없는 레거시 카테고리는 모든 유저의 목록에 나타나므로 캐시 전체를
    // 비우고(clear), 특정 USER 전용 카테고리는 그 유저의 캐시 키 하나만 지운다(evict) — CategoryService.
    // evictCategoryCache() 참고

    @Test
    @DisplayName("캐시 무효화 - ADMIN 이 카테고리를 생성하면(기본 카테고리) 캐시 전체를 비운다")
    void createCategory_byAdmin_clearsEntireCache() {
        CategoryRequestDto request = new CategoryRequestDto("공지");
        User admin = user(50L, UserType.ADMIN);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(admin));
        when(categoryRepository.existsVisibleDuplicateName("공지", 50L)).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cacheManager.getCache(CATEGORY_CACHE_NAME)).thenReturn(cache);

        categoryService.createCategory("tester@example.com", request);

        verify(cache).clear();
        verify(cache, never()).evict(any());
    }

    @Test
    @DisplayName("캐시 무효화 - USER 가 본인 카테고리를 생성하면 본인 캐시 키만 지운다")
    void createCategory_byUser_evictsOwnCacheKeyOnly() {
        CategoryRequestDto request = new CategoryRequestDto("취미");
        User requester = user(20L, UserType.USER);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.existsVisibleDuplicateName("취미", 20L)).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cacheManager.getCache(CATEGORY_CACHE_NAME)).thenReturn(cache);

        categoryService.createCategory("tester@example.com", request);

        verify(cache).evict("20");
        verify(cache, never()).clear();
    }

    @Test
    @DisplayName("캐시 무효화 - 본인 카테고리 수정 시 본인 캐시 키만 지운다")
    void updateCategory_ownedByRequester_evictsOwnCacheKeyOnly() {
        User requester = user(21L, UserType.USER);
        Category userCategory = Category.builder().id(11L).name("이전 이름").user(requester).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(11L)).thenReturn(Optional.of(userCategory));
        when(cacheManager.getCache(CATEGORY_CACHE_NAME)).thenReturn(cache);

        categoryService.updateCategory("tester@example.com", 11L, new CategoryRequestDto("새 이름"));

        verify(cache).evict("21");
        verify(cache, never()).clear();
    }

    @Test
    @DisplayName("캐시 무효화 - 소유자 없는 레거시 카테고리 수정 시 캐시 전체를 비운다")
    void updateCategory_legacyNullOwner_clearsEntireCache() {
        User requester = user(1L, UserType.USER);
        Category legacyCategory = Category.builder().id(12L).name("레거시").build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(12L)).thenReturn(Optional.of(legacyCategory));
        when(cacheManager.getCache(CATEGORY_CACHE_NAME)).thenReturn(cache);

        categoryService.updateCategory("tester@example.com", 12L, new CategoryRequestDto("새 이름"));

        verify(cache).clear();
        verify(cache, never()).evict(any());
    }

    @Test
    @DisplayName("캐시 무효화 - 본인 카테고리 삭제 시 본인 캐시 키만 지운다")
    void deleteCategory_ownedByRequester_evictsOwnCacheKeyOnly() {
        User requester = user(22L, UserType.USER);
        Category userCategory = Category.builder().id(13L).name("개인").user(requester).build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(13L)).thenReturn(Optional.of(userCategory));
        when(cacheManager.getCache(CATEGORY_CACHE_NAME)).thenReturn(cache);

        categoryService.deleteCategory("tester@example.com", 13L);

        verify(cache).evict("22");
        verify(cache, never()).clear();
    }

    @Test
    @DisplayName("캐시 무효화 - 소유자 없는 레거시 카테고리 삭제 시 캐시 전체를 비운다")
    void deleteCategory_legacyNullOwner_clearsEntireCache() {
        User requester = user(1L, UserType.USER);
        Category legacyCategory = Category.builder().id(14L).name("레거시").build();
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.findById(14L)).thenReturn(Optional.of(legacyCategory));
        when(cacheManager.getCache(CATEGORY_CACHE_NAME)).thenReturn(cache);

        categoryService.deleteCategory("tester@example.com", 14L);

        verify(cache).clear();
        verify(cache, never()).evict(any());
    }

    @Test
    @DisplayName("캐시 무효화 - Redis 장애로 캐시 삭제에 실패해도 카테고리 생성 자체는 실패하지 않는다(fail-open)")
    void createCategory_cacheEvictionFails_stillSucceeds() {
        CategoryRequestDto request = new CategoryRequestDto("업무");
        User requester = user(23L, UserType.USER);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        when(categoryRepository.existsVisibleDuplicateName("업무", 23L)).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cacheManager.getCache(CATEGORY_CACHE_NAME)).thenReturn(cache);
        doThrow(new QueryTimeoutException("redis down")).when(cache).evict(any());

        CategoryResponseDto response = categoryService.createCategory("tester@example.com", request);

        assertThat(response.name()).isEqualTo("업무");
    }
}
