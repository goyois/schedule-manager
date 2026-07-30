# DB 마이그레이션: MySQL → PostgreSQL

> 목적: 이후 Spring AI 기반 RAG(Retrieval-Augmented Generation)를 구축하기 위한 사전 작업.
> 별도 벡터 DB를 추가로 두지 않고, 애플리케이션 DB 자체를 임베딩 저장/검색까지 겸할 수 있는
> PostgreSQL로 옮긴다. `AI_STRATEGY.md`(AI 일정 추천, `/api/ai/suggest`)의 후속 확장이며,
> 그 문서가 다룬 "프롬프트에 최근 일정 텍스트를 통째로 넣는" 방식에서 "관련 일정/기록만
> 벡터 검색으로 추려 넣는" RAG 방식으로 넘어가기 위한 인프라 변경이다.

## 왜 PostgreSQL인가 — Spring AI + RAG 관점 MySQL vs PostgreSQL

| 항목 | MySQL 8.x | PostgreSQL 16+ | RAG에 미치는 영향 |
|---|---|---|---|
| 벡터 타입/검색 | 네이티브 벡터 타입 없음 (9.0에서 `VECTOR` 타입이 막 실험적으로 도입된 수준, 인덱싱·연산자 생태계 미성숙) | `pgvector` 익스텐션으로 `vector` 컬럼 타입 + 코사인/L2/내적 거리 연산자 + IVFFlat/HNSW 인덱스 제공 | 임베딩 저장·유사도 검색을 별도 벡터 DB 없이 같은 트랜잭션 DB 안에서 처리 가능 |
| Spring AI `VectorStore` 지원 | 공식 MySQL 기반 `VectorStore` 구현체 없음 | `PgVectorStore`(`spring-ai-starter-vector-store-pgvector`)가 Spring AI에서 가장 성숙하고 문서화가 잘 된 구현체 중 하나 | RAG 구현 시 직접 유사도 검색 SQL을 짤 필요 없이 `VectorStore.add()`/`similaritySearch()` 표준 API 재사용 |
| JSON | `JSON` 타입은 있지만 인덱싱·연산자가 제한적 | `JSONB` — GIN 인덱스로 인덱싱 가능, 풍부한 연산자(`@>`, `->`, `#>>` 등) | LLM 응답 메타데이터, RAG 검색 결과의 원본 payload, tool-call 구조 등을 유연하게 저장·질의 가능 |
| 전문 검색(Full-text) | `FULLTEXT` 인덱스 존재하지만 기능이 제한적 | `tsvector`/`tsquery` + GIN 인덱스로 성숙한 전문 검색 | 벡터 검색 + 키워드 검색을 결합하는 하이브리드 검색(RAG 품질 향상의 핵심 기법)을 같은 DB에서 구현 가능 |
| 인덱스/쿼리 기능 | 기본적인 B-Tree/FULLTEXT 위주 | GIN/GiST/BRIN 등 다양한 인덱스, 윈도우 함수·CTE 등 고급 SQL | 이 프로젝트가 이미 하는 "최근 2주~향후 2주 윈도우" 같은 컨텍스트 구성 쿼리를 더 정교하게 짤 수 있음 |
| 인프라 구성 | 벡터 검색을 하려면 Pinecone/Milvus/Weaviate 등 별도 벡터 DB를 추가로 운영해야 함 | 트랜잭션 데이터(users/schedules/categories)와 임베딩을 한 인스턴스에서 같이 운영 | 개인/포트폴리오 규모 프로젝트에서 운영 부담·비용을 최소화 |
| 라이선스 | GPL + Oracle 듀얼 라이선스 | PostgreSQL 라이선스(permissive, MIT류) | 오픈소스 확장(pgvector 포함)을 자유롭게 얹기 쉬움 |

**결론**: 지금 당장 RAG 기능이 없어도, "RAG를 Spring AI로 구축하겠다"는 목표가 정해진 시점에서
MySQL을 계속 쓰면 결국 별도 벡터 DB를 하나 더 추가하게 된다. PostgreSQL + `pgvector`로 옮기면
기존 스케줄/카테고리/유저 데이터와 임베딩을 같은 DB, 같은 트랜잭션 경계 안에서 다룰 수 있고,
Spring AI의 `PgVectorStore`를 그대로 쓸 수 있어 구현 난이도도 낮아진다.

## 이번에 한 일 (설정 전환)

이번 작업 범위는 **설정 전환만** — 기존 로컬 MySQL의 `api` DB에 있던 테스트 데이터
(users 35건 / schedules 251건 / categories 9건 / mandalart_boards 1건 / mandalart_cells 81건)는
로컬 개발용 더미 데이터라 이관하지 않기로 결정했고, PostgreSQL에 새 빈 DB로 시작한다.
(참고: 로컬 MySQL의 `api` 데이터베이스에는 이 프로젝트와 무관한 테이블(`stock`, `history`,
`outbox_event`, `conversion_stats_total`)도 같이 들어있었음 — 다른 로컬 프로젝트와 DB 이름을
공유하고 있던 것으로 보임, 이번 전환과는 무관.)

