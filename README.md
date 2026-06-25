# Schedule Manager

스케줄 관리 백엔드 서비스입니다. AI 기반 일정 추천 기능을 포함하며, Java 17 + Spring Boot 3.4.0 환경에서 동작합니다.

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3.4.0 |
| ORM | Spring Data JPA (Hibernate) |
| Database | MySQL 8.x |
| Cache / Session | Redis |
| AI | Spring AI + Anthropic Claude |
| Build | Gradle |
| Utility | Lombok |

---

## 주요 기능

- **회원 관리** - 회원가입, 로그인, 로그아웃 (세션 기반 인증)
- **스케줄 CRUD** - 일정 생성·조회·수정·삭제
- **카테고리 관리** - 일정 분류를 위한 카테고리 태깅
- **반복 일정** - 일별·주별·월별 반복 설정
- **일정 알림** - 이메일 / 푸시 알림 (예정)
- **AI 일정 추천** - Claude API를 활용한 일정 제안 및 요약

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

### application.yml 설정

`src/main/resources/application.yml`에서 아래 값을 환경에 맞게 수정합니다.

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/api?serverTimezone=Asia/Seoul
    username: root
    password: your_password

  data:
    redis:
      host: localhost
      port: 6379

  ai:
    anthropic:
      api-key: your_anthropic_api_key
      model: claude-3-5-sonnet-latest
```

> API 키 등 민감한 값은 환경변수 또는 별도 설정 파일로 관리하세요. `application.yml`을 `.gitignore`에 추가하거나 `application-secret.yml`을 분리하는 방법을 권장합니다.

### 실행

```bash
./gradlew bootRun
```

---

## 프로젝트 구조

```
src/main/java/com/example/schedule_manager/
├── domain/
│   ├── user/
│   │   ├── controller/
│   │   ├── service/
│   │   ├── repository/
│   │   └── entity/
│   ├── schedule/
│   │   ├── controller/
│   │   ├── service/
│   │   ├── repository/
│   │   └── entity/
│   └── category/
│       ├── controller/
│       ├── service/
│       ├── repository/
│       └── entity/
├── global/
│   ├── config/          # Security, Redis, JPA 설정
│   ├── exception/       # 전역 예외 처리
│   ├── response/        # 공통 응답 포맷
│   └── util/
└── ScheduleManagerApplication.java
```

---

## API 명세 (예정)

| Method | URI | 설명 |
|--------|-----|------|
| POST | `/api/auth/signup` | 회원가입 |
| POST | `/api/auth/login` | 로그인 |
| POST | `/api/auth/logout` | 로그아웃 |
| GET | `/api/schedules` | 내 일정 목록 조회 |
| POST | `/api/schedules` | 일정 생성 |
| GET | `/api/schedules/{id}` | 일정 단건 조회 |
| PUT | `/api/schedules/{id}` | 일정 수정 |
| DELETE | `/api/schedules/{id}` | 일정 삭제 |
| GET | `/api/categories` | 카테고리 목록 |
| POST | `/api/categories` | 카테고리 생성 |
| POST | `/api/ai/suggest` | AI 일정 추천 |

---

## 개발 태스크

태스크 목록은 [TASKS.md](./TASKS.md)를 참고하세요.

---

## 테스트

```bash
./gradlew test
```
