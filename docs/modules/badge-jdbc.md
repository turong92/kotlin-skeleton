# badge-jdbc

`badge` 의 저장 포트 `BadgeStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 셋 — `badge_definitions`(정의, `unique(code)`) ·
`badge_awards`(보유 상태 한 줄, `unique(badge_id, account_id)`, 회수한 뒤 다시 받으면 같은 줄이 `ACTIVE` 로) · `badge_award_events`(수여/회수 내역, 덧붙이기만, `unique(action, source_type, source_ref)`).

규칙: 보유 줄은 **넣기를 먼저 하고 잠금 읽기를 나중에** 한다(없는 키를 `for update` 로 잠그면 MySQL 의 틈 잠금 때문에 동시에 온 두 호출이 교착한다) ·
상태 전이는 모두 `where status = …` 조건부 갱신이라 동시에 와도 한쪽만 이긴다(회수 · 되살리기) ·
넣기는 `SqlDialect.insertIgnore` 이고 넣었는지는 내가 만든 id 가 있는지로 본다(MySQL 은 이미 있는 행에도 1 을 돌려주고 PostgreSQL 은 유니크 위반 예외가 트랜잭션을 abort 시킨다) ·
`(AWARDED, GRANT, 지급 건 id)` 유니크가 일꾼 재시도를 한 번으로 접는다(출처 키가 null 인 줄 — 운영자 수여 · 회수 — 은 겹쳐도 된다) ·
**공개 프로필 질의(`publicBadgesOf`)는 `status = 'ACTIVE' and visible` 인 줄만 읽고 공개 모양 넷(`code · name · description · image_url`)만 가져온다** — 숨긴 배지 · 회수된 배지는 읽지 않는다 ·
내역은 id 순(시간순 정렬 id) 커서로 쪽을 넘기고 `(badge_id, id)` · `(account_id, id)` 색인을 쓴다 · 탈퇴 때 보유 줄과 내역을 지운다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:badge-jdbc"))` |
| 함께 오는 모듈 | `badge`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `badge` 와 `DataSource` · `db-*` 모듈이 있으면 저장소가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcBadgeStore` |
| 마이그레이션 | `modules/badge-jdbc/src/main/resources/db/migration/postgresql`, `modules/badge-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/rewards` |
| 테스트 | `modules/badge-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [badge](badge.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
