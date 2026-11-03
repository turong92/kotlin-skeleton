# notification-broadcast-jdbc

`notification-broadcast` 의 저장소 포트 `BroadcastStore` · 작업 포트 `BroadcastTasks` 를 JDBC(PostgreSQL · MySQL)로 구현하고, 단계 처리기를 `job-queue-jdbc` 위에 얹는다. **공지를 켠 앱(`skeleton.notification.broadcast.enabled=true`)에서만 선다** — 꺼진 앱은 이 모듈이 없는 앱과 같다.
표 둘: `broadcasts`(공지 한 줄 — 상태 · 내용 · 대상 · 이어 달리기 상태 `run_token` / `next_seq` / `cursor_id` · 예상 / 처리 수 · 누가 · 언제 · 실패 사유) · `broadcast_channel_stats`(고른 채널마다 한 줄 — `queued` · `delivered` · `failed` · `dropped` · `opened`, 고리가 `col = col + n` 원자 증가). 받는 사람 데이터는 없다(수만 명을 담지 않는다) — 계정 지우기 대상이 아니다. 시각은 PostgreSQL `timestamptz` · MySQL `datetime(6)`(UTC). 프로젝트 모드면 프로젝트 스키마마다 표가 생긴다.

규칙: 상태 전이는 모두 `where status = …`(보내기는 `DRAFT`, 취소는 `SCHEDULED | SENDING` …) 조건부 갱신 — 동시에 눌러도 한 줄만 갱신된다. 단계 작업은 공지 줄을 `for update` 로 잡고(두 방언 공통) 토큰 · 순번이 같을 때만 움직인다; 알림함 저장 · 커서 · 다음 작업 넣기가 **한 트랜잭션**(`TransactionTemplate`)이라 중간에 죽으면 그 묶음은 통째로 되돌아가 다시 읽는다.
작업 어댑터: `notification.broadcast.step` 작업 하나 = 대상 한 묶음(내용 `{id, token, seq}`). 넣기는 `runAt` 을 받는다(예약 시각 · 묶음 사이 간격). 처리기(`BroadcastStepJobHandler`)는 영구 실패(대상 종류가 없어짐)를 재시도 없이 DEAD 로 보내고, 작업이 DEAD 가 되는 모든 길에서 `BroadcastDeadListener` 가 공지를 `FAILED` 로 바꾼다(`failure_reason` 에는 예외 종류만). 큐가 꺼져 있으면(`skeleton.job-queue.enabled=false`) 켠 앱의 시작이 실패한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-broadcast-jdbc"))` |
| 함께 오는 모듈 | `notification-broadcast`, `notification`, `platform`, `persistence-jdbc`, `job-queue-jdbc`, `json` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (공지 설정은 `notification-broadcast` 에서 정한다) |
| 기본 동작 | 조건부. 공지를 켠 앱에서 `DataSource` · `db-*` 모듈이 있으면 저장소 · 작업 · 처리기가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, `job-queue-jdbc` 의 스키마, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcBroadcastStore`, `JdbcBroadcastTasks`, `BroadcastStepJobHandler`, `BroadcastDeadListener` |
| 마이그레이션 | `modules/notification-broadcast-jdbc/src/main/resources/db/migration/postgresql`, `modules/notification-broadcast-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/notifications` |
| 테스트 | `modules/notification-broadcast-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [notification-broadcast](notification-broadcast.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
