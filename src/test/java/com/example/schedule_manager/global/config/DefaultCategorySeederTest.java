package com.example.schedule_manager.global.config;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCategorySeederTest {

    private static final List<String> DEFAULT_CATEGORY_NAMES = List.of("일상", "업무", "자기계발", "식단", "운동");

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationArguments applicationArguments;

    @InjectMocks
    private DefaultCategorySeeder seeder;

    private User admin(Long id) {
        return User.builder().id(id).userType(UserType.ADMIN).build();
    }

    @Test
    @DisplayName("ADMIN 계정이 없으면 기본 카테고리를 생성하지 않는다")
    void run_noAdmin_skipsSeeding() {
        when(userRepository.findFirstByUserType(UserType.ADMIN)).thenReturn(Optional.empty());

        seeder.run(applicationArguments);

        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    @DisplayName("ADMIN 계정이 있고 기본 카테고리가 하나도 없으면 5개를 전부 ADMIN 소유로 생성한다")
    void run_adminExists_createsAllMissingDefaultCategories() {
        User requester = admin(1L);
        when(userRepository.findFirstByUserType(UserType.ADMIN)).thenReturn(Optional.of(requester));
        when(categoryRepository.findByName(anyString())).thenReturn(Optional.empty());

        seeder.run(applicationArguments);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository, org.mockito.Mockito.times(5)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Category::getName)
                .containsExactlyInAnyOrderElementsOf(DEFAULT_CATEGORY_NAMES);
        assertThat(captor.getAllValues()).allMatch(c -> c.getUser() == requester);
    }

    @Test
    @DisplayName("이미 존재하는 이름의 카테고리는 다시 만들지 않고 없는 것만 생성한다")
    void run_someAlreadyExist_onlyCreatesMissingOnes() {
        User requester = admin(1L);
        when(userRepository.findFirstByUserType(UserType.ADMIN)).thenReturn(Optional.of(requester));
        when(categoryRepository.findByName("일상")).thenReturn(Optional.of(Category.builder().name("일상").build()));
        when(categoryRepository.findByName("업무")).thenReturn(Optional.of(Category.builder().name("업무").build()));
        when(categoryRepository.findByName("자기계발")).thenReturn(Optional.empty());
        when(categoryRepository.findByName("식단")).thenReturn(Optional.empty());
        when(categoryRepository.findByName("운동")).thenReturn(Optional.empty());

        seeder.run(applicationArguments);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Category::getName)
                .containsExactlyInAnyOrder("자기계발", "식단", "운동");
    }
}
