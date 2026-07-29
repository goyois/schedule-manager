package com.example.schedule_manager.domain.schedule.repository;

import com.example.schedule_manager.domain.category.entity.QCategory;
import com.example.schedule_manager.domain.schedule.dto.QScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.QSchedule;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.example.schedule_manager.domain.user.entity.QUser;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
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

    // dashboard.js의 보드 뷰 "더보기" 전용 - status 컬럼 하나를 [rangeStart, rangeEnd) 범위와
    // 겹치는 것만으로 좁혀 LIMIT/OFFSET(Pageable) 적용한 페이지를 돌려준다. content 쿼리와 count 쿼리를
    // 분리하되, PageableExecutionUtils 가 (같은 페이지 안에 다 들어가는 경우 등) 불필요하면 count 쿼리
    // 실행 자체를 생략해준다
    @Override
    public Page<ScheduleResponseDto> searchBoardSchedules(Long userId, Long categoryId, ScheduleStatus status,
                                                            LocalDateTime rangeStart, LocalDateTime rangeEnd,
                                                            Pageable pageable) {
        List<ScheduleResponseDto> content = queryFactory.select(Projections.constructor(ScheduleResponseDto.class,
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
                        categoryIdEq(schedule, categoryId),
                        schedule.status.eq(status),
                        overlapsRange(schedule, rangeStart, rangeEnd))
                .orderBy(schedule.startAt.asc(), schedule.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> queryFactory.select(schedule.count())
                .from(schedule)
                .where(
                        userIdEq(schedule, userId),
                        categoryIdEq(schedule, categoryId),
                        schedule.status.eq(status),
                        overlapsRange(schedule, rangeStart, rangeEnd))
                .fetchOne());
    }

    // null 을 반환하면 QueryDSL 이 where() 절에서 해당 조건을 통째로 무시한다
    // → userId/categoryId 유무에 따른 4가지 분기를 이 동적 조건 조합 하나로 대체
    private BooleanExpression userIdEq(QSchedule schedule, Long userId) {
        return userId != null ? schedule.user.id.eq(userId) : null;
    }

    private BooleanExpression categoryIdEq(QSchedule schedule, Long categoryId) {
        return categoryId != null ? schedule.category.id.eq(categoryId) : null;
    }

    // dashboard.js의 schedulesOverlappingRange()와 동일한 기준: 종료 시각이 없는(알림형) 일정은
    // 시작 시각을 종료 시각으로 취급한다. end(=endAt 또는 startAt) > rangeStart && startAt < rangeEnd
    private BooleanExpression overlapsRange(QSchedule schedule, LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        BooleanExpression effectiveEndAfterRangeStart = schedule.endAt.isNull().and(schedule.startAt.gt(rangeStart))
                .or(schedule.endAt.isNotNull().and(schedule.endAt.gt(rangeStart)));
        return schedule.startAt.lt(rangeEnd).and(effectiveEndAfterRangeStart);
    }
}
