# api-key-jdbc

`api-key` 의 저장 포트 `ApiKeyStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 하나 — `api_keys`(`unique(token_hash)`, `(owner_type, owner_id, created_at)` 색인).

규칙: 토큰 원문은 이 표에도 없다(`token_hash` 는 SHA-256 16진 64자, `token_hint` 는 화면용 `접두사…끝 4자`) ·
상태 전이는 모두 한 문장의 조건부 갱신이라 동시에 와도 한쪽만 이긴다 — 회수는 `where revoked_at is null`, **마지막 사용은 `where last_used_at is null or last_used_at < :stale_before`**(1분 해상도 — 같은 키의 요청이 몰려도 쓰기는 분당 한 번) ·
활성 수는 `revoked_at is null and (expires_at is null or expires_at > :now)` · 범위는 공백으로 구분한 한 칸(`varchar(1000)`) · 탈퇴 때 그 주인의 줄을 모두 지운다.
`owner_type` · `owner_id` · `created_by` 칸이 있어 다음 판의 소유자 종류(워크스페이스)가 표를 바꾸지 않고 들어오고, `revoked_by` · `revoked_reason` 은 운영 회수가 쓸 자리다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:api-key-jdbc"))` |
| 함께 오는 모듈 | `account`, `api-key`, `auth`, `persistence-jdbc`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `api-key` 와 `DataSource` · `db-*` 모듈이 있으면 저장소가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcApiKeyStore` |
| 마이그레이션 | `modules/api-key-jdbc/src/main/resources/db/migration/postgresql`, `modules/api-key-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/api-key-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [api-key](api-key.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
