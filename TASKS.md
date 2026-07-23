# 백엔드 개발 태스크

> 스택: Java 17, Spring Boot 3.4.0, Spring Data JPA, MySQL 8.x, Redis, Spring AI (Anthropic Claude)

---

## Task #1 — 프로젝트 기반 설정

- 도메인별 패키지 구조 생성
  - `domain/user`, `domain/schedule`, `domain/category`
  - `global/config`, `global/exception`, `global/response`, `global/util`
- `RedisConfig` — RedisTemplate, CacheManager Bean 설정
- `JpaConfig` — `@EnableJpaAuditing`, BaseEntity (createdAt, updatedAt) 정의
- `ApiResponse<T>` — 공통 응답 포맷 (code, message, data)
- `application.yml` — Redis 연결 설정 추가, 환경별 프로파일 분리 (`local` / `prod`)

---

## Task #2 — 회원(User) 도메인 구현

**엔티티**
```
User: id, email, password (BCrypt), nickname, createdAt, updatedAt
```

**API**
| Method | URI | 설명 |
|--------|-----|------|
| POST | `/api/auth/signup` | 회원가입 |
| POST | `/api/auth/login` | 로그인 (세션 발급) |
| POST | `/api/auth/logout` | 로그아웃 (세션 무효화) |
| GET | `/api/auth/me` | 내 정보 조회 |

- 세션 저장소: Redis (`spring.session.store-type: redis`)
- 비밀번호: `BCryptPasswordEncoder`

---

## Task #3 — 카테고리(Category) 도메인 구현

**엔티티**
```
Category: id, name, color (hex), user (FK), createdAt, updatedAt
```

**API**
| Method | URI | 설명 |
|--------|-----|------|
| GET | `/api/categories` | 내 카테고리 목록 |
| POST | `/api/categories` | 카테고리 생성 |
| PUT | `/api/categories/{id}` | 카테고리 수정 |
| DELETE | `/api/categories/{id}` | 카테고리 삭제 |

- 카테고리는 로그인한 사용자 소유, 타인 접근 불가

---

## Task #4 — 스케줄(Schedule) 도메인 구현

**엔티티**
```
Schedule: id, title, description, startAt, endAt, isAllDay,
          repeatType (NONE/DAILY/WEEKLY/MONTHLY),
          category (FK, nullable), user (FK), createdAt, updatedAt
```

**API**
| Method | URI | 설명 |
|--------|-----|------|
| GET | `/api/schedules?from=&to=` | 날짜 범위 내 일정 목록 |
| POST | `/api/schedules` | 일정 생성 |
| GET | `/api/schedules/{id}` | 일정 단건 조회 |
| PUT | `/api/schedules/{id}` | 일정 수정 |
| DELETE | `/api/schedules/{id}` | 일정 삭제 |

- `from` / `to` 파라미터로 월별·주별 조회 지원
- 타인의 일정 접근 시 403

---

## Task #5 — Redis 캐싱 적용

- `@EnableCaching` 활성화
- 캐시 대상
  - 카테고리 목록 (`categories::{userId}`) — TTL 10분
  - 스케줄 목록 (`schedules::{userId}::{from}::{to}`) — TTL 5분
- 생성·수정·삭제 시 `@CacheEvict`로 캐시 무효화

---

## Task #6 — 전역 예외 처리 및 검증

- `BusinessException` + `ErrorCode` enum으로 커스텀 예외 체계 구성
- `@RestControllerAdvice`로 전역 핸들링
  - `MethodArgumentNotValidException` → 400
  - `BusinessException` → ErrorCode에 정의된 상태코드
  - 미인증 접근 → 401
  - 권한 없음 → 403
- 요청 DTO에 `@Valid` + Bean Validation 어노테이션 적용

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

## Task #8 — 테스트 코드 작성

- 서비스 단위 테스트 (Mockito) — 각 도메인 서비스
- Repository 슬라이스 테스트 (`@DataJpaTest`) — 날짜 범위 쿼리 검증
- 컨트롤러 통합 테스트 (`@SpringBootTest` + MockMvc) — 인증 포함
- 테스트 DB: H2 in-memory 또는 Testcontainers MySQL

---

## Task #9 — JWT 리프레시 토큰 (Redis TTL)

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

## Task #10 — `schedules` 테이블 (user_id, category_id) 복합 인덱스

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

## Task #11 — 뷰단 상태 코드별 UX 에러 처리

**목표**: Task #6으로 백엔드가 상황별 상태 코드(400/401/403/404/409/500)를 정확히 내려주게 되는 것을 전제로, 프론트(`api.js`/`dashboard.js`/`login.js`/`signup.js`)가 그 상태 코드를 구분해 사용자에게 적절한 UX를 보여주도록 한다. 지금은 모든 실패가 `err.message` 하나로만 뭉뚱그려 토스트에 표시되고, 401/403을 동일하게 취급해 403(권한 없음)에도 불필요하게 refresh token 재시도가 발생한다.

- `api.js`
  - `ApiError` (status, message 보유) 도입 — 호출부가 `err.status`로 분기 가능하게
  - refresh-token 재시도는 **401**에서만 수행. 403은 인증은 유효하지만 권한이 없는 것이라 재발급해도 소용없으므로 재시도 없이 바로 실패 처리
  - 네트워크 자체 실패(fetch reject, 오프라인 등) 시 브라우저 raw 에러 대신 "네트워크 연결을 확인해주세요" 같은 문구로 감싸기
  - 백엔드가 message를 못 내려준 경우(네트워크 실패 등)를 대비해 상태 코드별 기본 한국어 문구 fallback 매핑
- `dashboard.js`
  - 일정 수정/삭제/상태변경에서 404(이미 삭제된 대상) → 목록 새로고침 + 안내, 403(소유자 아님) → 권한 안내로 구분
- 로그인/회원가입은 백엔드 메시지(이메일/비밀번호 불일치, 이메일 중복 등)를 그대로 노출하는 현재 방식 유지, 네트워크 실패만 위 공통 처리 혜택을 받음

**의존성**: Task #6 완료 후 진행 (백엔드가 상태 코드를 구분해서 내려줘야 프론트 분기가 의미 있음)

---

## 진행 순서 권장

```
#1 기반 설정 → #2 회원 → #6 예외처리 → #3 카테고리 → #4 스케줄 → #5 캐싱 → #7 AI → #8 테스트
```
