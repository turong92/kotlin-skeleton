# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
