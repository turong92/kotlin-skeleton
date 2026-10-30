# auth-mfa-jdbc

`auth-mfa` 의 저장 포트 `MfaStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 둘 — `mfa_totp`(계정당 한 줄: `secret_enc` · `last_used_step` · 재등록 대기 `pending_*`)와 `mfa_recovery_codes`(`unique(account_id, code_hash)`).

규칙: 비밀 · 복구 코드 원문은 이 표에도 없다(`secret_enc` 는 `crypto` 의 `aes-gcm:v1:…` 봉투, `code_hash` 는 SHA-256 16진 64자) ·
상태 전이는 모두 **한 문장의 조건부 갱신**이라 동시에 와도 한 요청만 이긴다 — 대기 → 비밀 옮김은 `where pending_secret_enc = :expected and pending_expires_at > :now`(MySQL 의 왼쪽부터 평가되는 대입 순서까지 맞췄다), 같은 TOTP 걸음은 `where last_used_step is null or last_used_step < :step`, 복구 코드는 `where used_at is null` ·
두 표를 함께 건드리는 일(첫 등록의 복구 코드 · 끄기)만 한 트랜잭션 · 재등록은 옛 `secret_enc` 를 두고 `pending_*` 만 새로 쓴다 · 탈퇴 때 두 표의 그 계정 줄을 지운다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-mfa-jdbc"))` |
| 함께 오는 모듈 | `account`, `auth`, `auth-mfa`, `crypto`, `persistence-jdbc`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `auth-mfa` 와 `DataSource` · `db-*` 모듈이 있으면 저장소가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcMfaStore` |
| 마이그레이션 | `modules/auth-mfa-jdbc/src/main/resources/db/migration/postgresql`, `modules/auth-mfa-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/auth-mfa-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [auth-mfa](auth-mfa.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
