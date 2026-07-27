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
      `jdbc:postgresql://localhost:5432/api`)로 변경. `spring.jpa.hibernate.ddl-auto: update`는 그대로
      유지 — Flyway/Liquibase 없이 Hibernate가 PostgreSQL 방언으로 스키마를 새로 만든다.
- [x] `README.md`: 기술 스택 표, 사전 요구사항, DB 생성 SQL, `application-local.yml` 예시를
      PostgreSQL 기준으로 갱신
- [x] `CLAUDE.md`: "Local infra required" 문구를 MySQL → PostgreSQL로 갱신, 이 문서(`TASK.md`) 링크 추가
- [x] 엔티티 점검: `@GeneratedValue(strategy = GenerationType.IDENTITY)`, `@Enumerated(EnumType.STRING)`만
      쓰고 있고 MySQL 전용 `columnDefinition`(예: `TINYINT`, `AUTO_INCREMENT` 리터럴, `ENGINE=`)은
      없어 엔티티 코드 변경 없이 그대로 PostgreSQL 방언으로 동작
- [x] `spring-session-jdbc`(`initialize-schema: always`)는 스타터가 내장한 `schema-postgresql.sql`을
      자동으로 골라 쓰므로 별도 설정 불필요
- [x] 빌드/컴파일/테스트/`bootRun` 스모크 테스트로 PostgreSQL 연결 확인 (아래 "검증" 참고)

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
- **pgvector 익스텐션/의존성 추가 안 함** — 이번 범위는 DB 전환까지. RAG 구현 착수 시:
  1. PostgreSQL에서 `CREATE EXTENSION IF NOT EXISTS vector;`
  2. `build.gradle`에 `spring-ai-starter-vector-store-pgvector` 추가
  3. 임베딩 대상 정하기(예: `Schedule.content` + `title`, 또는 별도 "메모/기록" 도메인)와
     임베딩 모델(Anthropic은 임베딩 모델을 제공하지 않으므로 별도 임베딩 모델 필요 — 예:
     OpenAI `text-embedding-3-small` 또는 로컬 임베딩 모델) 선정
  4. `AiService`의 컨텍스트 구성(`buildScheduleContext`, 최근/향후 2주 윈도우 방식)을
     `VectorStore.similaritySearch()` 기반으로 교체
- **로컬 MySQL 서비스 중지/삭제 안 함** — 다른 로컬 프로젝트가 같은 MySQL 인스턴스를 쓰고 있어
  (`stock`/`history`/`outbox_event` 등 무관 테이블 존재) 그대로 둠. 이 프로젝트만 PostgreSQL을
  바라보도록 설정만 바꿨다.

## 참고

- ⚠️ `AiService`/`POST /api/ai/suggest`는 Anthropic 유료 API를 호출한다 — `CLAUDE.md`에 명시된 대로
  환불 처리가 끝났다고 사용자가 확인하기 전까지는 실제 호출을 트리거하지 않는다. 이번 DB 마이그레이션
  검증(`bootRun`)에서도 `/api/ai/*` 엔드포인트는 호출하지 않았다.