- [x] 로컬 PostgreSQL(Homebrew, `postgresql@18`, 이미 실행 중)에 `api` 데이터베이스 생성 (`createdb api`)
- [x] `build.gradle`: `runtimeOnly 'com.mysql:mysql-connector-j'` → `runtimeOnly 'org.postgresql:postgresql'`
- [x] `application-local.yml`: `driver-class-name`/`url`/`username`을 PostgreSQL(`org.postgresql.Driver`,
      `jdbc:postgresql://localhost:5432/api`)로 변경. 초기 전환 시에는 `ddl-auto: update`로 Hibernate가
      PostgreSQL 방언으로 스키마를 새로 만들게 했고, 스키마가 안정된 뒤 `ddl-auto: validate`로 전환함
      (스키마 변경은 이제 수동 DDL로 관리 — 아래 "대소문자 구분 제거" 항목이 그 예시)
- [x] `README.md`: 기술 스택 표, 사전 요구사항, DB 생성 SQL, `application-local.yml` 예시를
      PostgreSQL 기준으로 갱신
- [x] `CLAUDE.md`: "Local infra required" 문구를 MySQL → PostgreSQL로 갱신, 이 문서(`TASK.md`) 링크 추가
- [x] 엔티티 점검: `@GeneratedValue(strategy = GenerationType.IDENTITY)`, `@Enumerated(EnumType.STRING)`만
      쓰고 있고 MySQL 전용 `columnDefinition`(예: `TINYINT`, `AUTO_INCREMENT` 리터럴, `ENGINE=`)은
      없어 엔티티 코드 변경 없이 그대로 PostgreSQL 방언으로 동작
- [x] `spring-session-jdbc`(`initialize-schema: always`)는 스타터가 내장한 `schema-postgresql.sql`을
      자동으로 골라 쓰므로 별도 설정 불필요
- [x] 빌드/컴파일/테스트/`bootRun` 스모크 테스트로 PostgreSQL 연결 확인 (아래 "검증" 참고)

## MySQL과의 차이로 발생한 문제 — 문자열 대소문자 구분

기존 MySQL(`api` DB, `utf8mb4_unicode_ci` collation)은 문자열 비교가 기본적으로 대소문자를
구분하지 않았다. PostgreSQL은 기본 collation이 대소문자를 구분해서, 전환 직후 아래 로직들의
동작이 조용히 바뀌어 있었다(코드 변경 없이 DB만 바꿔서 생긴 회귀):

- `UserRepository.findByEmail` / `existsByEmail` — 로그인 시 가입 때와 다른 대소문자의 이메일을
  쓰면 더 이상 매칭되지 않고, 가입 시에도 대소문자만 다른 이메일 중복을 막지 못함
  (`CustomUserDetailsService`가 이 `findByEmail`로 인증하므로 로그인 자체에 영향)
- `CategoryRepository.findByName` / `existsVisibleDuplicateName` — 카테고리 이름 중복 검사도 동일

**해결**: 애플리케이션 코드(이메일을 저장 전에 `lowercase`로 정규화하는 방식)를 건드리는 대신,
DB 레벨에서 PostgreSQL의 비결정적(non-deterministic) ICU collation으로 MySQL의 `_ci` collation과
동등한 효과를 재현했다. 컬럼의 선언 타입(`varchar(255)`)은 그대로 유지되고 collation만 바뀌므로
Hibernate `ddl-auto: validate`도 영향받지 않고, JPA 엔티티(`User`/`Category`) 코드도 변경 불필요.

```sql
CREATE COLLATION IF NOT EXISTS case_insensitive
    (provider = icu, locale = 'und-u-ks-level2', deterministic = false);

ALTER TABLE users      ALTER COLUMN email TYPE varchar(255) COLLATE case_insensitive;
ALTER TABLE categories ALTER COLUMN name  TYPE varchar(255) COLLATE case_insensitive;
```

- [x] 로컬 `api` DB에 위 DDL 적용 완료, `SELECT count(*) FROM users WHERE email = upper(email)`로
      대소문자 무시 비교가 실제로 동작하는지 확인
- [x] `ddl-auto: validate`인 상태로 `bootRun` 재기동해 스키마 검증 통과 확인 (collation은 JDBC
      컬럼 메타데이터에 노출되지 않아 Hibernate 검증 대상이 아님)
- [ ] **새 로컬 환경/CI에도 반영 필요** — 이 DDL은 Flyway/Liquibase 없이 로컬 DB에 수동으로만
      적용했다. 다른 환경에서 처음부터 세팅할 때는 위 SQL을 스키마 생성 직후 함께 실행해야 함
      (README "데이터베이스 설정" 절에 반영)
- 범위를 `email`/카테고리 `name`으로 한정함 — `username`은 로그인/중복 검사에 쓰이지 않아
  (`findByUsername`이 정의만 되어 있고 실제 호출부 없음) 그대로 둠. 필요해지면 같은 방식으로 추가.

## 검증

