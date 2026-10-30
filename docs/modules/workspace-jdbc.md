# workspace-jdbc

`workspace` 의 저장 포트 `WorkspaceStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 넷 — `workspaces`(`unique(slug)`) · `workspace_slug_history`(바꾸기 전의 slug, 다른 워크스페이스가 못 가져간다) ·
`workspace_members`(`pk(workspace_id, account_id)`, **`owner_key` 유니크 — OWNER 줄에만 워크스페이스 id 가 있어 소유자 둘은 커밋될 수 없다**) · `workspace_invitations`(초대 흐름이 쓴다 — 토큰은 sha256 만, `pending_key` 유니크로 같은 주소에 대기 초대는 하나).

규칙: **모든 변경은 `workspaces` 줄을 `for update` 로 먼저 잡고** 호출자의 지금 역할을 다시 읽어 판정한다(「최소 하나」· 역할 검사 · 좌석 검사가 같은 잠금 아래에서 이뤄진다) ·
소유권 이전은 옛 소유자를 ADMIN 으로 먼저 내려 소유자 표시를 비우고 새 소유자를 세운다 — 소유자가 이미 있는데 또 세우면 유니크 위반이 올라와 트랜잭션이 되돌려진다 ·
워크스페이스 넣기는 `SqlDialect.insertIgnore` 이고 넣었는지는 내가 만든 id 가 있는지로 본다(PG 의 유니크 위반 예외는 트랜잭션 전체를 abort 시키고 MySQL 은 이미 있는 행에도 1 을 돌려준다) ·
slug 가 겹치면 넣기가 조용히 비켜 서비스가 다음 후보(`-2`…)를 시도하고, 바꾸기는 유니크 위반을 잡아 `SLUG_TAKEN` 으로 바꾼다 · 시각은 PG `timestamptz` · MySQL `datetime(6)`(UTC) ·
계정 삭제 때 멤버 줄을 지우고 `created_by` · `invited_by` · `accepted_by` 를 묘비 값으로 바꾼다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:workspace-jdbc"))` |
| 함께 오는 모듈 | `workspace`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `workspace` 와 `DataSource` · `db-*` 모듈이 있으면 저장소가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcWorkspaceStore` |
| 마이그레이션 | `modules/workspace-jdbc/src/main/resources/db/migration/postgresql`, `modules/workspace-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/workspace-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [workspace](workspace.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
