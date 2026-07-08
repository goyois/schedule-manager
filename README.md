# Schedule Manager

스케줄 관리 백엔드 서비스입니다. JWT 기반 인증, Redis 캐싱/로그아웃 블랙리스트, Prometheus·Grafana 모니터링을 갖춘 Java 17 + Spring Boot 3.4.0 프로젝트이며, 정적 리소스로 제공되는 대시보드(캘린더·방사형 차트) 프론트엔드를 포함합니다.

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3.4.0 |
| ORM | Spring Data JPA (Hibernate) |
| Database | MySQL 8.x |
| Cache | Redis (일정 목록 캐시, JWT 로그아웃 블랙리스트) |
| Auth | Spring Security + JWT (jjwt), Stateless |
| Monitoring | Spring Actuator + Micrometer + Prometheus + Grafana |
| Frontend | 정적 HTML/CSS/JS (`src/main/resources/static`) |
| Build | Gradle |
| Utility | Lombok |

> `spring-ai-anthropic-spring-boot-starter` 의존성과 설정값은 있지만, AI 일정 추천 기능 자체는 아직 구현되지 않았습니다. (아래 "예정" 참고)

---

## 주요 기능

- **회원 관리** - 회원가입(`POST /api/users`), 로그인/로그아웃. 세션이 아닌 JWT 액세스 토큰 기반이며, 로그아웃된 토큰은 Redis 블랙리스트로 등록되어 재사용을 막습니다.
- **권한 분리** - `USER` / `ADMIN` 두 역할. ADMIN은 임의 유저의 일정을 조회할 수 있고, USER는 본인 소유 일정에만 접근할 수 있습니다.
- **스케줄 CRUD** - 일정 생성·조회·수정·삭제, 상태(`PENDING` / `IN_PROGRESS` / `COMPLETED` / `CANCELLED`) 관리
- **카테고리 관리** - 일정 분류를 위한 카테고리 CRUD
- **Redis 캐싱** - 일정 목록 조회 결과를 캐싱하며, Redis 장애 시 예외를 던지지 않고 DB 조회로 폴백합니다(fail-open).
- **대시보드 UI** - 정적 프론트엔드로 제공되는 7×7 달력, 24시간 아날로그 시간표, 카테고리별 방사형 차트
- **모니터링** - Actuator + Micrometer로 Prometheus 메트릭을 노출하고, Grafana 대시보드로 시각화 (`monitoring/docker-compose.yml`)
- **일정 알림(이메일/푸시)**, **AI 일정 추천/요약** - 미구현 (예정)

---

## 로컬 실행 환경 설정

### 사전 요구사항

- Java 17+
- MySQL 8.x
- Redis 7.x

### 데이터베이스 설정

```sql
CREATE DATABASE api CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### application-local.yml 설정

`src/main/resources/application-local.yml`은 `.gitignore`에 포함되어 있어 저장소에 커밋되지 않습니다. 로컬에서 직접 만들어야 하며, 최소한 아래 값이 필요합니다.

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/api?serverTimezone=Asia/Seoul
    username: root
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
```

> `spring.jwt.secret`은 HMAC-SHA 서명에 쓰이므로 최소 256bit(32자) 이상이어야 합니다. `spring.ai.anthropic.api-key`는 AI 기능이 아직 미구현이라 실제로 호출되진 않지만, 스타터 의존성이 값을 요구하므로 임의의 문자열이라도 채워둬야 부팅됩니다.

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
│   ├── user/       # 회원 CRUD (controller / service / repository / entity / dto)
│   ├── auth/       # 로그인·로그아웃 — JWT 발급 및 블랙리스트 등록 (controller / service / dto)
│   ├── schedule/   # 일정 CRUD, Redis 캐싱, ADMIN/USER 권한 분기 (controller / service / repository / entity / dto)
│   └── category/   # 카테고리 CRUD (controller / service / repository / entity / dto)
├── global/
│   ├── security/   # SecurityConfig, JwtAuthenticationFilter, JwtUtil, CustomUserDetailsService
│   ├── config/     # RedisConfig(캐시), CacheFailSafeErrorHandler
│   ├── common/     # BaseEntity (createdAt/updatedAt)
│   ├── response/   # ApiResponse<T>
│   └── controller/ # ViewController — 정적 페이지 forward
└── ScheduleManagerApplication.java

src/main/resources/static/   # 정적 프론트엔드 (index/signup/dashboard.html, css, js)
monitoring/                  # Prometheus + Grafana docker-compose
```

전역 예외 처리(`@RestControllerAdvice`)는 아직 없습니다. 서비스 계층은 not-found/권한 오류를 `IllegalArgumentException`으로 던지며, 현재는 매핑되지 않은 500으로 응답합니다.

---

## API 명세

| Method | URI | 설명 | 인증 |
|--------|-----|------|------|
| POST | `/api/users` | 회원가입 | 불필요 |
| GET / PUT / DELETE | `/api/users/{id}` | 회원 조회 / 수정 / 삭제 | 필요 |
| POST | `/api/auth/login` | 로그인 (JWT 발급) | 불필요 |
| POST | `/api/auth/logout` | 로그아웃 (토큰 블랙리스트 등록) | 필요 (Bearer 토큰) |
| GET | `/api/schedules?userId=&categoryId=` | 일정 목록 조회 — `userId`는 ADMIN에게만 유효, USER는 본인 것만 조회됨 | 필요 |
| POST | `/api/schedules` | 일정 생성 | 필요 |
| GET | `/api/schedules/{id}` | 일정 단건 조회 (본인 소유만, ADMIN 예외) | 필요 |
| PUT | `/api/schedules/{id}` | 일정 수정 | 필요 |
| DELETE | `/api/schedules/{id}` | 일정 삭제 | 필요 |
| GET | `/api/categories` | 카테고리 목록 | 필요 |
| POST | `/api/categories` | 카테고리 생성 | 필요 |
| GET / PUT / DELETE | `/api/categories/{id}` | 카테고리 조회 / 수정 / 삭제 | 필요 |

### 예정 (미구현)

| Method | URI | 설명 |
|--------|-----|------|
| POST | `/api/ai/suggest` | AI 일정 추천 |
| POST | `/api/ai/summary` | 이번 주 일정 요약 |

일정 알림(이메일 / 푸시)도 아직 구현되지 않았습니다.

---

## 개발 태스크

태스크 목록은 [TASKS.md](./TASKS.md)를 참고하세요. TASKS.md는 최초 설계 문서로, 세션 기반 인증 등 일부 항목이 실제 구현(JWT 기반 인증)과 다릅니다 — 인증 방식은 위 "주요 기능" 설명을 기준으로 보면 됩니다.

---

## 테스트

```bash
./gradlew test
```
