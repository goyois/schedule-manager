# 백엔드 개발 태스크

> 스택: Java 17, Spring Boot 3.4.0, Spring Data JPA, MySQL 8.x, Redis, Spring AI (Anthropic Claude)

---

## Task #1 [PARTIAL] — 프로젝트 기반 설정

- 도메인별 패키지 구조 생성
  - `domain/user`, `domain/schedule`, `domain/category` — 계획대로 존재. 계획엔 없었지만 `domain/auth`(JWT 로그인/구글 로그인/리프레시)도 별도 도메인으로 분리되어 있음
  - `global/config`, `global/exception`, `global/response` — 존재. **`global/util`은 없음** — 대신 `global/security/util`에 보안 전용 유틸(`JwtUtil`)만 있고, 도메인 공용 유틸 패키지는 만들어지지 않음
- `RedisConfig` — RedisTemplate, CacheManager Bean 설정 (실제 존재)
- **`JpaConfig` 별도 클래스는 없음** — `@EnableJpaAuditing`은 `ScheduleManagerApplication`(메인 클래스)에 직접 선언됨. `BaseEntity`(createdAt/updatedAt)는 `global/common`에 존재(계획은 `global/config` 하위로 상정했었음)
- `ApiResponse<T>` — 공통 응답 포맷 (code, message, data), 실제 존재
- **환경별 프로파일 분리 미완**: `application-local.yml`만 있고 `prod` 프로파일 파일은 없음(`application.yml`의 `spring.profiles.default: local`만 설정됨). `spring-session-jdbc` 의존성과 `spring.session.store-type: jdbc` 설정이 남아있지만, 실제 인증은 JWT(stateless)라 이 세션 설정은 어디서도 쓰이지 않는 죽은 설정임

---

## Task #2 [PARTIAL] — 회원(User) 도메인 구현

**엔티티** (실제 구현 — `nickname` 없음, `username`/`userType`/`authProvider` 추가)
```
User: id, username, email, password (BCrypt), userType (USER/ADMIN), authProvider (LOCAL/GOOGLE), createdAt, updatedAt
```

