package com.example.schedule_manager.domain.user.repository;

import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByUsername(String username);

    boolean existsByEmail(String email);

    // DefaultCategorySeeder가 기본 카테고리의 소유자로 지정할 ADMIN 계정 하나를 아무거나 고를 때 쓴다
    Optional<User> findFirstByUserType(UserType userType);
}
