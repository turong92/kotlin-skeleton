# kotlin-skeleton — Claude Code 컨텍스트

Kotlin + Spring Boot 백엔드 토이 프로젝트의 공개 출발점.

## 작업 원칙

- **Spring Boot 컨벤션**: 디렉토리 구조, application.yml 키 이름은 Spring 기본형 유지
- **Kotlin idiomatic**: data class, scope function (`let`/`apply`/`also`), null 안전성 활용
- **REST 네임스페이스**: `/api/v1/*` — 컨트롤러에서 `@RequestMapping("/api/v1/...")`
- **스키마 변경**: 반드시 `src/main/resources/db/migration/V{n}__{desc}.sql` Flyway 마이그레이션으로
- **테스트**: Testcontainers로 실 MySQL 띄워 Flyway 마이그레이션 포함 검증

## 프론트엔드와의 통신

프론트(React + Vite)가 같은 도메인에서 `/api/v1/*` 호출. 프로덕션에선 Caddy가 정적 번들 + 백엔드 프록시를 한 origin으로 합침 → **CORS 불필요**.

## 변경 이력

`CHANGELOG.md` 에 기록. 새 기능은 `[Unreleased]` 섹션에 먼저 적고, 릴리스 시 버전 섹션으로 승격.

## 스켈레톤 → 실제 프로젝트 전환 시

이 레포에서 `Use this template`으로 출발한 뒤엔 업스트림 자동 동기화 안 함. 스켈레톤 업데이트는 CHANGELOG 확인해서 필요한 부분만 수동 반영.
