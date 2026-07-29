# Schedule Manager

스케줄 관리 백엔드 서비스입니다. JWT 기반 인증(액세스/리프레시 토큰 로테이션), Redis 캐싱/로그아웃 블랙리스트, Prometheus·Grafana 모니터링을 갖춘 Java 17 + Spring Boot 3.4.0 프로젝트이며, 정적 리소스로 제공되는 대시보드(캘린더·방사형 차트·AI 챗봇 패널) 프론트엔드를 포함합니다. 일정 CRUD 외에 반복 일정(요일 지정), 만다라트, Claude 기반 AI 일정 추천 챗봇, 상태 자동 전환 등 자동화 설정을 제공합니다.

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3.4.0 |
| ORM | Spring Data JPA (Hibernate) |
| Database | PostgreSQL 16+ |
| Cache | Redis (일정 목록 캐시, JWT 로그아웃 블랙리스트) |
| Auth | Spring Security + JWT (jjwt), Stateless |
| Monitoring | Spring Actuator + Micrometer + Prometheus + Grafana |
| Frontend | 정적 HTML/CSS/JS (`src/main/resources/static`) |
| Build | Gradle |
| Utility | Lombok |

> `spring-ai-anthropic-spring-boot-starter`(`ChatClient`)를 사용해 AI 일정 추천 챗봇(`domain/ai`)이 실제로 Anthropic API를 호출합니다. 구현 배경은 `AI_STRATEGY.md` 참고.

---

## 주요 기능

- **회원 관리** - 회원가입(`POST /api/users`), 이메일/비밀번호 로그인, 구글 로그인(GIS ID 토큰 검증). 세션이 아닌 JWT 액세스 토큰 기반이며, 액세스 토큰 만료 시 리프레시 토큰으로 재발급(로테이션)합니다. 로그아웃된 액세스 토큰과 폐기된 리프레시 토큰은 Redis에 등록되어 재사용을 막습니다.
- **권한 분리** - `USER` / `ADMIN` 두 역할. ADMIN은 임의 유저의 일정을 조회할 수 있고, USER는 본인 소유 일정에만 접근할 수 있습니다.
- **스케줄 CRUD** - 일정 생성·조회·수정·삭제, 상태(`PENDING` / `IN_PROGRESS` / `COMPLETED` / `CANCELLED`) 관리. 상태 자동 전환 모드를 켜면 시작/종료 시각에 맞춰 서버가 상태를 자동으로 바꿔줍니다.
- **반복 일정** - 요일을 지정해(`MONDAY,WEDNESDAY,...`) 매주 반복되는 일정을 등록하면, 서버가 개별 일정(occurrence)으로 펼쳐 생성합니다.
- **카테고리 관리** - 일정 분류를 위한 카테고리 CRUD
- **만다라트** - 목표를 9x9 만다라트 보드로 관리하는 CRUD. 중앙 9칸(핵심 목표 + 세부목표 8개)을 채운 뒤 "AI로 채우기"를 누르면, 바깥 8개 블록의 자기 블록 중심 칸(세부목표 사본)은 그대로 복사하고 나머지 실행항목 최대 64칸은 Claude가 채운다(이미 채워진 칸은 절대 건드리지 않음). AI 챗봇에 "OO 만다라트 채워줘"라고 요청해도 동일하게 동작한다.
- **AI 일정 추천 챗봇** - Claude(`ChatClient`)와 구조화된 응답 기반으로 지속 대화합니다. 모델이 매 답변을 `SCHEDULE_RECOMMENDATION`(새 일정 추천)/`SCHEDULE_UPDATE`(기존 일정 수정 제안)/`MANDALART_FILL`(만다라트 채우기)/`GENERAL`(그 외 일반 답변) 중 하나로 분류해서, 그에 맞는 UI만 보여줍니다. 새 일정 추천은 "수동 등록"(폼 검토 후 저장) / "자동 등록"(즉시 저장, "AI 추천 자동 등록" 설정이 켜져 있으면 버튼 클릭 없이 응답 즉시 자동 반영) 중 선택할 수 있고, 기존 일정 수정 제안은 "수정"(수정 폼을 열어 검토 후 저장) / "수정 반영"(추가 창 없이 즉시 반영) 중 선택할 수 있습니다. "OO 만다라트 채워줘"처럼 요청하면 대상 보드를 찾아 그 자리에서 바로 채우고 결과만 보여줍니다(빈 칸만 채우므로 검토 단계 없이 자동 적용). ADMIN을 제외한 일반 유저는 Redis 기반으로 분당 5회까지만 요청할 수 있습니다.
- **실시간 반영(SSE)** - 일정이 생성/수정/삭제/자동 상태 전환될 때 `GET /api/schedules/stream`으로 연결된 브라우저 탭에 즉시 반영합니다(폴링 없음).
- **Redis 캐싱** - 일정 목록 조회 결과를 캐싱하며, Redis 장애 시 예외를 던지지 않고 DB 조회로 폴백합니다(fail-open). 일정 변경 시에는 캐시 전체가 아니라 해당 유저 키 패턴만 `SCAN` 기반으로 무효화합니다.
- **대시보드 UI** - 정적 프론트엔드로 제공되는 7×7 달력, 12시간제 아날로그 시계(중앙에 낮/밤에 따라 ☀️/🌙 표시), 카테고리별 방사형 차트, AI 챗봇 패널, 성취도 위젯
- **모니터링** - Actuator + Micrometer로 Prometheus 메트릭을 노출하고, Grafana 대시보드로 시각화 (`monitoring/docker-compose.yml`)
- **일정 알림(이메일/푸시)**, **AI 오늘의 운세** - 미구현 (예정)

