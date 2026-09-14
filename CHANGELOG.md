# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Spring Boot 4.0.5 → **4.1.1**, Kotlin 2.2.21 → **2.3.21** (Boot-managed: jOOQ 3.21.7, MySQL Connector/J 9.7.0, Testcontainers 2.0.5, Spring Security 7.1.1). One source change: `JwtTokenService` treats a missing `sub` claim as authentication failure (subject is nullable in Spring Security 7.1)
- `storage-s3`: static key-pair credentials (`credentials.access-key-id` / `secret-access-key`) for R2/MinIO with fail-fast on half-specified pairs; `region: auto` supported; `UploadObjectRequest.cacheControl` / `contentDisposition` passed to `PutObject`; `StorageService.deleteAll` (S3: `DeleteObjects` in batches of 1000). `docs/storage-s3.md` R2 section

### Added
- `modules/persistence-jooq`: jOOQ with code generation from a DDL file (`DDLDatabase`, no DB at build), `UtcInstantConverter` (`*_at` → `Instant`, UTC-fixed), `JooqAuditRecordListener`, UTC session defaults; Testcontainers MySQL test with JVM zone forced to Seoul. `docs/persistence-jooq.md`
- `modules/job-queue-jdbc`: MySQL table retry queue — `JobQueue.enqueue`, `JobHandler` by type, `FOR UPDATE SKIP LOCKED` claiming, exponential backoff, `max-attempts` → DEAD, `PermanentJobFailureException`, stale RUNNING recovery, no Redis; 6 Testcontainers tests. `docs/job-queue-jdbc.md`
- `modules/notification-mail`: SMTP `MailSender` on top of `spring.mail.*`, off by default. `docs/notification-mail.md`
- `modules/captcha-turnstile`: `TurnstileVerifier` via platform outbound HTTP, hostname/action checks, off by default. `docs/captcha-turnstile.md`
- Schema without Flyway: `spring.flyway.enabled=false` + `spring.sql.init.mode=always` + `schema.sql`, proven by `SchemaSqlInitIntegrationTest`; migration path back to Flyway in `docs/schema-management.md`
- HTML pages next to the API: `apps/api` `PagesController` + page-scoped `HtmlPageErrorAdvice` + `PublicEndpointContributor`, proven by `HtmlPageCoexistenceIntegrationTest` (public `text/html`, HTML errors instead of JSON envelope, `/api/**` still protected)
- `scripts/rename-skeleton.sh`: rewrites root package, `skeleton.*` config prefix, `SKELETON_*` env placeholders and `Skeleton*` class/file names; `docs/minimal-composition.md` with a generated module → config-prefix table and the no-Redis defaults (rate limit, idempotency, scheduler lock)
- `modules/time`: global-time capability — `TimeContext` (account preference → `X-Time-Zone`/`Accept-Language` → `skeleton.time.default-*`), `ZonedMoment` (local time + IANA zone as source of truth, derived `at`; DST gap/overlap policy documented and tested), `TimeFormatter.dual` (event zone + viewer zone, `GMT+9`-style labels), `CountryTimeZones` generated from tzdata `zone.tab` with representative defaults for multi-zone countries, `UserTimePreferences` SPI. 12 tests
- `modules/persistence-jdbc`: `JdbcTimeZoneEnvironmentPostProcessor` forces the MySQL session to UTC via Hikari driver properties (`connectionTimeZone`, `forceConnectionTimeZoneToSession`), and `UtcInstantConversions` writes `Instant`/`LocalDate`/`LocalDateTime` as `JdbcValue` literals and reads `LocalDateTime` as UTC — measured against Connector/J: it converts `Timestamp`/`Date` parameters by the JVM zone but returns `DATETIME` as a wall-clock `LocalDateTime`, so a non-UTC JVM shifted instants by hours and moved `LocalDate` by a day. `apps/api` proves the round trip with the JVM default zone set to `Asia/Seoul`
- `docs/time.md`: the three temporal kinds and how to store/format each

### Changed
- `apps/api` datasource URL no longer needs `connectionTimeZone`/`forceConnectionTimeZoneToSession` parameters
- Testcontainers MySQL pinned to `mysql:8.4` (was `mysql:latest`, i.e. 9.x) to match `docker-compose.yml`
- Dockerfile builder image `gradle:8.11` → `eclipse-temurin:21-jdk` (the wrapper downloads Gradle anyway; the image tag was misleading); `.dockerignore` added
- Springdoc OpenAPI UI: `/api/v1/docs`, `/api/v1/docs/ui`
- `HelloControllerIntegrationTest`: `X-Request-Id` → `X-Trace-Id` 전파, 표준 `ApiError`, 요청 로그 traceId 흐름 검증
- W3C `traceparent` 기반 trace context: traceId는 전체 플로우로 승계, 각 BE 요청은 새 spanId 생성
- 로그 correlation 패턴에 `traceId`, `spanId`, `parentSpanId` 모두 출력
- 에러 응답과 응답 헤더에 `spanId` 포함
- Standard success response envelopes: single `{value, meta}`, list `{values, meta}`, page `{values, pagination, meta}`
- `Response` helper and envelope DTOs: `BasicResponse`, `DataResponse`, `ListResponse`, `PageResponse`, and `CursorResponse`
- Standard REST operation contracts: `201 Created` with `Location`, `202 Accepted`, `204 No Content`, and reusable `PageQuery`
- Web platform capability: public endpoint registry for `permitAll`, forwarded/security headers, CORS scaffold, rate-limit scaffold, and WebClient-based outbound HTTP facade
- Module-composed OpenAPI docs: platform contributes standard schemas/trace/error responses, auth contributes bearer JWT security, and auth-social contributes its functional route docs
- Standard request validation errors via Jakarta Bean Validation and `ApiError.errors[]`
- `modules/auth` stateless auth capability:
  - `POST /api/v1/auth/login` password login and `GET /api/v1/auth/me`
  - HS256 JWT issue/authenticate with `CurrentPrincipal`
  - local/dev header login via `X-Dev-Account-Id`, `X-Dev-Username`, `X-Dev-Email`
  - production break-glass access via secret, reason, and account allowlist
  - overridable Spring Boot auth auto-configuration defaults for app-specific repositories and security chains
