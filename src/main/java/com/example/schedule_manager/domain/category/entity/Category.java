package com.example.schedule_manager.domain.category.entity;

import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "categories")
public class Category extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id")
    private Long id;
    private String name;

    // 이 카테고리를 만든 유저. ADMIN 이 만든 카테고리는 "기본 설정 카테고리"로 취급해 삭제를 막는 데 쓰인다
    // (CategoryService.deleteCategory 참고). 기존에 생성된 레거시 카테고리는 이 값이 null 이라 삭제 제한 대상이 아니다
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    public void update(String name) {
        this.name = name;
    }
}
