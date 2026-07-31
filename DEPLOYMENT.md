# 배포 프로세스 (AWS + Docker + Jenkins)

이 문서는 `schedule-manager`를 AWS 위에 Docker 컨테이너로 띄우고, Jenkins로 빌드/테스트/배포를
자동화하는 절차를 설명합니다. 1인 포트폴리오 프로젝트 규모를 기준으로, 비용과 구성 복잡도를
최소화하는 방향(EC2 단일 인스턴스 + RDS/ElastiCache 관리형 서비스)으로 설계했습니다.

## 목차

1. [아키텍처](#아키텍처)
2. [사전 준비물](#사전-준비물)
3. [AWS 인프라 만들기](#aws-인프라-만들기)
4. [DB 스키마 준비](#db-스키마-준비)
5. [애플리케이션 컨테이너화](#애플리케이션-컨테이너화)
6. [Jenkins 설치](#jenkins-설치)
7. [Jenkins 파이프라인 구성](#jenkins-파이프라인-구성)
8. [운영 비밀값 준비](#운영-비밀값-준비)
9. [첫 배포 실행 및 검증](#첫-배포-실행-및-검증)
10. [모니터링 연계](#모니터링-연계)
11. [롤백 & 트러블슈팅](#롤백--트러블슈팅)
12. [알려진 한계 및 다음 단계](#알려진-한계-및-다음-단계)

---

## 아키텍처

```mermaid
flowchart LR
    Dev[개발자] -->|git push| GitHub
    GitHub -->|webhook| Jenkins["Jenkins EC2\n(Docker)"]

    subgraph CI["Jenkins 파이프라인"]
        direction TB
        T[Test\n(docker-compose.ci.yml 임시 Postgres/Redis)]
        B[Docker Build]
        P[ECR Push]
        D[SSH Deploy]
        T --> B --> P --> D
    end

    Jenkins --> CI
    P -->|docker push| ECR[(Amazon ECR)]
    D -->|ssh + docker pull/run| App["앱 서버 EC2\n(Docker, :8080)"]
    App --> RDS[(RDS PostgreSQL 16\n+ pgvector)]
    App --> Redis[(ElastiCache Redis 7.x)]
    App -->|api| Anthropic[(Anthropic API)]
    App -->|embedding| OpenAI[(OpenAI Embedding API)]
    User[사용자 브라우저] -->|:8080 또는 :443| App
```

구성 요소 요약:

| 구성 요소 | 역할 | 비고 |
|---|---|---|
| Jenkins EC2 | 빌드/테스트/이미지 푸시/배포 트리거 | `t3.small` 권장 (Docker 데몬 + Jenkins 컨테이너) |
| 앱 서버 EC2 | 실제 애플리케이션 컨테이너 실행 | `t3.small` 권장, 포트 8080 |
| Amazon ECR | Docker 이미지 레지스트리 | 프라이빗 리포지토리 1개 |
| RDS PostgreSQL 16 | 메인 DB + pgvector(Mandalart/Schedule RAG) | `db.t4g.micro`로 충분 |
| ElastiCache Redis 7.x | 캐시/JWT 블랙리스트/AI rate limit | `cache.t4g.micro` |

---

## 사전 준비물

- AWS 계정 (이 문서는 리전 `ap-northeast-2`(서울) 기준)
- AWS CLI 로컬 설치 및 자격 증명 설정 (`aws configure`) — 최초 인프라 생성 작업용. Jenkins 자체는
  아래에서 IAM 역할을 붙여 별도 액세스 키 없이 동작하게 만듭니다.
- 도메인(선택) — 없어도 EC2 퍼블릭 IP/Elastic IP로 배포까지는 가능합니다. HTTPS는
  [알려진 한계](#알려진-한계-및-다음-단계) 참고.
- GitHub 저장소에 대한 접근 권한 (Jenkins가 체크아웃할 수 있어야 함)
- 유효한 Anthropic API 키, OpenAI API 키(임베딩용), Google OAuth 클라이언트 ID (README.md
  "application-local.yml 설정" 절 참고 — 운영에서도 동일한 값들이 필요합니다)

---

## AWS 인프라 만들기

### 1. 보안 그룹

콘솔에서 아래 3개를 만듭니다 (기본 VPC 사용 가정).

| 보안 그룹 | 인바운드 규칙 |
|---|---|
| `sm-jenkins-sg` | TCP 22 (내 IP만), TCP 8080 (내 IP만 — Jenkins 웹 UI) |
| `sm-app-sg` | TCP 22 (`sm-jenkins-sg`에서만), TCP 8080 (0.0.0.0/0 — 사용자 접근용) |
| `sm-data-sg` | TCP 5432 (`sm-app-sg`에서만), TCP 6379 (`sm-app-sg`에서만) |

> 22번 포트를 0.0.0.0/0으로 열어두지 마세요. Jenkins→앱 서버 SSH는 `sm-jenkins-sg`를 소스로 지정해
> 그 인스턴스에서만 들어오게 제한합니다.

### 2. RDS PostgreSQL

1. RDS 콘솔 → 데이터베이스 생성 → 엔진 **PostgreSQL 16.x** (pgvector 확장을 지원하는 최신 마이너 버전)
2. 템플릿: 개발/테스트 (프리 티어 가능하면 활용)
3. 인스턴스 클래스: `db.t4g.micro`
4. 퍼블릭 액세스: **아니오**
5. VPC 보안 그룹: `sm-data-sg`
6. 초기 데이터베이스 이름: `api`
7. 생성 후 엔드포인트를 기록해둡니다 (`DB_HOST`로 사용)

### 3. ElastiCache Redis

1. ElastiCache 콘솔 → Redis → 클러스터 생성 (클러스터 모드 비활성화, 단일 노드로 충분)
2. 노드 타입: `cache.t4g.micro`
3. 보안 그룹: `sm-data-sg`
4. 생성 후 기본 엔드포인트를 기록해둡니다 (`REDIS_HOST`로 사용)

### 4. ECR 리포지토리

```bash
aws ecr create-repository \
  --repository-name schedule-manager \
  --region ap-northeast-2 \
  --image-scanning-configuration scanOnPush=true
```

출력된 `repositoryUri`를 `Jenkinsfile`의 `ECR_REPOSITORY_URI`와 `deploy/deploy.sh` 호출부에 씁니다.

### 5. Jenkins EC2

1. AMI: Amazon Linux 2023, 타입 `t3.small`, 보안 그룹 `sm-jenkins-sg`
2. IAM 역할을 새로 만들어 이 인스턴스에 연결합니다 (`sm-jenkins-role`) — 정책:
   - `AmazonEC2ContainerRegistryPowerUser` (ECR push/pull)
   - `AmazonSSMReadOnlyAccess` (운영 비밀값을 SSM Parameter Store에서 읽어오는 배포 스크립트용 —
     [운영 비밀값 준비](#운영-비밀값-준비) 참고)

   → 이렇게 하면 Jenkins 컨테이너/파이프라인 어디에도 AWS 액세스 키를 저장할 필요가 없습니다.
3. 접속 후 Docker 설치:
   ```bash
   sudo dnf install -y docker
   sudo systemctl enable --now docker
   sudo usermod -aG docker ec2-user
   ```
   (그룹 반영을 위해 재접속)

### 6. 앱 서버 EC2

1. AMI: Amazon Linux 2023, 타입 `t3.small`, 보안 그룹 `sm-app-sg`
2. IAM 역할 `sm-app-role` 연결 — 정책: `AmazonEC2ContainerRegistryReadOnly`, `AmazonSSMReadOnlyAccess`
3. Docker 설치는 Jenkins EC2와 동일
4. Jenkins EC2의 SSH 공개키를 이 인스턴스의 `~/.ssh/authorized_keys`에 등록 (또는 처음부터 동일한
   키 페어로 두 인스턴스를 생성)

---

## DB 스키마 준비

> **왜 마이그레이션 도구가 없나요?** 이 저장소는 아직 Flyway/Liquibase를 쓰지 않고, `ddl-auto: validate`
> 아래에서 스키마를 수동 DDL로 관리합니다 (`CLAUDE.md` 참고). 즉 "완전한 스키마"를 코드만으로 재현할
> 방법이 없고, 로컬 개발 DB 자체가 사실상 유일한 정본(source of truth)입니다. 아래 절차는 그 로컬 DB를
> 그대로 복제해 RDS에 옮기는 방식입니다. 프로젝트가 커지면 Flyway 도입을 권장합니다
> ([알려진 한계](#알려진-한계-및-다음-단계) 참고).

1. RDS에 필요한 확장을 먼저 활성화합니다 (마스터 계정으로, `api` DB에 접속해서):
   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
   ```
2. 로컬의 완전히 마이그레이션된 개발 DB에서 스키마만 덤프합니다:
   ```bash
   pg_dump --schema-only --no-owner --no-privileges \
     -h localhost -U <local-db-user> -d api -f schema.sql
   ```
3. 덤프 파일을 훑어보고(테이블/제약조건/collation이 다 들어있는지) RDS로 복원합니다:
   ```bash
   psql -h <rds-endpoint> -U <rds-master-user> -d api -f schema.sql
   ```
4. 애플리케이션용 DB 계정을 별도로 만들어 최소 권한만 부여합니다 (마스터 계정을 앱이 직접 쓰지 않도록):
   ```sql
   CREATE USER schedule_manager_app WITH PASSWORD '...';
   GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO schedule_manager_app;
   GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO schedule_manager_app;
   ```
   이 계정 정보가 `DB_USERNAME`/`DB_PASSWORD`가 됩니다.
5. 이후 스키마 변경(새 컬럼/테이블 추가)은 로컬에서 검증한 DDL을 같은 방식으로 RDS에도 수동 적용합니다
   — `ddl-auto: validate`는 운영에서도 유지합니다 (Hibernate가 스키마를 건드리지 않게).

---

## 애플리케이션 컨테이너화

`Dockerfile`은 2단계 빌드입니다:

1. **build 스테이지** (`eclipse-temurin:17-jdk-jammy`): `./gradlew bootJar -x test`로 실행 가능한 jar만
   만듭니다. 테스트를 여기서 돌리지 않는 이유는 로컬 Postgres/Redis가 필요한데 격리된 이미지 빌드
   컨테이너 안에는 그게 없기 때문입니다 — 테스트는 Jenkins의 별도 Test 스테이지가 담당합니다.
2. **런타임 스테이지** (`eclipse-temurin:17-jre-jammy`): jar만 복사해 넣고, non-root 유저(`spring`)로
   실행합니다.

로컬에서 빌드/실행이 되는지 먼저 확인해보세요 (운영 DB에 연결은 안 되지만 이미지 자체가 만들어지는지
확인하는 용도):

```bash
docker build -t schedule-manager:local .
```

`application-prod.yml`은 `SPRING_PROFILES_ACTIVE=prod`일 때 적용되며, 모든 값을 환경변수
플레이스홀더(`${DB_HOST}` 등)로만 채워서 실제 비밀값 없이 커밋되어 있습니다. 실제 값은 배포 시
`deploy/deploy.sh`가 `--env-file`로 주입합니다 ([운영 비밀값 준비](#운영-비밀값-준비) 참고).

---

## Jenkins 설치

Jenkins EC2에서:

```bash
docker volume create jenkins_home

docker run -d \
  --name jenkins \
  --restart unless-stopped \
  -p 8080:8080 -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v $(which docker):/usr/bin/docker \
  jenkins/jenkins:lts
```

`-v /var/run/docker.sock:/var/run/docker.sock`로 Jenkins 컨테이너 안에서 호스트의 Docker 데몬을 그대로
쓸 수 있게 합니다(Docker-in-Docker 대신 "Docker outside of Docker" 방식 — 이미지 빌드/`docker compose`
실행이 훨씬 가볍습니다).

초기 관리자 비밀번호 확인 후 `http://<jenkins-ec2-ip>:8080`으로 접속해 설정 마법사를 진행합니다:

```bash
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

설치할 플러그인: **Pipeline**, **Git**, **SSH Agent**, **Docker Pipeline**, **AWS Credentials**(선택 —
IAM 역할만 쓰면 필수는 아님), **JUnit**.

컨테이너 안에도 `aws` CLI가 있어야 `deploy/deploy.sh`가 호출하는 `aws ecr get-login-password` 등이
동작합니다. 이미지에 기본 포함돼 있지 않으므로 한 번 설치해둡니다:

```bash
docker exec -u root jenkins bash -c "apt-get update && apt-get install -y awscli"
```

---

## Jenkins 파이프라인 구성

1. Jenkins → **새 아이템** → **Pipeline** → 이름 `schedule-manager-deploy`
2. **Pipeline** 섹션 → Definition: `Pipeline script from SCM` → SCM: `Git` → 저장소 URL/자격증명 입력 →
   Script Path: `Jenkinsfile`
3. **Credentials** 등록 (Jenkins → Manage Jenkins → Credentials):
   - `app-server-ssh-key` (SSH Username with private key, username `ec2-user`) — 앱 서버 EC2 접속용
   - Git 저장소가 프라이빗이면 GitHub 접근용 자격증명도 별도 등록
4. `Jenkinsfile` 상단의 `ECR_REPOSITORY_URI`와 `APP_SERVER_HOST`를 실제 값으로 바꿔 커밋합니다.
5. GitHub 웹훅(또는 폴링)으로 `main` 브랜치 push 시 자동 빌드하도록 트리거를 설정합니다
   (Jenkins 잡 설정 → Build Triggers → GitHub hook trigger for GITScm polling).

파이프라인 단계 (`Jenkinsfile` 참고):

| 스테이지 | 내용 |
|---|---|
| Checkout | 저장소 체크아웃 |
| Test | `docker-compose.ci.yml`로 임시 Postgres(pgvector 이미지)/Redis를 띄우고 `./gradlew test -PciBuild` 실행 후 정리 |
| Build & Push Docker Image | `Dockerfile`로 이미지 빌드, 빌드 번호 태그 + `latest` 태그로 ECR에 푸시 |
| Deploy | `deploy/deploy.sh`를 앱 서버로 scp한 뒤 ssh로 실행 — pull → 기존 컨테이너 교체 → 헬스체크 → 실패 시 자동 롤백 |

`-PciBuild`가 무엇을 제외하는지: `ScheduleServiceTest`의 성능 벤치마크 테스트 1개는 로컬 개발 DB에
미리 심어둔 카테고리 데이터가 있어야만 통과합니다. CI의 임시 컨테이너 DB는 매 빌드 비어 있는 상태로
시작하므로 `@Tag("performance")`로 표시해 CI에서만 제외합니다 — 로컬 `./gradlew test`(플래그 없음)는
지금까지와 동일하게 이 테스트를 포함해 전부 돕니다.

---

## 운영 비밀값 준비

`deploy/app.env.example`에 필요한 환경변수 목록이 있습니다. 실제 값은 AWS Systems Manager
**Parameter Store**에 `SecureString`으로 저장하고, 앱 서버 EC2에서 배포 직전에 받아와 로컬 파일로만
존재하게 합니다 (Jenkins나 Git 어디에도 평문으로 남기지 않기 위해).

```bash
aws ssm put-parameter --name "/schedule-manager/DB_PASSWORD" --type SecureString --value "..."
aws ssm put-parameter --name "/schedule-manager/ANTHROPIC_API_KEY" --type SecureString --value "..."
# JWT_SECRET, OPENAI_API_KEY, GOOGLE_OAUTH_CLIENT_ID 등도 동일하게
```

앱 서버 EC2에서 최초 1회, `deploy/app.env.example`을 참고해 파라미터들을 실제 `.env` 파일로
받아옵니다 (이 스크립트는 저장소에 포함하지 않고 앱 서버에만 둡니다):

```bash
#!/usr/bin/env bash
set -euo pipefail
PREFIX="/schedule-manager"
OUT="/home/ec2-user/schedule-manager.env"
> "$OUT"
for name in DB_HOST DB_PORT DB_NAME DB_USERNAME DB_PASSWORD \
            REDIS_HOST REDIS_PORT \
            ANTHROPIC_API_KEY ANTHROPIC_MODEL OPENAI_API_KEY \
            JWT_SECRET JWT_EXPIRATION JWT_REFRESH_EXPIRATION \
            GOOGLE_OAUTH_CLIENT_ID; do
  value=$(aws ssm get-parameter --name "$PREFIX/$name" --with-decryption --query 'Parameter.Value' --output text)
  echo "$name=$value" >> "$OUT"
done
chmod 600 "$OUT"
```

값이 바뀌면 이 스크립트를 다시 실행한 뒤 `deploy/deploy.sh`를 재실행(또는 다음 Jenkins 배포가 자동으로
새 컨테이너를 띄울 때 반영)합니다.

---

## 첫 배포 실행 및 검증

1. `ECR_REPOSITORY_URI`/`APP_SERVER_HOST`를 채운 `Jenkinsfile`을 `main`에 푸시(또는 Jenkins에서 수동
   **Build Now**)합니다.
2. Jenkins 콘솔 로그에서 Test → Build & Push → Deploy 스테이지가 순서대로 성공하는지 확인합니다.
3. 배포가 끝나면:
   ```bash
   curl http://<app-server-ip>:8080/actuator/health
   ```
   `{"status":"UP"}`이면 정상입니다.
4. 브라우저로 `http://<app-server-ip>:8080/login`에 접속해 로그인 화면이 뜨는지 확인합니다.
5. 회원가입 → 로그인 → 일정 생성까지 골든 패스를 한 번 수동으로 확인합니다.

---

## 모니터링 연계

기존 `monitoring/docker-compose.yml`(Prometheus + Grafana)을 앱 서버 EC2(또는 별도의 작은 모니터링
EC2)에서 그대로 띄울 수 있습니다. 다만 `monitoring/prometheus/prometheus.yml`의 스크레이프 대상이
`host.docker.internal:8080`(로컬 개발용)으로 고정돼 있으므로, 운영에서는 앱 컨테이너가 떠 있는 실제
호스트를 가리키도록 바꿔야 합니다:

```yaml
scrape_configs:
  - job_name: "schedule-manager"
    metrics_path: "/actuator/prometheus"
    static_configs:
      - targets: ["localhost:8080"]  # 앱 컨테이너와 같은 호스트에서 Prometheus를 띄우는 경우
```

`/actuator/prometheus`는 `SecurityConfig`에서 인증 없이 허용되어 있으므로 별도 토큰 설정 없이
스크레이핑됩니다.

---

## 롤백 & 트러블슈팅

- **배포 직후 자동 롤백**: `deploy/deploy.sh`는 새 컨테이너가 30번(약 1분) 헬스체크에 실패하면 직전에
  떠 있던 이미지로 자동 롤백합니다.
- **수동 롤백**: 특정 빌드 번호로 되돌리고 싶다면 앱 서버에서:
  ```bash
  ./deploy.sh <ECR_REPOSITORY_URI> <되돌릴-빌드번호> ap-northeast-2
  ```
- **컨테이너 로그 확인**:
  ```bash
  docker logs -f schedule-manager
  ```
- **Jenkins Test 스테이지가 매번 느리다면**: `docker-compose.ci.yml`의 Postgres에 `tmpfs`를 이미
  써서 디스크 I/O는 줄여뒀습니다. 그래도 느리면 Jenkins 에이전트 자체의 인스턴스 타입을 올리는 걸
  고려하세요 (이미지 빌드 + 컨테이너 기동이 대부분의 시간을 차지합니다).
- **`SchemaManagementException: missing column`으로 부팅 실패**: RDS 스키마가 최신 엔티티와 어긋난
  상태입니다. [DB 스키마 준비](#db-스키마-준비)의 5번 절차(로컬에서 검증한 DDL을 RDS에도 적용)를
  놓쳤을 가능성이 큽니다.

---

## 알려진 한계 및 다음 단계

- **스키마 마이그레이션 도구 부재**: 지금은 `pg_dump`/수동 DDL로 RDS 스키마를 맞춥니다. 팀 규모가
  커지거나 배포 빈도가 늘면 Flyway 도입을 권장합니다 — 마이그레이션 이력이 코드로 남고, Jenkins
  파이프라인에 "마이그레이션 적용" 스테이지를 추가해 이 문서의 4번 섹션 전체를 자동화할 수 있습니다.
- **HTTPS 미적용**: 현재 구성은 앱 서버 EC2의 8080 포트를 그대로 노출합니다. JWT를 평문 HTTP로
  주고받는 것은 실제 사용자를 받기 전에 반드시 고쳐야 합니다. 가장 간단한 방법은 앱 서버에
  Nginx + Certbot(Let's Encrypt)을 얹어 443 → 8080으로 리버스 프록시하는 것이고, 더 정석적인
  방법은 ALB + ACM 인증서 + Route 53을 앞단에 두는 것입니다(이 경우 앱 서버를 프라이빗 서브넷으로
  옮기고 `sm-app-sg`의 8080 인바운드도 ALB 보안 그룹으로만 좁혀야 합니다).
- **단일 인스턴스, Auto Scaling 없음**: 앱 서버가 1대뿐이라 배포 중 짧은 다운타임이 있고(컨테이너
  교체 방식), 트래픽이 늘면 수직 확장(인스턴스 타입 업)부터 고려하게 됩니다. 더 크게 가려면 ECS
  Fargate + ALB로 전환하는 편이 무중단 배포/오토스케일링을 자연스럽게 얻을 수 있습니다.
- **DB 계정 로테이션**: `deploy/app.env.example`의 값들은 한번 SSM에 넣으면 수동으로만 갱신됩니다.
  주기적 로테이션이 필요하면 AWS Secrets Manager로 옮기고 자동 로테이션을 붙이는 걸 고려하세요.