---

## 로컬 실행 환경 설정

### 사전 요구사항

- Java 17+
- PostgreSQL 16+
- Redis 7.x

### 데이터베이스 설정

```sql
CREATE DATABASE api;
```

앱을 최초 기동해 (`ddl-auto: update`로) 스키마가 생성된 뒤, 이메일/카테고리 이름 조회를
MySQL 때(`utf8mb4_unicode_ci`)와 동일하게 대소문자 구분 없이 동작시키려면 아래 DDL을 한 번
실행해야 한다 (`ddl-auto: validate`로 바꾼 뒤에는 스키마를 이런 수동 DDL로 관리한다 — 자세한
배경은 `TASK.md` 참고):

```sql
CREATE COLLATION IF NOT EXISTS case_insensitive
    (provider = icu, locale = 'und-u-ks-level2', deterministic = false);

ALTER TABLE users      ALTER COLUMN email TYPE varchar(255) COLLATE case_insensitive;
ALTER TABLE categories ALTER COLUMN name  TYPE varchar(255) COLLATE case_insensitive;
```

### application-local.yml 설정

`src/main/resources/application-local.yml`은 `.gitignore`에 포함되어 있어 저장소에 커밋되지 않습니다. 로컬에서 직접 만들어야 하며, 최소한 아래 값이 필요합니다.

```yaml
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://localhost:5432/api
    username: your_username
    password: your_password
  jpa:
    hibernate:
      ddl-auto: update
  data:
    redis:
      host: localhost
      port: 6379
  ai:
    anthropic:
      api-key: your_anthropic_api_key
      model: claude-3-5-sonnet-latest
  jwt:
    secret: your-jwt-secret-key-must-be-at-least-256bits-long
    expiration: 3600000

google:
  oauth:
    client-id: your_google_oauth_client_id
```

