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

## 진행 순서 권장

```
#1 기반 설정 → #2 회원 → #6 예외처리 → #3 카테고리 → #4 스케줄 → #5 캐싱 → #7 AI → #8 테스트
```
