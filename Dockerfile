# syntax=docker/dockerfile:1

# ---- 1단계: 실행 가능한 jar 빌드 ----
# 테스트는 Jenkins 파이프라인의 별도 Test 스테이지에서 이미 돌린 뒤이므로(로컬 Postgres/Redis가
# 필요해 격리된 빌드 컨테이너 안에서는 애초에 돌릴 수 없다), 이미지 빌드 시에는 건너뛴다.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Gradle wrapper와 의존성 정의 파일만 먼저 복사해 소스 변경과 무관하게 의존성 다운로드 레이어를 캐싱한다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --version

COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# ---- 2단계: JRE만 담은 실행 이미지 ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
