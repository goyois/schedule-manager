package com.example.schedule_manager.domain.category.repository;

import com.example.schedule_manager.domain.category.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByName(String name);

    // 이 유저에게 보여야 하는 카테고리: 소유자가 없는 레거시 카테고리, ADMIN 이 만든 기본 카테고리(전체 공개),
    // 그리고 본인이 만든 카테고리. 다른 USER 가 만든 카테고리는 그 유저 전용이라 여기 걸리지 않는다.
    // c.user 경로를 LEFT JOIN 없이 그냥 쓰면(c.user.userType 처럼 프로퍼티로 접근) Hibernate 가 암시적으로
    // INNER JOIN 을 만들어서, WHERE 절의 "c.user IS NULL" 조건과 무관하게 소유자 없는 레거시 카테고리가
    // JOIN 단계에서부터 통째로 걸러져 나가 버린다 — 그래서 반드시 LEFT JOIN 으로 명시해야 한다
    @Query("SELECT c FROM Category c LEFT JOIN c.user u WHERE c.user IS NULL "
            + "OR u.userType = com.example.schedule_manager.domain.user.entity.UserType.ADMIN "
            + "OR u.id = :userId")
    List<Category> findVisibleTo(@Param("userId") Long userId);

    // 이름 중복 검사도 위와 같은 범위(레거시/기본/본인 카테고리)로 한정한다 — 그렇지 않으면 서로 안 보이는
    // 다른 USER 의 비공개 카테고리와 이름이 겹쳤다는 이유로 생성이 막히는, 눈에 보이지 않는 충돌이 생긴다.
    // 위와 같은 이유로 LEFT JOIN 을 명시한다
    @Query("SELECT COUNT(c) > 0 FROM Category c LEFT JOIN c.user u WHERE c.name = :name AND (c.user IS NULL "
            + "OR u.userType = com.example.schedule_manager.domain.user.entity.UserType.ADMIN "
            + "OR u.id = :userId)")
    boolean existsVisibleDuplicateName(@Param("name") String name, @Param("userId") Long userId);
}
