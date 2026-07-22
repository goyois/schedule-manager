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

Local infra required to run the app: MySQL 8.x (db `api`) and Redis 7.x, both on default ports. `src/main/resources/application-local.yml` (gitignored) holds datasource creds, Redis host, the Anthropic API key, the JWT secret, and the Google OAuth client ID (`google.oauth.client-id`) — it must exist locally and is not checked in. `src/main/resources/application.yml` only has profile-independent settings (Redis timeout, actuator exposure).

Monitoring stack (optional, not required for app to run): `docker-compose -f monitoring/docker-compose.yml up` starts Prometheus (`:9090`) and Grafana (`:3000`, admin/admin). The app exposes `/actuator/prometheus` unauthenticated (permitted in `SecurityConfig`) for scraping.

## Architecture

Spring Boot 3.4 / Java 17, package-by-domain under `com.example.schedule_manager`:

- `domain/{user,auth,schedule,category}` — each has `controller/`, `service/`, `repository/`, `entity/`, and (except auth) `dto/`. DTOs are Java records; entities use Lombok `@Builder` with a private all-args constructor and protected no-args constructor, and expose mutation only through explicit `update(...)` methods (no public setters).
- `global/security/` — JWT auth stack: `SecurityConfig` (stateless, CSRF disabled, permits `/api/auth/**`, `POST /api/users`, static view routes, `/actuator/**`), `JwtAuthenticationFilter` (runs before `UsernamePasswordAuthenticationFilter`, resolves `Bearer` token, checks a Redis logout blacklist, populates `SecurityContext`), `JwtUtil` (HS-signed JWT, email as subject), `CustomUserDetailsService` (loads `User` by email, grants `ROLE_USER`/`ROLE_ADMIN` from `UserType`).
- `global/config/GoogleOAuthConfig` — provides a `GoogleIdTokenVerifier` bean (audience = `google.oauth.client-id`) used by `AuthService.loginWithGoogle`. Google login is credential-flow, not Spring Security's `oauth2Login()`: the frontend obtains a Google ID token via Google Identity Services (GIS) client-side JS, POSTs it to `POST /api/auth/google`, the server verifies the token's signature/audience/expiry against Google's public keys, then get-or-creates a `User` by email (`AuthProvider.GOOGLE` for new accounts, `password` set to an unusable random BCrypt hash since `CustomUserDetailsService`/`UserDetails` require a non-null password) and issues the same app JWT as password login. No redirect/authorization-code flow, no Google client secret needed.
- `global/config/RedisConfig` + `CacheFailSafeErrorHandler` — Redis-backed `CacheManager` for `@Cacheable`/`@CacheEvict`. Custom `GenericJackson2JsonRedisSerializer` registers `JavaTimeModule` (required for caching DTOs with `LocalDateTime` fields). `CacheFailSafeErrorHandler` makes cache get/put/evict failures fail-open (log + fall through to DB) instead of turning a Redis outage into a 500 — the same fail-open pattern is used in `JwtAuthenticationFilter`'s blacklist check.
- `global/response/ApiResponse<T>` — every controller returns `ResponseEntity<ApiResponse<T>>` via `ApiResponse.success(data)` / `.success()` / `.error(code, message)`.
- `global/common/BaseEntity` — `@MappedSuperclass` with `@CreatedDate`/`@LastModifiedDate` (JPA auditing).
- `global/controller/ViewController` — forwards clean paths (`/`, `/login`, `/signup`, `/dashboard`) to static HTML in `src/main/resources/static/` (vanilla JS frontend: `dashboard.js`, `login.js`, `signup.js`, `api.js`).
- No `@RestControllerAdvice` / global exception handler exists yet — services throw raw `IllegalArgumentException` for not-found/validation/authorization failures (e.g. ownership checks in `ScheduleService`), and these currently surface as unmapped 500s rather than 400/403/404.

**Authorization model**: `UserType` is `ADMIN` or `USER`. In `ScheduleService`, `ADMIN` can pass an arbitrary `userId` to read/target any user's schedules; `USER` always has the requester's own id substituted in, ignoring any `userId` param from the request. Ownership on single-resource reads (`getSchedule`) is enforced by comparing `schedule.getUser().getId()` to the authenticated requester.

**Caching**: `ScheduleService.getSchedules` is cached under cache name `schedules` with key `requesterEmail-userId-categoryId` (empty results are not cached). Because per-key eviction isn't feasible from `updateSchedule`/`deleteSchedule` (they only receive a schedule id, not the owning user), all three mutating methods evict the entire `schedules` cache (`allEntries = true`) rather than targeting a specific key.

**AI integration**: `spring-ai-anthropic-spring-boot-starter` is a dependency (model configured in `application-local.yml`), but no `ChatClient`/AI controller is wired up yet — the `/api/ai/*` endpoints described in README/TASKS.md are not yet implemented.

Note: README.md and TASKS.md describe the original task plan (session-based auth, `domain/schedule` field names like `repeatType`) — the actual implementation diverged (JWT-based auth instead of session, `Schedule` uses `content`/`status` instead of `description`/`repeatType`). Trust the code over those docs.

## Testing

- When adding new logic (new service methods, new endpoints, new business rules), write accompanying test code in the same change — don't leave it for a follow-up ask.
- Match existing conventions: JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`, `@Mock`/`@InjectMocks`), AssertJ assertions (`assertThat`, `assertThatThrownBy`), Korean `@DisplayName` describing the scenario (see `UserServiceTest`, `AuthServiceTest`). Cover both success and failure/exception paths.
- Before reporting a change as done, actually run it: at minimum `./gradlew test --tests "<AffectedClass>"` for the touched class(es), or the full `./gradlew test` when the change spans multiple classes/layers. Don't claim success from reading the code alone — if tests can't be run (e.g. missing local MySQL/Redis), say so explicitly instead of asserting it works.

## Git workflow

- Commit freely per unit of work; no need to ask before committing.
- As of commit `d933707` ([CHORE-2] CLAUDE.md 추가 (#10)), commit messages follow: `[<category>-<n>] <설명> (#<m>)`.
  - `<category>` = commit type (`feat`, `chore`, `test`, ...), same types as the old `feat:`/`chore:` prefixes.
  - **Lowercase** (e.g. `[test-2]`, not `[TEST-2]`). Already-pushed commits `d933707`/`5db74ed` used uppercase (`[CHORE-2]`, `[CHORE-3]`) before this was corrected and were left as-is since rewriting published history isn't safe; unpushed commits were rewritten to lowercase before push.
  - `<n>` = 1-indexed count of prior commits in that category, computed from `git log --oneline`. Before this scheme, history used bare `feat:`/`chore:` prefixes (6 `feat`, 1 `chore`, 2 with no prefix, excluded from counts) — that history counts toward `<n>`.
  - `<m>` = 1-indexed count of *all* prior commits total (`git log --oneline | wc -l`), since this repo pushes directly to `main` with no PR flow and has no real PR/issue numbers to reuse.
  - Recompute both counts from current `git log` before each commit — don't reuse cached numbers from a prior session.
- Older commits (pre-`d933707`) used `<type>: <description>` (e.g. `feat: add ...`, `chore: ...`) — superseded by the scheme above, kept here only for history context.
- Always ask for explicit confirmation before `git push` (this repo pushes directly to `main`, no PR flow) — never push proactively.
- Never amend or force-push without the user explicitly asking.
