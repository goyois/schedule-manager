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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final ScheduleRepository scheduleRepository;

    public CategoryResponseDto createCategory(String requesterEmail, CategoryRequestDto request) {
        User requester = findUserByEmail(requesterEmail);
        if (categoryRepository.existsVisibleDuplicateName(request.name(), requester.getId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_CATEGORY);
        }
        Category category = Category.builder()
                .name(request.name())
                .user(requester)
                .build();
        return CategoryResponseDto.from(categoryRepository.save(category));
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
        return categoryRepository.findVisibleTo(requester.getId()).stream()
                .map(CategoryResponseDto::from)
                .toList();
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
        categoryRepository.delete(category);
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
