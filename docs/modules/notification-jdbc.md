# notification-jdbc

`NotificationInboxRepository` 를 JDBC 로 구현해 알림 인박스를 `notification_inbox` 테이블에 저장하고, `NotificationMuteRepository` 로 종류별 수신 끄기를 `notification_mutes`(끈 것만 줄이 있다)에 저장한다.
페이로드는 `json` 의 `JsonCodec` 으로 직렬화한다. `json` 이 새로 끌고 오는 웹 스택은 없다: webflux · validation · springdoc 은 `platform` 이 이미 가져온다.

**같은 열쇠 묶기는 DB 가 원자적으로 막는다**: `unread_collapse_key`(안 읽은 묶음 줄만 값, 읽으면 null)에 `(recipient_id, unread_collapse_key)` 유니크가 있어 같은 열쇠 안 읽은 줄은 사람마다 하나뿐이다(PostgreSQL · MySQL 모두 null 은 겹쳐도 된다).
저장은 「넣기(어느 유니크 충돌이든 무시) → 내 이벤트 줄이 있으면 끝 → 없으면 안 읽은 줄을 접기」 순서이고, 같은 열쇠를 동시에 저장하는 두 트랜잭션도 한 줄에 수 2 로 끝난다. 접기를 먼저 시도하면 MySQL 이 간격 잠금으로 교착한다(실측). 부르는 쪽 트랜잭션 안에서 돌아 롤백되면 같이 사라진다.
`deleteOlderThan` · `deleteByTopic` · `deleteByRecipientAndTopic` 은 1000줄씩 나눠 지운다. 방언 `insertIgnore` 는 충돌 칼럼 목록을 비우면 「어느 유니크 충돌이든 무시」로 받는다.

**계정 삭제**: `account` 가 계정을 지울 때(platform 의 `AccountErasureListener`) 그 계정의 받은편지함 줄과 알림 끄기 줄(`notification_mutes`)을 지우고, 같은 이벤트를 받은 다른 사람의 줄 안 수신자 목록에서 계정 id 를 톰스톤으로 바꾼다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-jdbc"))` |
| 함께 오는 모듈 | `notification`, `json`, `persistence-jdbc`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `NotificationInboxRepository`, `NotificationMuteRepository`, `notificationInboxErasureListener` |
| 마이그레이션 | `modules/notification-jdbc/src/main/resources/db/migration/postgresql`, `modules/notification-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/notifications` |
| 테스트 | `modules/notification-jdbc/src/test`, `modules/notification-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
