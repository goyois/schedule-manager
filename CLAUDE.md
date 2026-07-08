# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew bootRun                                    # run the app (default profile: local)
./gradlew build                                      # compile + test + package
./gradlew compileJava compileTestJava -q              # fast compile check without running tests
./gradlew test                                       # run all tests
./gradlew test --tests "ScheduleServiceTest"          # run a single test class
./gradlew test --tests "ScheduleServiceTest.apiPerformance"  # run a single test method
```

Local infra required to run the app: MySQL 8.x (db `api`) and Redis 7.x, both on default ports. `src/main/resources/application-local.yml` (gitignored) holds datasource creds, Redis host, the Anthropic API key, and the JWT secret — it must exist locally and is not checked in. `src/main/resources/application.yml` only has profile-independent settings (Redis timeout, actuator exposure).

Monitoring stack (optional, not required for app to run): `docker-compose -f monitoring/docker-compose.yml up` starts Prometheus (`:9090`) and Grafana (`:3000`, admin/admin). The app exposes `/actuator/prometheus` unauthenticated (permitted in `SecurityConfig`) for scraping.

## Architecture

Spring Boot 3.4 / Java 17, package-by-domain under `com.example.schedule_manager`:

- `domain/{user,auth,schedule,category}` — each has `controller/`, `service/`, `repository/`, `entity/`, and (except auth) `dto/`. DTOs are Java records; entities use Lombok `@Builder` with a private all-args constructor and protected no-args constructor, and expose mutation only through explicit `update(...)` methods (no public setters).
- `global/security/` — JWT auth stack: `SecurityConfig` (stateless, CSRF disabled, permits `/api/auth/**`, `POST /api/users`, static view routes, `/actuator/**`), `JwtAuthenticationFilter` (runs before `UsernamePasswordAuthenticationFilter`, resolves `Bearer` token, checks a Redis logout blacklist, populates `SecurityContext`), `JwtUtil` (HS-signed JWT, email as subject), `CustomUserDetailsService` (loads `User` by email, grants `ROLE_USER`/`ROLE_ADMIN` from `UserType`).
- `global/config/RedisConfig` + `CacheFailSafeErrorHandler` — Redis-backed `CacheManager` for `@Cacheable`/`@CacheEvict`. Custom `GenericJackson2JsonRedisSerializer` registers `JavaTimeModule` (required for caching DTOs with `LocalDateTime` fields). `CacheFailSafeErrorHandler` makes cache get/put/evict failures fail-open (log + fall through to DB) instead of turning a Redis outage into a 500 — the same fail-open pattern is used in `JwtAuthenticationFilter`'s blacklist check.
- `global/response/ApiResponse<T>` — every controller returns `ResponseEntity<ApiResponse<T>>` via `ApiResponse.success(data)` / `.success()` / `.error(code, message)`.
- `global/common/BaseEntity` — `@MappedSuperclass` with `@CreatedDate`/`@LastModifiedDate` (JPA auditing).
- `global/controller/ViewController` — forwards clean paths (`/`, `/login`, `/signup`, `/dashboard`) to static HTML in `src/main/resources/static/` (vanilla JS frontend: `dashboard.js`, `login.js`, `signup.js`, `api.js`).
- No `@RestControllerAdvice` / global exception handler exists yet — services throw raw `IllegalArgumentException` for not-found/validation/authorization failures (e.g. ownership checks in `ScheduleService`), and these currently surface as unmapped 500s rather than 400/403/404.

**Authorization model**: `UserType` is `ADMIN` or `USER`. In `ScheduleService`, `ADMIN` can pass an arbitrary `userId` to read/target any user's schedules; `USER` always has the requester's own id substituted in, ignoring any `userId` param from the request. Ownership on single-resource reads (`getSchedule`) is enforced by comparing `schedule.getUser().getId()` to the authenticated requester.

**Caching**: `ScheduleService.getSchedules` is cached under cache name `schedules` with key `requesterEmail-userId-categoryId` (empty results are not cached). Because per-key eviction isn't feasible from `updateSchedule`/`deleteSchedule` (they only receive a schedule id, not the owning user), all three mutating methods evict the entire `schedules` cache (`allEntries = true`) rather than targeting a specific key.

**AI integration**: `spring-ai-anthropic-spring-boot-starter` is a dependency (model configured in `application-local.yml`), but no `ChatClient`/AI controller is wired up yet — the `/api/ai/*` endpoints described in README/TASKS.md are not yet implemented.

Note: README.md and TASKS.md describe the original task plan (session-based auth, `domain/schedule` field names like `repeatType`) — the actual implementation diverged (JWT-based auth instead of session, `Schedule` uses `content`/`status` instead of `description`/`repeatType`). Trust the code over those docs.

## Git workflow

- Commit freely per unit of work; no need to ask before committing.
- Commit message style follows existing history: `<type>: <description>`, e.g. `feat: add ...`, `chore: ...` — description is often Korean, type prefix in English.
- Always ask for explicit confirmation before `git push` (this repo pushes directly to `main`, no PR flow) — never push proactively.
- Never amend or force-push without the user explicitly asking.
