# waiting-room-jdbc

`waiting-room` 의 저장 포트 `WaitingRoomStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 셋 — `waiting_rooms`(대기실: 오픈 시각 · 오픈 전 줄 시작 · 입장 시간 · 상태 · 섞은 시각 `shuffled_at` · 번호의 끝 `queue_tail`) · `waiting_room_rates`(속도 구간, 멈춤 = 0) · `waiting_room_tickets`(순번표, 유니크 (방, 계정) 와 (방, 번호)). 계정 id 에는 외래 키가 없다.

두 방언이 같은 SQL 이다:
- **번호는 방 줄을 먼저 잠그고 준다** — `queue_tail = queue_tail + 1` 갱신이 방 줄 잠금이고 같은 트랜잭션에서 읽은 값이 내 번호다. 순번표 넣기가 유니크(방, 계정)에 걸리면 트랜잭션이 통째로 되돌려져 번호도 되돌아간다 → 1..n 빈틈이 없다.
- **섞기는 조건부 갱신**(`shuffled_at is null`)을 이긴 한 번뿐이고, 번호 매기기까지 한 트랜잭션이다. 진 호출은 이긴 쪽이 끝나야 풀려나므로 돌아왔을 때는 번호가 다 매겨져 있다. 섞기와 겹쳐 커밋된 늦은 오픈 전 줄은 다음 요청에서 맨 뒤 번호를 받는다.
- 시각은 서비스의 `TimeProvider` 한 곳에서 온다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:waiting-room-jdbc"))` |
| 함께 오는 모듈 | `waiting-room`, `persistence-jdbc`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 저장소가 서고, 그러면 `waiting-room` 의 서비스가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcWaitingRoomStore` |
| 마이그레이션 | `modules/waiting-room-jdbc/src/main/resources/db/migration/postgresql`, `modules/waiting-room-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/waiting-room-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [waiting-room](waiting-room.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
