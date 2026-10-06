# notification-jdbc

`NotificationInboxRepository` 를 JDBC 로 구현해 알림 인박스를 `notification_inbox` 테이블에 저장한다.
페이로드는 `json` 의 `JsonCodec` 으로 직렬화한다. `json` 이 새로 끌고 오는 웹 스택은 없다: webflux · validation · springdoc 은 `platform` 이 이미 가져온다.

**계정 삭제**: `account` 가 계정을 지울 때(platform 의 `AccountErasureListener`) 그 계정의 받은편지함 줄을 지우고, 같은 이벤트를 받은 다른 사람의 줄 안 수신자 목록에서 계정 id 를 톰스톤으로 바꾼다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-jdbc"))` |
| 함께 오는 모듈 | `notification`, `json`, `persistence-jdbc`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `NotificationInboxRepository`, `notificationInboxErasureListener` |
| 마이그레이션 | `modules/notification-jdbc/src/main/resources/db/migration/postgresql`, `modules/notification-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/notifications` |
| 테스트 | `modules/notification-jdbc/src/test`, `modules/notification-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