**API** (실제 구현 — 세션이 아니라 JWT 기반. 인증은 `domain/user`가 아니라 별도 `domain/auth`에서 처리)
| Method | URI | 설명 |
|--------|-----|------|
| POST | `/api/users` | 회원가입 (계획의 `/api/auth/signup` 대신) |
| GET/PUT/DELETE | `/api/users/{id}` | 유저 조회/수정/삭제 |
| POST | `/api/auth/login` | 로그인 → access/refresh token 발급 (세션 아님) |
| POST | `/api/auth/google` | 구글 크레덴셜 로그인 (계획에 없던 기능) |
| POST | `/api/auth/refresh` | refresh token으로 재발급 (Task #9) |
| POST | `/api/auth/logout` | 로그아웃 (access token 블랙리스트 등록) |

- `/api/auth/me`는 구현되지 않음 (프론트가 로그인 응답의 이메일/토큰만으로 현재 유저 정보를 로컬에 들고 있음)
- 세션 저장소 대신 Redis에 JWT 블랙리스트(`blacklist:{token}`)/refresh token(`refresh:{email}`)을 저장 — `spring-session-jdbc` 의존성과 `spring.session.store-type: jdbc` 설정은 남아있지만 실제로는 쓰이지 않음(제거 검토 대상)
- 비밀번호: `BCryptPasswordEncoder` (계획대로)

---

## Task #3 [FIX] — 카테고리(Category) 도메인 구현

**엔티티** (실제 구현 — `color` 필드는 없음)
```
Category: id, name, user (FK, nullable), createdAt, updatedAt
```

**API**
| Method | URI | 설명 |
|--------|-----|------|
| GET | `/api/categories` | 내게 보이는 카테고리 목록 |
| GET | `/api/categories/{id}` | 카테고리 단건 조회 |
| POST | `/api/categories` | 카테고리 생성 (요청자가 소유자로 저장됨) |
| PUT | `/api/categories/{id}` | 카테고리 수정 |
| DELETE | `/api/categories/{id}` | 카테고리 삭제 |

**소유권 모델** — 카테고리를 만든 사람의 역할에 따라 성격이 갈린다

- **ADMIN 이 생성** → "기본 카테고리": 모든 유저/관리자에게 보이지만(전체 공개) **수정·삭제는 아무도 할 수 없음**
  (`DEFAULT_CATEGORY_UPDATE_FORBIDDEN` / `DEFAULT_CATEGORY_DELETE_FORBIDDEN`, 둘 다 403)
- **일반 USER 가 생성** → 그 유저 전용: 목록/단건 조회·이름 중복 검사 모두 본인에게만 보이는 범위로 스코프됨.
  다른 USER 의 비공개 카테고리는 존재 자체가 드러나면 안 되므로 조회/수정/삭제 모두 `CATEGORY_NOT_FOUND`(404)로 응답 (그 유저 것인지 아닌지조차 알 수 없게)
- **소유자 없는 레거시 카테고리**(`user_id = NULL`, 이 기능 도입 이전에 만들어진 카테고리) — 누구에게나 보이고 제한 없이 수정·삭제 가능
- 아직 스케줄이 참조 중인 카테고리는 삭제 불가 (`ScheduleRepository.existsByCategoryId`) — FK 제약 위반이 500 으로 새어나가는 것을 사전 차단
- 이름 중복 검사(`CategoryRepository.existsVisibleDuplicateName`)도 위 가시성 범위로 한정 — 서로 안 보이는 두 USER 가 같은 이름을 써도 충돌하지 않음
- 구현 시 주의: 가시성 쿼리(`findVisibleTo`)에서 `c.user.userType` 처럼 연관 경로를 그냥 쓰면 Hibernate 가 암시적 INNER JOIN 을 만들어 소유자 없는 레거시 카테고리가 결과에서 통째로 빠지는 문제가 있었음 → `LEFT JOIN c.user u` 로 명시해서 해결

**테스트**: `CategoryServiceTest` — 생성 시 소유자 지정, 이름 중복(가시 범위 한정), 목록/단건 조회 가시성, ADMIN 카테고리 수정·삭제 금지, 타 유저 비공개 카테고리 404 처리, 스케줄이 참조 중인 카테고리 삭제 금지, 레거시(소유자 없음) 카테고리는 제약 없음 등

---

## Task #4 [PARTIAL] — 스케줄(Schedule) 도메인 구현

**엔티티** (실제 구현 — `description`/`isAllDay`/`repeatType` 대신 `content`/`status`)
```
Schedule: id, title, content, startAt, endAt, status (PENDING/IN_PROGRESS/COMPLETED/CANCELLED),
          category (FK), user (FK), createdAt, updatedAt
```

**API**
| Method | URI | 설명 |
|--------|-----|------|
| GET | `/api/schedules` | 일정 목록 (`userId`/`categoryId` 선택적 쿼리 파라미터) |
| POST | `/api/schedules` | 일정 생성 |
| GET | `/api/schedules/{id}` | 일정 단건 조회 |
| PUT | `/api/schedules/{id}` | 일정 수정 |
| DELETE | `/api/schedules/{id}` | 일정 삭제 |

- **`from`/`to` 날짜 범위 조회는 구현되지 않음** — 대신 프론트(`dashboard.js`)가 전체 목록을 받아와 일/주/월/년 뷰별로 클라이언트에서 직접 필터링·집계함
- `userId`는 ADMIN 전용 — 일반 `USER`가 넘겨도 무시되고 본인 id로 강제 치환됨(계획에 없던 역할 기반 접근 제어)
- 타인의 일정 단건 조회 시 403 (`SCHEDULE_ACCESS_DENIED`) — 계획대로 구현됨. 다만 수정/삭제(`updateSchedule`/`deleteSchedule`)에는 소유자 검사가 없어, 스케줄 id만 알면 타인 것도 수정·삭제 가능한 상태(알려진 갭)

---

## Task #5 [PARTIAL] — Redis 캐싱 적용

- `@EnableCaching` 활성화 (계획대로, `RedisConfig`)
- 캐시 대상
  - **카테고리 목록 캐싱은 구현되지 않음** — `CategoryService`에 `@Cacheable`/`@CacheEvict`가 전혀 없음
  - 스케줄 목록만 캐싱됨 — 캐시 이름 `schedules`, 키는 `{requesterEmail}-{targetUserId}-{categoryId}`(계획의 `from`/`to` 기반이 아님, Task #4 참고)
- 생성 시에는 `@CacheEvict` 대신 대상 유저와 관련된 키만 골라 지우는 커스텀 evict(`RedisTemplate` + `SCAN`)를 직접 호출 — `@CacheEvict`의 SpEL로는 와일드카드(유저별 타겟) 삭제가 안 되기 때문. 자세한 배경은 스케줄 캐시 무효화 관련 커밋 참고(`perf-1`/`perf-2`)
- **TTL 5분 추가**(`RedisConfig.cacheManager()`의 `entryTtl(Duration.ofMinutes(5))`) — evict 로직이 못 걷어내는 경로가 실제로 있어서(ADMIN이 `userId` 없이 전체 조회한 캐시 키는 evict 패턴에 안 걸림, 카테고리 이름을 바꿔도 그 카테고리를 참조하는 스케줄 캐시는 evict 안 됨) 안전망으로 도입. evict가 정상 동작하는 일반적인 경우엔 체감되지 않고, evict를 놓친 경우에만 "영원히 stale" 대신 "최대 5분 후 자연 회복"으로 바뀜

---

## Task #6 [FIX] — 전역 예외 처리 및 검증

- `BusinessException` + `ErrorCode` enum(상태코드 + 메시지 쌍)으로 커스텀 예외 체계 구성
- `GlobalExceptionHandler`(`@RestControllerAdvice`)로 전역 핸들링, 전부 `ApiResponse.error(code, message)` 포맷으로 응답
  - `BusinessException` → `ErrorCode`에 정의된 상태코드/메시지 그대로
  - `MethodArgumentNotValidException`(`@Valid` 실패) → 400, 첫 번째 필드 오류 메시지
  - `AuthenticationException`(로그인 시 이메일/비밀번호 불일치) → 401, 계정 존재 여부가 드러나지 않도록 원인 무관 고정 메시지
  - `AccessDeniedException` → 403
  - 그 외 예기치 못한 예외 → 500, 내부 메시지는 노출하지 않고 로그만 남김
- `SecurityConfig`에 JSON 포맷 `AuthenticationEntryPoint`/`AccessDeniedHandler` 등록 — `JwtAuthenticationFilter`를 통과하기 전에 걸리는 인증 실패(토큰 없음/무효 토큰)는 컨트롤러에 도달하지 않아 `GlobalExceptionHandler`를 거치지 않으므로, 필터 체인 단계에서도 동일한 `ApiResponse` JSON 포맷으로 응답하도록 별도 처리
- 요청 DTO(`UserRequestDto`, `CategoryRequestDto`, `ScheduleRequestDto`, `LoginRequestDto`, `GoogleLoginRequestDto`, `RefreshTokenRequestDto`)에 `@Valid` + Bean Validation 어노테이션 적용, `spring-boot-starter-validation` 의존성 추가
- 서비스 계층의 raw `IllegalArgumentException`을 전부 `BusinessException`으로 교체 (대표: `USER_NOT_FOUND`/`CATEGORY_NOT_FOUND`/`SCHEDULE_NOT_FOUND`/`DUPLICATE_EMAIL`/`DUPLICATE_CATEGORY`/`SCHEDULE_ACCESS_DENIED`/`MISSING_TOKEN`/`INVALID_REFRESH_TOKEN`/`EXPIRED_REFRESH_TOKEN`/`INVALID_GOOGLE_TOKEN`/`UNVERIFIED_GOOGLE_EMAIL`)

**테스트**: `GlobalExceptionHandlerTest`(신규) — 예외 종류별 상태코드/메시지 매핑 검증. 기존 `AuthServiceTest`/`UserServiceTest`의 `IllegalArgumentException` 단언을 `BusinessException` + `ErrorCode` 단언으로 갱신

---

## Task #7 — AI 일정 추천 기능 구현

**API**
| Method | URI | 설명 |
|--------|-----|------|
| POST | `/api/ai/suggest` | 일정 추천 요청 |
| POST | `/api/ai/summary` | 이번 주 일정 요약 |

- `ChatClient` (Spring AI)로 Claude API 호출
- 사용자 일정 목록을 컨텍스트로 프롬프트 구성
- 응답은 스트리밍 또는 단건 텍스트

---

## Task #8 [PARTIAL] — 테스트 코드 작성

- 서비스 단위 테스트(Mockito, `@ExtendWith(MockitoExtension.class)`) — 계획대로 존재: `AuthServiceTest`, `UserServiceTest`, `CategoryServiceTest`, `ScheduleServiceTest`, `GlobalExceptionHandlerTest`, `JwtUtilTest`
- **Repository 슬라이스 테스트(`@DataJpaTest`)는 없음** — 대신 `@SpringBootTest`로 실제 로컬 MySQL/Redis에 직접 붙는 벤치마크성 테스트(`ScheduleIndexPerformanceTest`, `ScheduleCacheEvictionTest`, `ScheduleCacheEvictionBenchmarkTest`)로 대체됨. 날짜 범위 쿼리 자체가 미구현(Task #4)이라 그 검증도 없음
- **컨트롤러 통합 테스트(MockMvc)는 없음** — 서비스 계층까지만 테스트됨
- **테스트 DB 격리(H2/Testcontainers) 미적용** — 전부 `application-local.yml` 그대로 실제 로컬 MySQL/Redis를 사용(`src/test/resources`에 별도 설정 없음). 특히 `@SpringBootTest` 벤치마크 테스트들은 대량 데이터(약 5.7만 건)를 실제 개발 DB에 직접 넣고 정리하지 않아, 반복 실행 시 개발 DB에 테스트 데이터가 계속 누적되는 부작용이 있음(카테고리 삭제 관련 작업 중 실제로 발견됨)

---

## Task #9 [FIX] — JWT 리프레시 토큰 (Redis TTL)

**목표**: 현재 access token(`spring.jwt.expiration`, 1시간) 만료 시 재로그인해야 하는 문제를 refresh token으로 해소.

**설계**

- `JwtUtil`
  - `generateRefreshToken(String email)` 추가, 만료시간은 별도 프로퍼티 `spring.jwt.refresh-expiration`로 관리
  - 토큰에 `tokenType` claim(`ACCESS` / `REFRESH`) 추가 — refresh token이 access token 대신 `Authorization: Bearer`로 재사용되는 것을 막기 위함 (`JwtAuthenticationFilter`에서 `tokenType == ACCESS`인 토큰만 인증 처리하도록 검증 추가)
- **저장 방식**: 단일 세션 — Redis key `refresh:{email}` (value = refresh token, `EX` = refresh 만료시간). 재로그인 시 같은 키를 덮어써 이전 refresh token은 자동 폐기됨 (기존 `blacklist:{token}` 로그아웃 패턴과 구분되는 별도 키 네임스페이스)
- **로테이션**: `POST /api/auth/refresh` 호출 시 access token과 refresh token을 모두 새로 발급하고 Redis 값을 교체(TTL 갱신). 탈취된 refresh token이 재사용되면 이후 정상 사용자의 재발급 요청이 값 불일치로 실패 → 탈취 감지 가능
- **로그아웃 연동**: 기존 `AuthService.logout`은 access token 블랙리스트만 처리 → `refresh:{email}` 키 삭제도 함께 수행하도록 확장
- Redis 접근은 기존 `JwtAuthenticationFilter.isBlacklisted`와 동일하게 `try/catch (DataAccessException)` fail-open 패턴 적용 (신규 `RedisTemplate` Bean 추가 없이 `RedisConfig`의 기존 `RedisTemplate<String, Object>` 재사용)

**API**
| Method | URI | 설명 |
|--------|-----|------|
| POST | `/api/auth/refresh` | refresh token으로 access/refresh token 재발급 (로테이션) |

- `LoginResponseDto`에 `refreshToken` 필드 추가 (로그인/구글 로그인 응답에도 포함)
- `/api/auth/refresh`는 기존 `SecurityConfig`의 `/api/auth/**` permitAll 범위에 포함되어 별도 설정 불필요

**테스트**: `JwtUtilTest`(신규) — refresh token 생성/검증/타입 구분, `AuthServiceTest` — refresh 성공/실패(불일치·만료·미로그인) 및 logout 시 refresh 키 삭제 케이스 추가

---

## Task #10 [FIX] — `schedules` 테이블 (user_id, category_id) 복합 인덱스

**목표**: `ScheduleService.getSchedules(userId, categoryId)` / `ScheduleRepositoryImpl.searchSchedules`가 `user_id`, `category_id` 두 컬럼을 동시에 필터링하는데, 인덱스는 각 컬럼에 (FK 제약으로 InnoDB가 자동 생성한) 단일 컬럼 인덱스만 있어 MySQL이 둘 중 하나만 인덱스로 타고 나머지는 row-by-row `Filter`로 걸러내던 비효율을 개선.

**분석**

- `start_at`은 코드 어디서도 WHERE/ORDER BY에 쓰이지 않아 인덱싱 대상에서 제외(당초 "user_id/category_id/start_at 모두 인덱스 없음"으로 추정했던 전제를 실제 스키마 확인 후 정정).
- 실사용 패턴: 일반 `USER`는 항상 자기 `user_id`로, 여기에 `categoryId`가 선택적으로 더해짐(둘 다 nullable) → `(user_id, category_id)` 복합 인덱스가 leftmost-prefix로 두 케이스(“user_id만”, “user_id+category_id”)를 모두 커버.

**적용**

- `Schedule` 엔티티에 `@Table(indexes = @Index(name = "idx_schedule_user_category", columnList = "user_id, category_id"))` 추가
- `ddl-auto: update`이고 별도 마이그레이션 도구(Flyway/Liquibase) 없음 → 엔티티 애노테이션만으로 다음 기동 시 인덱스 반영

**벤치마크 (`ScheduleIndexPerformanceTest`, 신규)**

- 현실적인 컨텐션 재현: 대상 유저는 5개 카테고리에 걸쳐 일정 보유(user_id 단독으로는 카테고리가 안 좁혀짐), 대상 카테고리는 다른 15명 유저와 공유(category_id 단독으로는 유저가 안 좁혀짐) — 총 약 5.7만 건
- as-is: `EXPLAIN` 상 `user_id` 인덱스로 1만 건 조회 후 `category_id`는 Filter로 제거, optimizer cost 975, 평균 응답 10.85ms(최대 19ms)
- to-be: 복합 인덱스로 2,000건 정확히 매치, cost 251(-74%), 평균 응답 9.6ms(최대 13ms, 변동폭 감소)
- 로컬 데이터셋은 InnoDB 버퍼풀에 다 들어가 wall-clock 차이는 크지 않음 — `EXPLAIN`의 cost/rows-examined 감소가 핵심 근거

**테스트**: `ScheduleIndexPerformanceTest`(신규) — `EXPLAIN` 실행계획 로그 + warmup/measure 라운드로 응답시간 측정(기존 `ScheduleServiceTest.apiPerformance` 컨벤션 준용, 캐시 우회하고 `ScheduleRepositoryImpl.searchSchedules` 직접 호출)

---

## Task #11 [FIX] — 뷰단 상태 코드별 UX 에러 처리

**목표**: Task #6으로 백엔드가 상황별 상태 코드(400/401/403/404/409/500)를 정확히 내려주게 되는 것을 전제로, 프론트(`api.js`/`dashboard.js`/`login.js`/`signup.js`)가 그 상태 코드를 구분해 사용자에게 적절한 UX를 보여주도록 한다. 지금은 모든 실패가 `err.message` 하나로만 뭉뚱그려 토스트에 표시되고, 401/403을 동일하게 취급해 403(권한 없음)에도 불필요하게 refresh token 재시도가 발생한다.

- `api.js`
  - `ApiError`(status, message 보유) 도입 — 호출부가 `err.status`로 분기 가능하게
  - refresh-token 재시도는 **401**에서만 수행. 403은 인증은 유효하지만 권한이 없는 것이라 재발급해도 소용없으므로 재시도 없이 바로 실패 처리
  - 네트워크 자체 실패(fetch reject, 오프라인 등) 시 브라우저 raw 에러 대신 "네트워크 연결을 확인해주세요" 같은 문구로 감싸기
  - 백엔드가 message를 못 내려준 경우를 대비한 상태 코드별 기본 문구(`defaultMessageForStatus`)도 상태 코드 노출("요청 실패 (500)") 대신 다음 행동을 알 수 있는 자연스러운 문장으로 작성
- `dashboard.js`
  - 일정 수정/삭제/상태변경 실패 시 상태코드별로 분기(`notifyScheduleMutationError`): 404(이미 삭제된 대상) → 목록 새로고침 + 안내, 403(소유자 아님) → 전용 안내, 그 외 → 서버 메시지
  - 카테고리 삭제 실패 시 403(ADMIN 기본 카테고리 삭제 시도 등)은 서버 메시지 자체가 완결된 안내문이라 접두어 없이 그대로 노출, 그 외는 "~하지 못했습니다. {메시지}" 형태의 두 문장으로 정리
  - 그 외 목록 로드 실패 등도 "액션 실패: {raw message}" 콜론 연결 대신 "~하지 못했습니다. {메시지}" 자연스러운 문장으로 통일
- 로그인/회원가입은 백엔드 메시지(이메일/비밀번호 불일치, 이메일 중복 등)를 그대로 노출하는 현재 방식 유지, 네트워크 실패만 위 공통 처리 혜택을 받음

**의존성**: Task #6 완료 후 진행 (백엔드가 상태 코드를 구분해서 내려줘야 프론트 분기가 의미 있음)

---

## 진행 순서 권장

```
#1 기반 설정 → #2 회원 → #6 예외처리 → #3 카테고리 → #4 스케줄 → #5 캐싱 → #7 AI → #8 테스트
```
