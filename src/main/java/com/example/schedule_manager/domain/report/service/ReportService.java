package com.example.schedule_manager.domain.report.service;

import com.example.schedule_manager.domain.ai.service.AiRateLimiter;
import com.example.schedule_manager.domain.report.dto.CategorySeriesDto;
import com.example.schedule_manager.domain.report.dto.CategoryStatDto;
import com.example.schedule_manager.domain.report.dto.CategoryTrendDto;
import com.example.schedule_manager.domain.report.dto.PreviousPeriodComparisonDto;
import com.example.schedule_manager.domain.report.dto.ReportInsightCacheDto;
import com.example.schedule_manager.domain.report.dto.ReportInsightDto;
import com.example.schedule_manager.domain.report.dto.ReportStatsDto;
import com.example.schedule_manager.domain.report.dto.StatusCountDto;
import com.example.schedule_manager.domain.report.entity.ReportInsightSnapshot;
import com.example.schedule_manager.domain.report.entity.ReportPeriod;
import com.example.schedule_manager.domain.report.repository.ReportInsightSnapshotRepository;
import com.example.schedule_manager.domain.schedule.dto.ScheduleResponseDto;
import com.example.schedule_manager.domain.schedule.entity.ScheduleStatus;
import com.example.schedule_manager.domain.schedule.service.ScheduleEmbeddingService;
import com.example.schedule_manager.domain.schedule.service.ScheduleService;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

