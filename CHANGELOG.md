# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Springdoc OpenAPI UI: `/api/v1/docs`, `/api/v1/docs/ui`
- `HelloControllerIntegrationTest`: `X-Request-Id` → `X-Trace-Id` 전파, 표준 `ApiError`, 요청 로그 traceId 흐름 검증
- W3C `traceparent` 기반 trace context: traceId는 전체 플로우로 승계, 각 BE 요청은 새 spanId 생성
- 로그 correlation 패턴에 `traceId`, `spanId`, `parentSpanId` 모두 출력
- 에러 응답과 응답 헤더에 `spanId` 포함
- Standard success response envelopes: single `{value, meta}`, list `{values, meta}`, page `{values, pagination, meta}`
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
- `ExternalHttpClient.postForm(...)` and per-call `baseUrl(...)` overrides for OAuth/payment-style external APIs

### Fixed
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