- `modules/auth-social` optional social-login capability with provider-neutral OAuth contracts, account-link resolution, fake-provider integration tests, and `/api/v1/auth/social/{provider}/login`
- Optional social OAuth provider client modules: `modules/auth-social-google`, `modules/auth-social-kakao`, and `modules/auth-social-naver`
- `modules/idempotency` optional command endpoint protection with `@IdempotentOperation`, required `Idempotency-Key`, request fingerprinting, replay headers, and replaceable `IdempotencyStore`
- `ExternalHttpClient.postForm(...)` and per-call `baseUrl(...)` overrides for OAuth/payment-style external APIs
- `modules/notification` optional notification contracts with a replaceable in-memory broker
- `modules/notification-sse` optional Spring MVC SSE delivery through `GET /api/v1/notifications/sse`
- Persistence audit timestamp modules:
  - platform `TimeProvider` auto-configuration and `BaseAuditTimestamps` for UTC `Instant` + MySQL `DATETIME(6)` precision
  - optional `modules/persistence-jpa` with JPA `AuditTimestamps` and `BaseJpaEntity`
  - optional `modules/persistence-jdbc` with JDBC `AuditTimestamps`, `JdbcAuditable`, and audit callback auto-configuration

### Fixed
- Modules no longer depend on the app component-scanning `dev.sumin.skeleton`: `platform` registers `TraceIdFilter`, `RequestLoggingFilter` and `GlobalExceptionHandler` via `PlatformWebAutoConfiguration`, `auth` registers `AuthController` in `AuthAutoConfiguration` (all `@ConditionalOnMissingBean`, so scanning apps keep their beans). Found by assembling a monorepo app in `dev.sumin.app1`: auth endpoints were 404 and responses had no trace id. Guarded by `ModuleSelfRegistrationIntegrationTest`, which boots a root configuration that scans nothing. `CodeEnumOpenApiCustomizer` no longer filters DTOs by the `dev.sumin.skeleton` package prefix (it skips JDK/Kotlin/Spring/Jackson types instead), so code-enum descriptions appear for DTOs in any package
- 매핑되지 않은 API 경로를 `500`이 아니라 표준 `404 ApiError`로 응답
- break-glass/dev-login authentication is no longer overwritten by a later bearer-token filter when both headers are present

## v1.2.0 - Multi-module foundation

- Converted the backend skeleton to a coarse-grained Gradle multi-module layout.
- Added `apps/api` as the executable Spring Boot application.
- Added `modules/platform` for shared web/error/observability infrastructure.
- Added `modules/auth` as the authentication capability module foundation.
- Kept the existing trace-aware `/api/v1/hello` behavior and integration tests.

## [1.1.1] - 2026-04-20

### Added
- `RequestLoggingFilter`: 요청 시작/종료를 `→` `←` pair 로그로 출력 (같은 traceId로 묶임)
- `application.yml` `connectionTimeZone=UTC` — DB 타임존 JVM과 무관하게 UTC 고정
- `application.yml` Flyway MySQL 버전 경고 억제 (ERROR 레벨)

### Changed
- traceId 관리는 `TraceIdFilter` (커스텀) 유지 결정 — Spring Boot 4 + Micrometer Brave autoconfig 가 우리 환경에서 안정적으로 붙지 않아서. 단일 서비스 PoC 에선 차이 없고, 멀티 언어(Python/JS) 환경에서 오히려 단순 (X-Request-Id 는 누구나 다룸)
- 로그 correlation 패턴은 `[traceId]` 만 출력 (app 이름은 기본 APPLICATION_NAME 자리에서 한 번만)

## [1.1.0] - 2026-04-20

### Added
- **traceId 기반 관찰가능성**: `TraceIdFilter`가 요청마다 MDC `traceId` 심고 응답 헤더 `X-Trace-Id`로 반환. 클라이언트 `X-Request-Id` 헤더 오면 승계
- **표준 에러 응답**: `ApiError` (RFC 7807 Problem Details 변형 + traceId + timestamp), `GlobalExceptionHandler` 가 전역 예외 캐치
- **도메인 예외 베이스**: `ApplicationException` — 상속해서 throw하면 HTTP status + title이 자동 매핑
- **로깅 패턴**: 모든 로그 라인에 `[appName,traceId]` 프리픽스 출력
- **패키지 구조 정립**: `api/`, `domain/`, `infra/`, `common/`, `config/` 경계와 책임 CLAUDE.md에 명시
- **샘플 `HelloController`** (`/api/v1/hello`) — 컨벤션 시연용

## [1.0.0] - 2026-04-20

### Added
- Spring Boot 4.0 + Kotlin 2.2 + JDK 21 기본 구성
- MySQL 드라이버, Flyway, Spring Data JDBC
- Spring Boot Actuator (`/health` 노출)
- Testcontainers 테스트 지원
- 멀티 스테이지 Dockerfile
- `docker-compose.yml` 로컬 dev 환경 (app + MySQL)
- GitHub Actions CI 워크플로
