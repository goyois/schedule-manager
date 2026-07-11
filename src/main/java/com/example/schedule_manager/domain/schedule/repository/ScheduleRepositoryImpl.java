package com.example.schedule_manager.domain.schedule.repository;

import com.example.schedule_manager.domain.category.entity.QCategory;
import com.example.schedule_manager.domain.schedule.dto.QScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.QSchedule;
import com.example.schedule_manager.domain.user.entity.QUser;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

import static com.example.schedule_manager.domain.category.entity.QCategory.*;
import static com.example.schedule_manager.domain.schedule.entity.QSchedule.*;
import static com.example.schedule_manager.domain.user.entity.QUser.*;

// Spring Data 가 ScheduleRepository(JpaRepository) 프록시를 만들 때, 이름이
// "ScheduleRepository" + "Impl" 인 이 클래스를 자동으로 찾아 ScheduleRepositoryCustom 의 구현으로 합성한다
// (이 클래스가 없으면 searchSchedules() 를 커스텀 구현이 아니라 메서드 이름 기반 쿼리 파생으로 오해해
//  PropertyReferenceException 으로 컨텍스트 로딩 자체가 실패한다)
@RequiredArgsConstructor
public class ScheduleRepositoryImpl implements ScheduleRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    // user/category 를 fetch join 이 아니라 select 절에서 스칼라 값(username, name)으로 바로 뽑아
    // QScheduleResponseDto(ScheduleResponseDto 의 @QueryProjection 생성자로부터 생성된 타입)로 projection 한다
    // → 결과 건수와 무관하게 SQL 1번으로 끝나 N+1 이 원천적으로 발생하지 않고, 생성자 파라미터 순서/타입이
    //   어긋나면 Projections.constructor 의 리플렉션 매칭과 달리 컴파일 타임에 바로 잡힌다
    // (기존 Schedule 엔티티 조회 후 스트림에서 schedule.getUser()/getCategory() 를 호출하던 방식은
    //  LAZY 프록시 초기화 때문에 조회 건마다 추가 SELECT 가 나갈 수 있었다)
    @Override
    public List<ScheduleResponseDto> searchSchedules(Long userId, Long categoryId) {
        return queryFactory.select(Projections.constructor(ScheduleResponseDto.class,
                        schedule.id,
                        schedule.title,
                        schedule.content,
                        schedule.startAt,
                        schedule.endAt,
                        schedule.status,
                        user.username,
                        category.name))
                .from(schedule)
                .join(schedule.user, user)
                .join(schedule.category, category)
                .where(
                        userIdEq(schedule, userId),
                        categoryIdEq(schedule, categoryId))
                .fetch();
    }

    // null 을 반환하면 QueryDSL 이 where() 절에서 해당 조건을 통째로 무시한다
    // → userId/categoryId 유무에 따른 4가지 분기를 이 동적 조건 조합 하나로 대체
    private BooleanExpression userIdEq(QSchedule schedule, Long userId) {
        return userId != null ? schedule.user.id.eq(userId) : null;
    }

    private BooleanExpression categoryIdEq(QSchedule schedule, Long categoryId) {
        return categoryId != null ? schedule.category.id.eq(categoryId) : null;
    }
}
