# auth-session-jdbc

`auth-session` 의 저장을 DB 에 둔다 — `auth_sessions`(기기 · IP · 마지막 사용 · 철회) 와 `auth_refresh_tokens`(토큰 해시, `used_at` 이 비면 지금 쓸 수 있는 토큰).
한 토큰을 두 번 쓰지 못하게 하는 것은 조건부 UPDATE 한 문장이다 — 16 스레드가 같은 토큰을 내밀어도 정확히 하나만 이긴다 (두 DB 에서 시험).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-session-jdbc"))` |
| 함께 오는 모듈 | `auth-session`, `auth`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcSessionStore` |
| 마이그레이션 | `modules/auth-session-jdbc/src/main/resources/db/migration/postgresql`, `modules/auth-session-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-session-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [auth-session](auth-session.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
