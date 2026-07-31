# syntax=docker/dockerfile:1

# ---- 1단계: 실행 가능한 jar 빌드 ----
# 테스트는 GitHub Actions 워크플로(.github/workflows/deploy.yml)의 별도 test 잡에서 이미 돌린
# 뒤이므로(로컬 Postgres/Redis가 필요해 격리된 빌드 컨테이너 안에서는 애초에 돌릴 수 없다), 이미지
# 빌드 시에는 건너뛴다.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Gradle wrapper와 의존성 정의 파일만 먼저 복사해 소스 변경과 무관하게 wrapper 배포판 다운로드
# 레이어를 캐싱한다. 다만 실제 의존성 JAR는 src를 복사한 뒤 첫 빌드 태스크 실행 시점에 받아지므로,
# 이 레이어 캐싱만으로는 src가 바뀔 때마다 의존성을 다시 받는 걸 막지 못한다 - 그래서 아래 두 RUN에
# BuildKit 캐시 마운트(~/.gradle)를 추가로 걸어, 레이어가 무효화돼도 의존성 캐시는 그대로 재사용한다
# (deploy.yml의 build-and-push 잡이 cache-to: type=gha로 이 마운트 내용까지 캐싱한다)
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --version

COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar -x test --no-daemon

# ---- 2단계: JRE만 담은 실행 이미지 ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