- `./gradlew compileJava compileTestJava` — 통과
- `./gradlew test` — 72개 중 71개 통과. 실패한 1건(`ScheduleServiceTest > 개시 적용 후 성능 측정`)은
  마이그레이션과 무관 — `@SpringBootTest`로 실제 DB에 붙어 미리 저장된 카테고리(업무/일상/운동健康/
  자기계발/식단)가 있다는 걸 전제로 하는 로컬 성능 벤치마크 테스트인데, 이번에 새로 만든 빈 PostgreSQL
  DB에는 그 카테고리들이 아직 없어 실패함(데이터 이관을 하지 않기로 한 이번 결정의 예상된 결과).
  Hikari 커넥션 풀이 정상적으로 붙는 로그가 남는 걸로 봐서 PostgreSQL 연결 자체는 문제없음. 해당
  카테고리를 로컬 DB에 수동으로 넣거나 테스트를 `@BeforeAll`에서 자체 시딩하도록 바꾸면 통과함 —
  이번 마이그레이션 범위 밖이라 손대지 않음.
- `./gradlew bootRun`으로 앱을 띄워 Hibernate가 PostgreSQL `api` DB에 스키마를 실제로 생성하는지 확인
  (`categories`/`mandalart_boards`/`mandalart_cells`/`schedules`/`users`/`spring_session`/
  `spring_session_attributes` 7개 테이블 생성 확인, `\dt`로 검증) 후 프로세스 종료

## 하지 않은 것 / 다음 단계

- **데이터 이관 안 함** — 위 결정에 따라 기존 MySQL 로컬 데이터는 이관하지 않음. 필요해지면
  `pgloader`(MySQL → PostgreSQL 전용 도구)로 별도 진행.
- [x] **pgvector RAG 1탄 완료 — 만다라트 과거 목표 검색** (`MandalartGoalEmbeddingService`,
  `[feat] 만다라트 AI 채우기 RAG` 커밋): 완성된 바깥 블록(세부목표+실행항목 8개)을 pgvector에
  임베딩해뒀다가, `fillWithAi`가 지금 채우는 세부목표와 비슷한 요청자 본인의 과거 블록을 검색해
  few-shot 예시로 프롬프트에 얹는다. 실제로 쓴 구성: `spring-ai-openai-spring-boot-starter`(임베딩
  전용, `spring.ai.openai.chat.enabled: false`로 채팅 빈은 꺼둠) + `spring-ai-pgvector-store-spring-boot-starter`
  (M1 기준 실제 artifact 이름 — `spring-ai-starter-vector-store-pgvector`라는 최신 이름은 이 버전엔
  없음, `PgVectorStoreAutoConfiguration`이 `JdbcTemplate`+`EmbeddingModel`만 있으면 자동 구성), 임베딩
  모델은 OpenAI `text-embedding-3-small`. 로컬에 `vector` 익스텐션 외에 `uuid-ossp`도 추가로 필요했음
  (`PgVectorStore`의 기본 id 컬럼이 `uuid_generate_v4()` 디폴트를 쓰는 `uuid` 타입이라, 결정적 upsert
  키를 만들려면 `UUID.nameUUIDFromBytes(...)`로 문자열 키를 uuid로 변환해야 함). 자세한 내용은
  `CLAUDE.md`의 "Mandalart RAG" 절 참고.
- [x] **pgvector RAG 2탄 완료 — 일정 컨텍스트 RAG** (`ScheduleEmbeddingService`): 고정 ±2주 윈도우
  (`AiService.filterToWindow`)는 대체하지 않고 그대로 두되, 윈도우 밖 일정 중 지금 대화와 의미적으로
  비슷한 것만 `[참고: 의미상 비슷한 과거/예정 일정]` 섹션으로 보강. 만다라트 RAG와 같은
  `VectorStore`/OpenAI 임베딩 빈을 그대로 재사용(임베딩 대상은 계획대로 `Schedule.title`+`content`).
  같은 `vector_store` 테이블을 공유하게 되면서, 두 RAG 용도가 섞이지 않도록 `docType` 메타데이터
  구분을 이번에 추가(기존 만다라트 RAG 필터에도 소급 적용). 색인은 `createSchedule`/`updateSchedule`/
  `deleteSchedule`에만 연결하고, 반복 일정 배치 생성(`createSchedules`)과 자동 상태 전환은 제외
  (지연시간/가치 트레이드오프). 자세한 내용은 `CLAUDE.md`의 "Schedule RAG" 절 참고.
- **로컬 MySQL 서비스 중지/삭제 안 함** — 다른 로컬 프로젝트가 같은 MySQL 인스턴스를 쓰고 있어
  (`stock`/`history`/`outbox_event` 등 무관 테이블 존재) 그대로 둠. 이 프로젝트만 PostgreSQL을
  바라보도록 설정만 바꿨다.

## 참고

- ⚠️ `AiService`/`POST /api/ai/suggest`는 Anthropic 유료 API를 호출한다 — `CLAUDE.md`에 명시된 대로
  환불 처리가 끝났다고 사용자가 확인하기 전까지는 실제 호출을 트리거하지 않는다. 이번 DB 마이그레이션
  검증(`bootRun`)에서도 `/api/ai/*` 엔드포인트는 호출하지 않았다.
