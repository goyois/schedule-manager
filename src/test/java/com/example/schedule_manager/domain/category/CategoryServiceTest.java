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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

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
    @DisplayName("카테고리 목록 조회 - 레거시/기본(ADMIN)/본인 카테고리만 보여주도록 repository 에 위임한다")
    void getCategories_delegatesToVisibleQuery() {
        User requester = user(1L, UserType.USER);
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(requester));
        Category visible = Category.builder().id(1L).name("보이는 것").build();
        when(categoryRepository.findVisibleTo(1L)).thenReturn(List.of(visible));

        List<CategoryResponseDto> result = categoryService.getCategories("tester@example.com");

        assertThat(result).extracting(CategoryResponseDto::name).containsExactly("보이는 것");
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
}