// 기간별 리포트(README "기간별 리포트" 기능): 주/월/년 단위로 카테고리 비율·완료율·직전 동일 기간 대비
// 증감 같은 결정적 통계는 무료로 즉시 계산하고(getStats), "잘한 점/아쉬운 점"·"행동 패턴/성향 평가" 같은
// 주관적 서술은 별도 엔드포인트(getInsight)에서만 AI를 호출한다 - 통계 카드/파이차트는 Anthropic 장애나
// 레이트리밋과 무관하게 항상 즉시 보여줄 수 있어야 하므로, 두 관심사를 하나의 요청/응답으로 묶지 않는다
// (MandalartAiService가 "채우기"만 AI로 하고 순수 복사 부분(mirrorSubGoalsToOuterBlockCenters)은 코드로
// 처리하는 것과 같은 이유).
//
// 리포트는 항상 "본인" 일정만 대상으로 한다(AiService.sendMessage와 동일한 이유 - ADMIN이 호출해도
// scheduleService.getSchedules에 본인 id를 명시적으로 넘겨야 한다. null을 넘기면 ADMIN은 전체 유저
// 일정을 받게 되므로 절대 null을 넘기지 않는다) - 회고/성향 평가라는 기능 성격상 "남의 리포트를 관리자가
// 대신 본다"는 시나리오 자체가 없다.
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    // strengths/improvements 각각 이 개수까지만 반영한다 - UI 카드가 무한정 길어지는 것을 방지(방어적 처리,
    // AiService의 MAX_SCHEDULE_ITEMS_PER_RECOMMENDATION과 같은 이유)
    private static final int MAX_INSIGHT_ITEMS = 5;

    // 이번 기간과 의미상 비슷한 다른 시기의 일정을 몇 건까지 참고 컨텍스트로 보강할지
    // (ScheduleEmbeddingService, AiService.RAG_SCHEDULE_TOP_K와 같은 값)
    private static final int RAG_TOP_K = 5;

    // RAG 질의 텍스트(이번 기간 일정 제목들을 이어붙인 것)가 너무 길어지지 않도록 상한
    private static final int MAX_TITLES_FOR_QUERY = 30;

    // 프롬프트에 나열하는 일정 개수 상한(1년 리포트처럼 일정이 아주 많을 수 있어 토큰 비용 방어) -
    // 정확한 개수/비율은 이미 [기간 통계]에 숫자로 들어가 있으므로, 목록은 맥락 파악용으로만 이 정도면 충분하다
    private static final int MAX_SCHEDULES_IN_PROMPT = 60;

    // 카테고리별 선 그래프(categoryTrend)의 WEEK/MONTH 구간 라벨 포맷 - "MM/dd"
    private static final DateTimeFormatter DAY_LABEL_FORMATTER = DateTimeFormatter.ofPattern("MM/dd");

    // ReportInsightSnapshot.strengths/improvements에 List<String>을 별도 컬렉션 테이블 없이 이어붙여
    // 저장할 때 쓰는 구분자 - AI가 생성하는 자연어 문장에 나올 가능성이 거의 없는 조합을 골랐다
    private static final String ITEM_DELIMITER = "␟";

    private static final String SYSTEM_PROMPT = """
            당신은 사용자의 지난 일정 기록을 바탕으로 회고를 도와주는 도우미입니다. 함께 주어지는
            [기간 통계](정확한 숫자), [이번 기간 일정 목록], 그리고 있다면 [참고: 의미상 비슷한 다른 시기의
            활동]만 근거로 삼아 아래 네 항목을 채우세요. 통계에 없는 숫자나 목록에 없는 일정을 지어내지
            마세요.

            - strengths: 이번 기간에 잘한 점을 1~3개, 짧은 문장으로.
            - improvements: 아쉬웠거나 개선하면 좋을 점을 1~3개, 짧은 문장으로.
            - behaviorPattern: 요일/시간대/카테고리 편중 등 이번 기간에 드러나는 행동 패턴을 1~2문장으로.
            - personalityNote: 일정 데이터에서 드러나는 성향을 가볍고 긍정적인 톤으로 1~2문장. 과도한
              단정이나 부정적 평가는 피하세요.

            해당 기간에 일정이 거의 없다면 억지로 분석하지 말고 그 사실 자체를 언급하세요.
            """;

    private final ChatClient chatClient;
    private final ScheduleService scheduleService;
    private final UserRepository userRepository;
    private final AiRateLimiter aiRateLimiter;
    private final ScheduleEmbeddingService scheduleEmbeddingService;
    private final ReportInsightSnapshotRepository reportInsightSnapshotRepository;

    @Transactional(readOnly = true)
    public ReportStatsDto getStats(String requesterEmail, ReportPeriod period, LocalDate referenceDate) {
        User requester = findUserByEmail(requesterEmail);
        LocalDate ref = referenceDate != null ? referenceDate : LocalDate.now();
        List<ScheduleResponseDto> windowed = fetchStatsWindow(requesterEmail, requester.getId(), period, ref);
        return computeStats(period, ref, windowed);
    }

    // getStats/getInsight가 필요로 하는 건 이번 기간(range) + 직전 동일 기간(previousRange) 비교뿐인데,
    // 예전엔 computeStats에 유저 전체 이력을 무제한으로 넘겨서 그 안에서 메모리로 걸러냈다(요청마다
    // 일정이 쌓일수록 비용이 커지는 구조). 두 기간은 항상 붙어있으므로(prevRange[1] = range[0]-1일)
    // [prevRange[0], range[1]] 하나의 DB 범위 조회로 두 구간을 한 번에 커버한다 - computeStats 내부
    // 로직(resolveRange/previousRange/overlapping)은 그대로 두고 입력 리스트만 이렇게 좁힌다
    private List<ScheduleResponseDto> fetchStatsWindow(String requesterEmail, Long userId, ReportPeriod period, LocalDate ref) {
        LocalDate[] range = resolveRange(period, ref);
        LocalDate[] prevRange = previousRange(period, range[0]);
        return scheduleService.getSchedulesInRange(requesterEmail, userId, null,
                prevRange[0].atStartOfDay(), range[1].plusDays(1).atStartOfDay());
    }

    // AI를 실제로 호출해 재생성하고, 결과를 ReportInsightSnapshot에 upsert한다(그래서 더 이상
    // readOnly가 아니다) - 프론트의 "AI 코멘트 생성" 버튼(신규/재생성 모두)이 호출하는 유일한 경로다.
    // 조회만 하고 싶으면(캐시된 결과 + 최신 여부만 필요하면) getCachedInsight를 쓴다
    @Transactional
    public ReportInsightDto getInsight(String requesterEmail, ReportPeriod period, LocalDate referenceDate) {
        User requester = findUserByEmail(requesterEmail);
        aiRateLimiter.checkLimit(requester.getId(), requester.getUserType());

        LocalDate ref = referenceDate != null ? referenceDate : LocalDate.now();
        List<ScheduleResponseDto> windowed = fetchStatsWindow(requesterEmail, requester.getId(), period, ref);
        ReportStatsDto stats = computeStats(period, ref, windowed);

        LocalDate[] range = resolveRange(period, ref);
        List<ScheduleResponseDto> inRange = overlapping(windowed, range[0].atStartOfDay(), range[1].plusDays(1).atStartOfDay());

        String userPrompt = "[기간 통계]\n" + buildStatsContext(stats)
                + "\n\n[이번 기간 일정 목록]\n" + buildScheduleListContext(inRange)
                + buildRagContext(requester.getId(), inRange);

        ReportInsightDto suggestion;
        try {
            suggestion = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .entity(ReportInsightDto.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AI_REQUEST_FAILED, e);
        }

        ReportInsightDto sanitized = sanitizeInsight(suggestion);
        saveSnapshot(requester, period, range, inRange, sanitized);
        return sanitized;
    }

    // AI를 호출하지 않고(요금/레이트리밋 없음) 마지막으로 저장해둔 결과 + 그 이후 이 기간 일정이 바뀌었는지만
    // 돌려준다. 프론트가 리포트 페이지 진입/기간 전환마다 먼저 이걸 불러 저장된 결과를 바로 보여주고,
    // stale일 때만 "스케줄 변동으로 새로운 결과를 얻을 수 있습니다" 안내와 함께 재생성 버튼을 띄운다
    @Transactional(readOnly = true)
    public ReportInsightCacheDto getCachedInsight(String requesterEmail, ReportPeriod period, LocalDate referenceDate) {
        User requester = findUserByEmail(requesterEmail);
        LocalDate ref = referenceDate != null ? referenceDate : LocalDate.now();
        LocalDate[] range = resolveRange(period, ref);

        ReportInsightSnapshot snapshot = reportInsightSnapshotRepository
                .findByUserIdAndPeriodAndRangeStart(requester.getId(), period, range[0])
                .orElse(null);
        if (snapshot == null) {
            return new ReportInsightCacheDto(null, false, null);
        }

        List<ScheduleResponseDto> inRange = scheduleService.getSchedulesInRange(requesterEmail, requester.getId(), null,
                range[0].atStartOfDay(), range[1].plusDays(1).atStartOfDay());
        Fingerprint current = fingerprintOf(inRange);
        boolean stale = current.count() != snapshot.getScheduleCount()
                || !Objects.equals(current.latestUpdatedAt(), snapshot.getLatestScheduleUpdatedAt());

        ReportInsightDto insight = new ReportInsightDto(
                splitItems(snapshot.getStrengths()),
                splitItems(snapshot.getImprovements()),
                snapshot.getBehaviorPattern(),
                snapshot.getPersonalityNote());

        return new ReportInsightCacheDto(insight, stale, snapshot.getUpdatedAt());
    }

    // "이 기간에 지금 어떤 일정들이 있는지"를 하나의 값으로 압축한 것 - scheduleCount는 추가/삭제/기간
    // 밖으로 이동을, latestUpdatedAt은 내용/상태만 바뀐 경우(개수는 그대로)를 잡아낸다. 저장 시점과 조회
    // 시점에 각각 계산해서 둘 다 같으면 "그 사이 이 기간 일정에 변화가 없었다"로 간주한다
    private record Fingerprint(long count, LocalDateTime latestUpdatedAt) {
    }

    private Fingerprint fingerprintOf(List<ScheduleResponseDto> inRange) {
        LocalDateTime latest = inRange.stream()
                .map(ScheduleResponseDto::updatedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new Fingerprint(inRange.size(), latest);
    }

    private void saveSnapshot(User requester, ReportPeriod period, LocalDate[] range,
                               List<ScheduleResponseDto> inRange, ReportInsightDto insight) {
        Fingerprint fingerprint = fingerprintOf(inRange);
        ReportInsightSnapshot snapshot = reportInsightSnapshotRepository
                .findByUserIdAndPeriodAndRangeStart(requester.getId(), period, range[0])
                .orElseGet(() -> ReportInsightSnapshot.builder()
                        .user(requester)
                        .period(period)
                        .rangeStart(range[0])
                        .rangeEnd(range[1])
                        .build());
        snapshot.update(joinItems(insight.strengths()), joinItems(insight.improvements()),
                insight.behaviorPattern(), insight.personalityNote(), fingerprint.count(), fingerprint.latestUpdatedAt());
        reportInsightSnapshotRepository.save(snapshot);
    }

    private String joinItems(List<String> items) {
        return items == null ? "" : String.join(ITEM_DELIMITER, items);
    }

    private List<String> splitItems(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return Arrays.asList(joined.split(Pattern.quote(ITEM_DELIMITER)));
    }

    private ReportStatsDto computeStats(ReportPeriod period, LocalDate referenceDate, List<ScheduleResponseDto> all) {
        LocalDate[] range = resolveRange(period, referenceDate);
        List<ScheduleResponseDto> inRange = overlapping(all, range[0].atStartOfDay(), range[1].plusDays(1).atStartOfDay());

        long total = inRange.size();
        Map<ScheduleStatus, Long> statusMap = inRange.stream()
                .collect(Collectors.groupingBy(ScheduleResponseDto::status, Collectors.counting()));
        List<StatusCountDto> statusCounts = Arrays.stream(ScheduleStatus.values())
                .map(status -> new StatusCountDto(status, statusMap.getOrDefault(status, 0L)))
                .toList();
        double completionRate = completionRate(total, statusMap);

        Map<String, Long> categoryMap = inRange.stream()
                .collect(Collectors.groupingBy(ScheduleResponseDto::categoryName, Collectors.counting()));
        List<CategoryStatDto> categoryBreakdown = categoryMap.entrySet().stream()
                .map(e -> new CategoryStatDto(e.getKey(), e.getValue(), total > 0 ? e.getValue() * 100.0 / total : 0.0))
                .sorted(Comparator.comparingLong(CategoryStatDto::count).reversed())
                .toList();

        LocalDate[] prevRange = previousRange(period, range[0]);
        List<ScheduleResponseDto> prevInRange = overlapping(all, prevRange[0].atStartOfDay(), prevRange[1].plusDays(1).atStartOfDay());
        long prevTotal = prevInRange.size();
        Map<ScheduleStatus, Long> prevStatusMap = prevInRange.stream()
                .collect(Collectors.groupingBy(ScheduleResponseDto::status, Collectors.counting()));
        double prevCompletionRate = completionRate(prevTotal, prevStatusMap);
        PreviousPeriodComparisonDto previous = new PreviousPeriodComparisonDto(
                prevTotal, prevCompletionRate, total - prevTotal, completionRate - prevCompletionRate);

        CategoryTrendDto categoryTrend = buildCategoryTrend(period, range, inRange, categoryBreakdown);

        return new ReportStatsDto(period, range[0], range[1], total, completionRate, statusCounts, categoryBreakdown, previous, categoryTrend);
    }

    // 카테고리별 선 그래프용 데이터 - 구간(bucket) 단위는 기간에 따라 다르다: WEEK/MONTH는 일 단위(최대
    // 31개, 한 달 치를 하루씩 찍어도 과밀하지 않은 수준), YEAR는 월 단위(12개)로 묶어 점이 너무 많아지는
    // 것을 막는다. 일정은 시작 시각(startAt) 기준으로 한 구간에만 배정한다 - overlapping()처럼 겹침으로
    // 여러 구간에 걸치게 하면 "그 활동이 언제 일어났는지"보다 "언제까지 걸쳐 있었는지"를 보여주게 돼
    // 선 그래프의 "발생 시점 추이"라는 목적과 맞지 않는다. series 순서는 categoryBreakdown(건수 내림차순)과
    // 동일하게 맞춰 프론트가 파이차트/범례와 같은 색을 그대로 재사용할 수 있게 한다.
    private CategoryTrendDto buildCategoryTrend(ReportPeriod period, LocalDate[] range,
                                                 List<ScheduleResponseDto> inRange, List<CategoryStatDto> categoryBreakdown) {
        List<String> categoryNames = categoryBreakdown.stream().map(CategoryStatDto::categoryName).toList();

        if (period == ReportPeriod.YEAR) {
            List<String> labels = IntStream.rangeClosed(1, 12).mapToObj(m -> m + "월").toList();
            Map<String, long[]> countsByCategory = initCounts(categoryNames, 12);
            int year = range[0].getYear();
            for (ScheduleResponseDto s : inRange) {
                LocalDate d = s.startAt().toLocalDate();
                if (d.getYear() != year) {
                    continue; // 방어적 처리 - 연도 경계를 걸치는 일정의 시작일이 다른 해일 수 있음
                }
                long[] counts = countsByCategory.get(s.categoryName());
                if (counts != null) {
                    counts[d.getMonthValue() - 1]++;
                }
            }
            return toTrendDto(labels, categoryNames, countsByCategory);
        }

        int dayCount = (int) (ChronoUnit.DAYS.between(range[0], range[1]) + 1);
        List<String> labels = IntStream.range(0, dayCount)
                .mapToObj(i -> range[0].plusDays(i).format(DAY_LABEL_FORMATTER))
                .toList();
        Map<String, long[]> countsByCategory = initCounts(categoryNames, dayCount);
        for (ScheduleResponseDto s : inRange) {
            LocalDate d = s.startAt().toLocalDate();
            int idx = (int) ChronoUnit.DAYS.between(range[0], d);
            if (idx < 0 || idx >= dayCount) {
                continue; // 방어적 처리 - 기간을 걸치는 일정의 시작일이 이번 구간 밖일 수 있음
            }
            long[] counts = countsByCategory.get(s.categoryName());
            if (counts != null) {
                counts[idx]++;
            }
        }
        return toTrendDto(labels, categoryNames, countsByCategory);
    }

    private Map<String, long[]> initCounts(List<String> categoryNames, int bucketCount) {
        Map<String, long[]> map = new LinkedHashMap<>();
        for (String name : categoryNames) {
            map.put(name, new long[bucketCount]);
        }
        return map;
    }

    private CategoryTrendDto toTrendDto(List<String> labels, List<String> categoryNames, Map<String, long[]> countsByCategory) {
        List<CategorySeriesDto> series = categoryNames.stream()
                .map(name -> new CategorySeriesDto(name, Arrays.stream(countsByCategory.get(name)).boxed().toList()))
                .toList();
        return new CategoryTrendDto(labels, series);
    }

    // 완료율 = COMPLETED / (전체 - CANCELLED) - 취소된 일정은 애초에 "완료할 대상"이 아니었으므로 분모에서
    // 뺀다. 분모가 0(일정이 없거나 전부 취소)이면 0으로 둔다(0/0 방어)
    private double completionRate(long total, Map<ScheduleStatus, Long> statusMap) {
        long completed = statusMap.getOrDefault(ScheduleStatus.COMPLETED, 0L);
        long cancelled = statusMap.getOrDefault(ScheduleStatus.CANCELLED, 0L);
        long denominator = total - cancelled;
        return denominator > 0 ? (double) completed / denominator : 0.0;
    }

    private LocalDate[] resolveRange(ReportPeriod period, LocalDate reference) {
        return switch (period) {
            case WEEK -> {
                LocalDate start = reference.with(DayOfWeek.MONDAY);
                yield new LocalDate[]{start, start.plusDays(6)};
            }
            case MONTH -> {
                LocalDate start = reference.withDayOfMonth(1);
                yield new LocalDate[]{start, start.plusMonths(1).minusDays(1)};
            }
            case YEAR -> {
                LocalDate start = reference.withDayOfYear(1);
                yield new LocalDate[]{start, start.plusYears(1).minusDays(1)};
            }
        };
    }

    private LocalDate[] previousRange(ReportPeriod period, LocalDate currentStart) {
        return switch (period) {
            case WEEK -> new LocalDate[]{currentStart.minusWeeks(1), currentStart.minusDays(1)};
            case MONTH -> new LocalDate[]{currentStart.minusMonths(1), currentStart.minusDays(1)};
            case YEAR -> new LocalDate[]{currentStart.minusYears(1), currentStart.minusDays(1)};
        };
    }

    // ScheduleRepositoryImpl.overlapsRange / dashboard.js의 schedulesOverlappingRange와 동일한 겹침
    // 기준(종료 시각이 없는 알림형 일정은 시작 시각을 종료 시각으로 취급) - 이미 메모리에 올라온 전체 목록을
    // 걸러내는 것이므로 새 QueryDSL 메서드 없이 AiService.filterToWindow와 같은 방식으로 처리한다
    private List<ScheduleResponseDto> overlapping(List<ScheduleResponseDto> schedules, LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        return schedules.stream()
                .filter(s -> {
                    LocalDateTime effectiveEnd = s.endAt() != null ? s.endAt() : s.startAt();
                    return s.startAt().isBefore(rangeEnd) && effectiveEnd.isAfter(rangeStart);
                })
                .sorted(Comparator.comparing(ScheduleResponseDto::startAt))
                .toList();
    }

    private String buildStatsContext(ReportStatsDto s) {
        String categoryLines = s.categoryBreakdown().stream()
                .map(c -> "- %s: %d건 (%.1f%%)".formatted(c.categoryName(), c.count(), c.percentage()))
                .collect(Collectors.joining("\n"));
        String statusLines = s.statusCounts().stream()
                .filter(c -> c.count() > 0)
                .map(c -> "- %s: %d건".formatted(c.status(), c.count()))
                .collect(Collectors.joining("\n"));
        PreviousPeriodComparisonDto prev = s.previous();
        String prevLine = "직전 동일 기간 대비: 총 일정 %+d건, 완료율 %+.1f%%p".formatted(
                prev.totalCountDelta(), prev.completionRateDelta() * 100);

        return "기간: %s ~ %s\n총 일정: %d건\n완료율: %.1f%%\n\n[카테고리별]\n%s\n\n[상태별]\n%s\n\n%s".formatted(
                s.rangeStart(), s.rangeEnd(), s.totalCount(), s.completionRate() * 100,
                categoryLines.isBlank() ? "(없음)" : categoryLines,
                statusLines.isBlank() ? "(없음)" : statusLines,
                prevLine);
    }

    private String buildScheduleListContext(List<ScheduleResponseDto> schedules) {
        if (schedules.isEmpty()) {
            return "(해당 기간에 등록된 일정 없음)";
        }
        List<ScheduleResponseDto> capped = schedules.size() > MAX_SCHEDULES_IN_PROMPT
                ? schedules.subList(0, MAX_SCHEDULES_IN_PROMPT)
                : schedules;
        String body = capped.stream()
                .map(s -> "- [%s] %s (%s, 카테고리: %s)".formatted(s.status(), s.title(), s.startAt(), s.categoryName()))
                .collect(Collectors.joining("\n"));
        return schedules.size() > MAX_SCHEDULES_IN_PROMPT
                ? body + "\n... 외 %d건".formatted(schedules.size() - MAX_SCHEDULES_IN_PROMPT)
                : body;
    }

    // 이번 기간 일정 제목들을 질의로 삼아, 기간 밖에서 의미상 비슷한 과거/예정 일정을 찾아 참고 컨텍스트로
    // 보강한다(AiService.sendMessage가 채팅 응답에 하는 것과 같은 목적의 RAG, 다만 질의가 사용자 메시지가
    // 아니라 "이번 기간 일정 제목들"이라는 점이 다르다) - "이런 활동이 예전에도 있었다" 같은 연속성 있는
    // 서술을 가능하게 한다. 검색 자체가 실패해도 ScheduleEmbeddingService가 이미 fail-open으로 빈 목록을
    // 돌려주므로 여기서 별도 예외 처리는 필요 없다.
    //
    // 매칭된 일정은 이번/직전 기간 밖(몇 달 전 등)일 수 있어 getSchedulesByIds로 그 (RAG_TOP_K=5개 이하)
    // id만 targeted 조회한다 - 예전엔 이 조회를 피하려고 유저 전체 이력을 항상 미리 들고 있었는데,
    // 그 대가(매 리포트 요청마다 무제한 전체 조회)가 실제 매칭이 있을 때만 발생하는 이 작은 조회보다 컸다
    private String buildRagContext(Long userId, List<ScheduleResponseDto> inRange) {
        if (inRange.isEmpty()) {
            return "";
        }
        String queryText = inRange.stream()
                .map(ScheduleResponseDto::title)
                .filter(t -> t != null && !t.isBlank())
                .limit(MAX_TITLES_FOR_QUERY)
                .collect(Collectors.joining(", "));
        if (queryText.isBlank()) {
            return "";
        }

        Set<Long> inRangeIds = inRange.stream().map(ScheduleResponseDto::id).collect(Collectors.toSet());
        List<Long> similarIds = scheduleEmbeddingService.findSimilarScheduleIds(userId, queryText, inRangeIds, RAG_TOP_K);
        if (similarIds.isEmpty()) {
            return "";
        }

        List<ScheduleResponseDto> similar = scheduleService.getSchedulesByIds(Set.copyOf(similarIds));
        if (similar.isEmpty()) {
            return "";
        }

        return "\n\n[참고: 의미상 비슷한 다른 시기의 활동]\n" + buildScheduleListContext(similar);
    }

    // 모델이 리스트를 비워/null로 보내거나 과도하게 채워 보낼 수 있으므로 방어적으로 정리한다
    // (AiService.sanitizeRecommendationItem과 같은 이유)
    private ReportInsightDto sanitizeInsight(ReportInsightDto raw) {
        return new ReportInsightDto(
                capNonBlank(raw.strengths()),
                capNonBlank(raw.improvements()),
                raw.behaviorPattern() != null ? raw.behaviorPattern() : "",
                raw.personalityNote() != null ? raw.personalityNote() : "");
    }

    private List<String> capNonBlank(List<String> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(s -> s != null && !s.isBlank())
                .limit(MAX_INSIGHT_ITEMS)
                .toList();
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