> `spring.jwt.secret`은 HMAC-SHA 서명에 쓰이므로 최소 256bit(32자) 이상이어야 합니다. `spring.ai.anthropic.api-key`는 AI 일정 추천 챗봇(`POST /api/ai/chat/messages`)이 실제로 호출하는 유효한 Anthropic API 키여야 합니다(과금 발생). `google.oauth.client-id`는 구글 로그인(`POST /api/auth/google`)에서 ID 토큰의 audience 검증에 쓰이며, [Google Cloud Console](https://console.cloud.google.com/)에서 웹 애플리케이션용 OAuth 2.0 클라이언트 ID를 발급받아 채워야 합니다(Client Secret은 필요 없음 — ID 토큰 검증만 하는 방식이라 Client ID만 사용). 값이 없으면 부팅 자체가 실패하므로, 아직 구글 로그인을 안 쓰더라도 임의의 문자열을 채워둬야 합니다.

### 실행

```bash
./gradlew bootRun
```

기동 후 `http://localhost:8080`으로 접속하면 로그인 화면(`/login`)으로 연결되고, 로그인 후 `/dashboard`에서 일정 관리 UI를 사용할 수 있습니다.

### 모니터링 스택 (선택)

```bash
docker compose -f monitoring/docker-compose.yml up
```

Prometheus: `http://localhost:9090`, Grafana: `http://localhost:3000` (admin/admin). 앱은 `/actuator/prometheus`를 인증 없이 노출해 스크레이핑을 허용합니다.

---

## 프로젝트 구조

```
src/main/java/com/example/schedule_manager/
├── domain/
│   ├── user/               # 회원 CRUD, 자동 상태 전환·AI 자동 등록 설정 (controller / service / repository / entity / dto)
│   ├── auth/               # 로그인(비밀번호/구글)·로그아웃·리프레시 — JWT 발급 및 Redis 블랙리스트/리프레시 저장 (controller / service / dto)
│   ├── schedule/           # 일정 CRUD, Redis 캐싱, SSE 실시간 반영, ADMIN/USER 권한 분기 (controller / service / repository / entity / dto)
│   ├── recurringschedule/  # 반복 일정(요일 지정) — occurrence로 펼쳐 schedule에 생성 (controller / service / repository / entity / dto)
│   ├── category/           # 카테고리 CRUD (controller / service / repository / entity / dto)
│   ├── ai/                 # AI 일정 추천 챗봇 — ChatClient 연동, 대화 이력 저장/등록 (controller / service / repository / entity / dto)
│   └── mandalart/          # 만다라트 보드/셀 CRUD + AI로 빈 칸 채우기 (controller / service / repository / entity / dto)
├── global/
│   ├── security/    # config/SecurityConfig, filter/JwtAuthenticationFilter, util/JwtUtil, service/CustomUserDetailsService
│   ├── config/      # RedisConfig(캐시), CacheFailSafeErrorHandler, GoogleOAuthConfig, AiConfig(ChatClient 빈)
│   ├── exception/   # GlobalExceptionHandler(@RestControllerAdvice), BusinessException, ErrorCode
│   ├── common/      # BaseEntity (createdAt/updatedAt)
│   ├── response/    # ApiResponse<T>
│   └── controller/  # ViewController — 정적 페이지 forward
└── ScheduleManagerApplication.java

src/main/resources/static/   # 정적 프론트엔드 (index/signup/dashboard/mandalart/settings.html, css, js)
monitoring/                  # Prometheus + Grafana docker-compose
```

전역 예외 처리는 `global/exception/GlobalExceptionHandler`(`@RestControllerAdvice`)가 담당합니다. 서비스 계층은 not-found/권한/충돌 오류를 `ErrorCode`(HTTP 상태 + 메시지 매핑)를 담은 `BusinessException`으로 던지고, 핸들러가 이를 그대로 `ApiResponse.error(...)`로 변환합니다. `@Valid` 검증 실패, 인증 실패(`AuthenticationException`), 권한 없음(`AccessDeniedException`), 그 외 예기치 못한 예외도 각각 매핑되어 더 이상 원인 불명의 500으로 새어나가지 않습니다.

---

## API 명세

| Method | URI | 설명 | 인증 |
|--------|-----|------|------|
| POST | `/api/users` | 회원가입 | 불필요 |
| GET | `/api/users/me` | 내 정보 조회 | 필요 |
| GET / PUT / DELETE | `/api/users/{id}` | 회원 조회 / 수정 / 삭제 | 필요 |
| PUT | `/api/users/me/auto-status-mode` | 일정 상태 자동 전환 on/off | 필요 |
| PUT | `/api/users/me/ai-auto-register` | AI 추천 자동 등록 on/off | 필요 |
| GET | `/api/auth/google/client-id` | 구글 로그인용 OAuth client-id 조회(공개값) | 불필요 |
| POST | `/api/auth/login` | 이메일/비밀번호 로그인 (액세스+리프레시 토큰 발급) | 불필요 |
| POST | `/api/auth/google` | 구글 ID 토큰 로그인 (get-or-create) | 불필요 |
| POST | `/api/auth/refresh` | 리프레시 토큰으로 액세스/리프레시 토큰 재발급(로테이션) | 불필요 (리프레시 토큰 필요) |
| POST | `/api/auth/logout` | 로그아웃 (액세스 토큰 블랙리스트 등록 + 리프레시 토큰 폐기) | 필요 (Bearer 토큰) |
| GET | `/api/schedules?userId=&categoryId=` | 일정 목록 조회 — `userId`는 ADMIN에게만 유효, USER는 본인 것만 조회됨 | 필요 |
| POST | `/api/schedules` | 일정 생성 | 필요 |
| GET | `/api/schedules/{id}` | 일정 단건 조회 (본인 소유만, ADMIN 예외) | 필요 |
| PUT | `/api/schedules/{id}` | 일정 수정 | 필요 |
| DELETE | `/api/schedules/{id}` | 일정 삭제 | 필요 |
| GET | `/api/schedules/board?status=&page=&size=&categoryId=` | 보드 뷰 상태 컬럼 하나를 "오늘" 범위로 페이징 조회(LIMIT/OFFSET) — 보드의 "더보기" 전용 | 필요 |
| GET | `/api/schedules/stream` | 일정 변경 실시간 반영 (SSE, 토큰은 쿼리 파라미터로 전달) | 필요 |
| POST | `/api/recurring-schedules` | 반복 일정(요일 지정) 등록 | 필요 |
| GET | `/api/recurring-schedules` | 반복 일정 목록 조회 | 필요 |
| DELETE | `/api/recurring-schedules/{id}` | 반복 일정 삭제 | 필요 |
| GET | `/api/categories` | 카테고리 목록 | 필요 |
| POST | `/api/categories` | 카테고리 생성 | 필요 |
| GET / PUT / DELETE | `/api/categories/{id}` | 카테고리 조회 / 수정 / 삭제 | 필요 |
| POST | `/api/mandalart` | 만다라트 보드 생성 | 필요 |
| GET | `/api/mandalart` | 만다라트 보드 목록 | 필요 |
| GET | `/api/mandalart/{boardId}` | 만다라트 보드 조회 | 필요 |
| PUT | `/api/mandalart/{boardId}/cells/{row}/{col}` | 만다라트 셀 수정 | 필요 |
| POST | `/api/mandalart/{boardId}/ai-fill` | 중앙 9칸을 기준으로 나머지 빈 칸을 AI로 채우기(이미 채워진 칸은 유지) | 필요 |
| DELETE | `/api/mandalart/{boardId}` | 만다라트 보드 삭제 | 필요 |
| GET | `/api/ai/chat/messages` | AI 챗봇 대화 이력 조회 | 필요 |
| POST | `/api/ai/chat/messages` | AI 챗봇에 메시지 전송 (구조화된 추천 응답 수신, 만다라트 채우기 요청도 이 엔드포인트로 처리) | 필요 |
| PATCH | `/api/ai/chat/messages/{id}/register` | AI 추천 메시지를 실제 일정으로 등록 | 필요 |
| DELETE | `/api/ai/chat/messages` | AI 챗봇 대화 이력 초기화 | 필요 |

### 아직 미구현

- 일정 알림 (이메일 / 푸시)
- AI 오늘의 운세
- 캘린더/시계/레이더용 `GET /api/schedules`(전체 목록)는 여전히 페이지네이션 없이 전량 조회 — 보드 뷰(`GET /api/schedules/board`)만 서버 페이징 적용됨 (캘린더 뷰는 특정 날짜 범위 전체가 필요해 의도적으로 그대로 둠)
- `prod` 스프링 프로파일 (현재 `application-local.yml`만 존재)

---

## 개발 태스크

태스크 목록은 [TASKS.md](./TASKS.md)를 참고하세요. TASKS.md는 최초 설계 문서로, 세션 기반 인증 등 일부 항목이 실제 구현(JWT 기반 인증)과 다릅니다 — 인증 방식은 위 "주요 기능" 설명을 기준으로 보면 됩니다.

---

## 테스트

```bash
./gradlew test
```
