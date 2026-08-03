# RAG 파이프라인 개선 기록 (유사도 하한선 + 임베딩 색인 비동기화)

> 2026-08-03, 커밋 `24d6e78` (`[fix-41]`). `ScheduleEmbeddingService`/`MandalartGoalEmbeddingService`
> (CLAUDE.md의 "Schedule RAG"/"Mandalart RAG" 절 참고)를 다시 감사(audit)하다가 서로 다른 축의 문제
> 두 가지 — **검색 품질**(무관한 결과가 섞여 들어옴)과 **응답 지연**(임베딩 API 호출이 쓰기 요청을
> 블로킹함) — 를 찾아 같이 고쳤다. 하나는 리트리벌 판단력, 하나는 설계/성능 판단력을 보여주는
> 사례라 기록해둔다.

## 문제 1 — 유사도 하한선 없이 topK를 무조건 채움

### As-Is

`ScheduleEmbeddingService.findSimilarScheduleIds`와 `MandalartGoalEmbeddingService.findSimilarExamples`
모두 `VectorStore.similaritySearch(SearchRequest...)`를 호출할 때 `.withTopK(...)`와
`.withFilterExpression(...)`만 지정하고 `.withSimilarityThreshold(...)`는 지정하지 않았다
(spring-ai 기본값은 `SIMILARITY_THRESHOLD_ACCEPT_ALL` = 0.0, 사실상 무제한 허용). 그 결과 진짜로
의미상 비슷한 문서가 하나도 없어도 "그나마 제일 덜 무관한" topK개를 그대로 채워서 반환했다 —
`AiService`의 `[참고: 의미상 비슷한 과거/예정 일정]` 섹션, `MandalartAiService`의 few-shot 예시
모두 이 값을 그대로 프롬프트에 얹는다.

### 실측 (실제 OpenAI `text-embedding-3-small` 라이브 호출)

로컬에 설정된 실제 OpenAI 키로 즉석 스크립트(코사인 유사도 직접 계산, 결과는 이 문서에만 남기고
저장소에는 커밋하지 않음)를 돌려 두 개의 서로 다른 질의로 검증했다.

**질의 1**: "이번 주말에 등산 갈 만한 코스 있으면 일정으로 잡아줘"

| 후보 일정 | 코사인 유사도 |
|---|---:|
| 설악산 1박2일 등반 계획 | 0.4708 |
| 관악산 등산 (작년 가을) | 0.3978 |
| 북한산 둘레길 답사 | 0.3366 |
| 치과 스케일링 예약 | 0.2530 |
| 분기 회계 마감 보고서 제출 | 0.2389 |
| 노트북 배터리 교체 문의 | 0.2218 |

topK=5, 하한선 없음(AS-IS) 기준이면 "치과 스케일링 예약"·"분기 회계 마감 보고서 제출"처럼 질의와
전혀 무관한 일정 2건이 그대로 프롬프트에 섞여 들어간다.

**질의 2**: "다음 주에 팀 프로젝트 발표 준비 관련 일정 있었는지 찾아줘"

| 후보 일정 | 코사인 유사도 |
|---|---:|
| 프로젝트 발표 리허설 | 0.4850 |
| 3분기 팀 프로젝트 킥오프 | 0.3889 |
| 생일 파티 준비 | 0.3400 |
| 분기 회고 미팅 | 0.3012 |
| 헬스장 PT 예약 | 0.2592 |
| 자동차 정기 점검 | 0.1817 |

### To-Be

`SearchRequest`에 `.withSimilarityThreshold(0.35)`를 추가해, 이 값 미만인 결과는 애초에
`VectorStore` 단에서 걸러지도록 했다.

- 질의 1: "치과 스케일링 예약"(0.2530), "분기 회계 마감 보고서 제출"(0.2389)이 제외되고 등산 관련
  3건만 남는다.
- 질의 2: "헬스장 PT 예약"(0.2592), "자동차 정기 점검"(0.1817)이 제외된다.

### 한계 — 완벽한 판별선이 아니다 (정직하게 기록)

질의 2에서 "생일 파티 준비"(0.3400, 명백히 무관)가 "분기 회고 미팅"(0.3012, 그나마 업무 맥락이라
어느 정도 관련)보다 유사도가 더 높게 나왔다. 즉 코사인 유사도 순서가 실제 관련도 순서와 어긋나는
구간이 존재한다 — `text-embedding-3-small`이 한국어에서 유사도 분포가 압축되고 노이즈가 있다는
CLAUDE.md의 기존 우려(범용 다국어 모델의 한국어 성능 리스크)가 실측으로 확인된 셈이다. 그래서
0.35는 "완벽한 관련/무관 분리선"이 아니라 "recall보다 precision을 우선하는" 보수적 임계값으로
선택했다 — RAG 결과는 어차피 `AiService`/`MandalartAiService`의 보조 컨텍스트일 뿐이라, 애매하면
아예 안 보여주는 쪽이 잘못된 걸 보여주는 쪽보다 안전하다고 판단했다.

### 코드 변경

- `ScheduleEmbeddingService.SIMILARITY_THRESHOLD = 0.35` (`findSimilarScheduleIds`)
- `MandalartGoalEmbeddingService.SIMILARITY_THRESHOLD = 0.35` (`findSimilarExamples`, topK=1이라
  하한선이 없으면 무관한 과거 블록도 "그나마 제일 비슷한 것"이라는 이유만으로 항상 few-shot에
  끼워 넣게 된다는 점에서 오히려 더 취약했음)
