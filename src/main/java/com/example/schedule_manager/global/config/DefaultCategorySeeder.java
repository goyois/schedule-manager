package com.example.schedule_manager.global.config;

import com.example.schedule_manager.domain.category.entity.Category;
import com.example.schedule_manager.domain.category.repository.CategoryRepository;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 앱 기동 시 일상/업무/자기계발/식단/운동 5개를 ADMIN 소유(CategoryService.isAdminOwned가 취급하는
// "기본 설정 카테고리")로 없으면 만들어 둔다 - 모든 유저에게 보이지만 일반 USER는 수정/삭제할 수 없고
// (updateCategory는 ADMIN 소유면 요청자와 무관하게 막고, deleteCategory는 ADMIN 요청자만 예외로 허용),
// 이미 같은 이름의 카테고리(레거시든 다른 유저의 것이든)가 있으면 건드리지 않고 건너뛴다 - 기존 카테고리를
// 가로채 소유자를 바꿔버리면 그 카테고리를 쓰던 사람 입장에서 갑자기 권한이 바뀌는 부작용이 생기기 때문이다.
// ADMIN 계정이 아직 없는 환경(예: 막 새로 띄운 빈 DB)에서는 소유자로 지정할 유저가 없으므로 조용히
// 건너뛴다 - 이후 ADMIN 계정이 생기고 앱이 재기동되면 그때 채워진다
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultCategorySeeder implements ApplicationRunner {

    private static final List<String> DEFAULT_CATEGORY_NAMES = List.of("일상", "업무", "자기계발", "식단", "운동");

    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        User admin = userRepository.findFirstByUserType(UserType.ADMIN).orElse(null);
        if (admin == null) {
            log.warn("ADMIN 계정이 없어 기본 카테고리를 생성하지 못했습니다 - ADMIN 계정 생성 후 재기동하면 자동으로 채워집니다");
            return;
        }

        DEFAULT_CATEGORY_NAMES.stream()
                .filter(name -> categoryRepository.findByName(name).isEmpty())
                .forEach(name -> {
                    categoryRepository.save(Category.builder().name(name).user(admin).build());
                    log.info("기본 카테고리 생성: {}", name);
                });
    }
}
