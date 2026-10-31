# notification-push-jdbc

`notification-push` 의 기기 저장소 `PushDeviceStore` · 「푸시만 끄기」 저장소 `PushTypeMuteStore` · 작업 포트 `PushTasks` 를 JDBC(PostgreSQL · MySQL)로 구현하고, 발송 처리기를 `job-queue-jdbc` 위에 얹는다.
표 둘: `push_devices`(한 행 = 한 설치) · `push_type_mutes`(푸시만 끈 종류만 줄이 있다). 시각은 PostgreSQL `timestamptz` · MySQL `datetime(6)`(UTC).

규칙: `installation_id` 유니크(클라이언트가 만든 무작위 값, 한 설치 = 한 줄) · `address_hash` 유니크(sha256(`kind` + 개행 + 주소) — 같은 주소는 한 줄에만).
**등록은 한 트랜잭션**이다: 같은 주소의 **다른 설치** 줄 지우기(주소가 다른 계정으로 넘어가도 옛 줄이 없어진다) → 넣기(`SqlDialect.insertIgnore`) → 갱신(주인 · 세션 · 주소 · 열쇠 · 키 지문 · `last_seen_at`) → 그 계정 기기가 `max-devices-per-account`(기본 20)를 넘으면 `last_seen_at` 이 가장 오랜 것부터 지우기.
같은 설치의 동시 `PUT` 둘은 한 줄로 끝난다. 상한은 순차 등록에서 정확하고, 같은 계정의 동시 등록은 일시적으로 상한을 넘을 수 있다(다음 등록이 다시 줄인다). **지우기는 모두 계정을 같이 본다** — 남의 설치 id 로는 아무것도 지워지지 않는다.
주소(웹 푸시 endpoint · FCM 토큰)와 `auth_secret` 은 암호화하지 않는다: endpoint 는 서버의 VAPID 개인 키 없이는 쓸 수 없는 주소다. 그래도 로그 · 응답 · `toString` 에는 싣지 않는다. 프로젝트 모드면 프로젝트 스키마마다 표가 생긴다.

작업 어댑터: `notification.push` 작업 하나 = 기기 하나에 푸시 하나(내용은 기기 id · 받는 사람 id · 메시지). 넣기는 `runAt` 을 받는다(보류). 처리기(`PushJobHandler`)는 일꾼의 재시도 신호를 큐 백오프에 맡기고, 영구 실패는 재시도 없이 바로 DEAD 로 보낸다.
**계정 지우기**(`AccountErasureListener`)는 그 계정의 기기 · 끄기 줄을 모두 지운다(멱등). 오래 안 본 기기 정리는 `notification-push` 의 설정(`stale-cleanup`, 기본 꺼짐)이 이 저장소의 `deleteStale` 을 1000줄씩 돈다. 큐가 꺼져 있으면(`skeleton.job-queue.enabled=false`) 시작이 실패한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-push-jdbc"))` |
| 함께 오는 모듈 | `notification-push`, `notification`, `platform`, `persistence-jdbc`, `job-queue-jdbc`, `json` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (푸시 설정은 `notification-push` 에서 정한다) |
| 기본 동작 | 켜짐. `DataSource` · `db-*` 모듈이 있으면 저장소 · 작업 · 처리기가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, `job-queue-jdbc` 의 스키마, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcPushDeviceStore`, `JdbcPushTypeMuteStore`, `JdbcPushTasks`, `PushJobHandler`, `pushErasureListener` |
| 마이그레이션 | `modules/notification-push-jdbc/src/main/resources/db/migration/postgresql`, `modules/notification-push-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/notification-push-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [notification-push](notification-push.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
