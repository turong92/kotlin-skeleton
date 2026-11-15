# notification-scheduled-jdbc

`notification-scheduled` 의 저장소 포트 `ScheduledPushStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. **예약 푸시를 켠 앱(`skeleton.notification.scheduled.enabled=true`)에서만 선다** — 꺼진 앱은 이 모듈이 없는 앱과 같다.
표 하나 `scheduled_pushes`(예약 한 통 = 한 줄, 기본 키 = 앱이 정한 키 `push_key`): 받는 사람 · 시각 · 제목 · 본문 · 링크 · 광고 여부 · 상태(`PENDING` → `SENT` | `SKIPPED`) · 건너뛴 이유 · 임대 만료(`claimed_until`) · 시도 수. 시각은 PostgreSQL `timestamptz` · MySQL `datetime(6)`(UTC). 프로젝트 모드면 프로젝트 스키마마다 표가 생긴다.

규칙: 상태를 바꾸는 문장은 모두 `where status = 'PENDING' …` 조건부 갱신이다 — 별도 잠금 · 트랜잭션 없이 여러 인스턴스 중 하나만 한 줄을 집는다(집기 = `claimed_until` 을 임대 시간만큼 앞으로 밀기). 같은 키 예약은 아직 안 나간(집히지 않은) 줄만 바꾸고, 그 밖이면 그대로 둔다. 새 줄은 두 방언 모두 insert-ignore(`SqlDialect.insertIgnore`)로 넣는다. 호출자의 트랜잭션이 있으면 거기에 참여한다(앱이 사건을 저장하는 트랜잭션 안에서 예약해도 된다).
`recipient_id` 로 탈퇴 삭제(`deleteByRecipient`)와 하루 상한 집계(`sentCountSince`)를 한다. 끝난 줄은 `keep-finished` 뒤 훑기가 지운다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-scheduled-jdbc"))` |
| 함께 오는 모듈 | `notification-scheduled`, `notification`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (예약 설정은 `notification-scheduled` 에서 정한다) |
| 기본 동작 | 조건부. 예약 푸시를 켠 앱에서 `DataSource` · `db-*` 모듈이 있으면 저장소가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcScheduledPushStore` |
| 마이그레이션 | `modules/notification-scheduled-jdbc/src/main/resources/db/migration/postgresql`, `modules/notification-scheduled-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/notification-scheduled-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [notification-scheduled](notification-scheduled.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