- 테스트(`ScheduleEmbeddingServiceTest`, `MandalartGoalEmbeddingServiceTest`)는 `SearchRequest`에
  임계값이 실제로 실려 나가는지만 검증한다 — 실제 벡터 유사도 필터링은 `PgVectorStore` 내부(SQL
  `WHERE 1 - (embedding <=> ?) >= threshold`)에서 일어나므로 Mockito로는 재현할 수 없다.

## 문제 2 — 임베딩 색인/삭제가 요청 스레드를 블로킹

### As-Is

`ScheduleService.createSchedule`/`updateSchedule`/`deleteSchedule`, `MandalartService.updateCell`/
`deleteBoard`가 각각 `ScheduleEmbeddingService.reindexSchedule`/`deleteScheduleEmbedding`,
`MandalartGoalEmbeddingService.reindexBlockIfComplete`/`deleteBoardEmbeddings`를 동기 호출한다.
이 메서드들은 내부에서 `VectorStore.add(...)`/`.delete(...)`를 호출하는데, 이건 OpenAI 임베딩 API
호출 + pgvector 왕복이 끝나야 반환된다. 즉 **일정 하나 등록/수정/삭제할 때마다, 그리고 만다라트
셀을 채울 때마다** RAG는 그 결과를 나중에(AI 챗봇 질문 시점에) 참고용으로만 쓰는데도 그 색인 작업이
끝날 때까지 사용자 응답이 지연됐다. 코드베이스 전체에 `@EnableAsync`/`@Async`가 단 한 곳도 없었다
(확인: `grep -rn "@Async\|EnableAsync"` 전체 저장소 0건).

### To-Be

`global/config/AsyncConfig`를 신설해 `@EnableAsync` + 전용 `embeddingTaskExecutor`
(`ThreadPoolTaskExecutor`, core 2 / max 4 / queue 50)를 등록하고, 색인·삭제(쓰기) 메서드 4개에만
`@Async("embeddingTaskExecutor")`를 붙였다. 빈 이름은 기본 매칭에 기대지 않고 명시적으로 지정했다
(CLAUDE.md에 이미 기록된 `mandalartFillChatClient` 빈-이름 매칭 이슈와 같은 이유 — Lombok
`@RequiredArgsConstructor`/`@Qualifier` 관련 지뢰를 다시 밟지 않으려는 습관). 검색 메서드
(`findSimilarScheduleIds`, `findSimilarExamples`)는 결과를 즉시 써야 하므로 동기로 남겼다.

트랜잭션 경계 관련해서도 확인했다: 이 서비스들은 클래스 레벨 `@Transactional`이지만, 임베딩
색인은 이미 메모리에 있는 엔티티 필드 값만 읽어 `VectorStore`(별도 JDBC 커넥션)에 쓰는 것이라
Hibernate 세션에 의존하지 않는다 — 그래서 `@Async`로 스레드가 바뀌어도 지연 로딩
(`LazyInitializationException`) 위험이 없다. 트랜잭션이 나중에 롤백될 경우 이미 나간 임베딩
호출이 고아 문서를 남길 수 있다는 이론적 위험은 동기 시절에도 완전히 동일하게 존재했다(별도
커넥션이라 커밋 순서와 무관) — 비동기 전환으로 새로 생긴 리스크가 아니다.

### 실측

실제 OpenAI 호출은 네트워크 변동성 때문에 재현 가능한 숫자를 못 준다. 대신
`ScheduleEmbeddingAsyncPerformanceTest`(`@Tag("performance")`, `./gradlew test`에 포함, CI 제외
대상은 아님 — DB 시드 데이터 불필요)에서 `VectorStore.add`를 300ms 인위적 지연으로 모킹해
"임베딩 API가 N ms 걸릴 때 호출자가 그 시간을 그대로 떠안는지"만 격리해서 측정했다.

| | 호출자가 블로킹된 시간 |
|---|---:|
| AS-IS (동기 — `@Async` 프록시를 거치지 않는 순수 객체로 재현) | **310ms** |
| TO-BE (`@Async` 프록시 적용된 실제 Spring 빈) | **1ms** |

(시뮬레이션 지연 300ms 기준, AS-IS는 그 지연을 그대로 떠안고, TO-BE는 즉시 반환 후 백그라운드에서
완료 — `verify(vectorStore, timeout(900).times(2))`로 실제로 나중에 호출이 완료되는 것도 같이
검증했다.)

## 참고

- 코드: `ScheduleEmbeddingService`, `MandalartGoalEmbeddingService`, `global/config/AsyncConfig`
- 테스트: `ScheduleEmbeddingServiceTest`, `MandalartGoalEmbeddingServiceTest`,
  `ScheduleEmbeddingAsyncPerformanceTest`
- 커밋: `24d6e78` (`[fix-41] RAG 파이프라인 개선 - 유사도 하한선 추가 + 임베딩 색인/삭제 비동기 전환`)
- 다음 후보(아직 미착수): `schedules` 캐시 evict를 SCAN에서 유저별 버전 카운터로 바꿔 O(1) 무효화로
  전환하는 것 — CLAUDE.md의 캐시 섹션과 별도 메모 참고.
