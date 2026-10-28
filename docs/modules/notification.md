# notification

제공자 중립 알림 계약(`NotificationBroker`, `NotificationRecipientResolver`, `NotificationInboxRepository`)과 기본 인메모리 브로커다.
전달 수단(SSE · WebSocket · Slack · 메일)과 영속(`notification-jdbc`)은 어댑터 모듈이 붙인다.

호출자의 받은편지함 HTTP 엔드포인트도 이 모듈이 연다 (서블릿 웹 앱 + Spring Security 가 있을 때, 인증 필요 · 기본 켜짐):
`GET /api/v1/notifications?page&size&unreadOnly&topic`(페이지 envelope — 줄마다 `link` · `collapsedCount`) · `GET /api/v1/notifications/unread-count`(`{ value: { count } }`) · `PATCH /api/v1/notifications/{eventId}/read` · `PATCH /api/v1/notifications/read-all` · `GET /api/v1/notifications/preferences` · `PUT /api/v1/notifications/preferences/{type}`(본문 `{ "muted": true }`, 목록 밖 종류는 404).
호출자는 `Authentication.name`(auth 모듈에서는 계정 id)이고 남의 알림은 404 다. 앱이 자기 컨트롤러를 두거나 끄려면 `skeleton.notification.inbox.enabled=false`.
알림을 보내는 쪽(`NotificationPublisher.publish`)은 앱 코드가 부른다 — 받는 사람은 `NotificationEvent.recipientIds`.

**클릭 이동 · 묶기**(둘 다 기본 null — 발행하는 쪽이 정하고, 모듈은 기본으로 묶지도 링크를 만들지도 않는다):
- `NotificationEvent.link` — 클릭하면 갈 **앱 안** 경로. `/` 로 시작하고 `//` 로 시작하지 않으며 1000자 이하(역슬래시 · 제어 문자도 거절 — 열린 리다이렉트 막이).
- `NotificationEvent.collapseKey` — 받는 사람의 **안 읽은** 같은 열쇠 줄에 접는다: 내용은 새 것, `collapsedCount` +1, `eventId`(읽음 주소)는 처음 것 그대로. 읽은 줄은 건드리지 않고 새 줄. 같은 이벤트 id 재발행은 수가 오르지 않는다.
- `NotificationInboxRepository` 의 새 메서드: `countUnread`(기본 구현 있음) · `deleteOlderThan(cutoff)` · `deleteByTopic` · `deleteByRecipientAndTopic`(지우기 셋은 추상 — 저장소를 직접 구현했다면 구현해야 한다). 서버 API 만, HTTP 로는 열지 않는다.

**보관 정리**: `skeleton.notification.retention.enabled=true` 면 `interval` 마다 `max-age`(기본 90일)보다 오래된 줄을 지운다. 기본 꺼짐. `scheduler` 모듈의 `skeletonTaskScheduler` 가 없으면 경고 한 줄만 남기고 돌지 않으며, 앱이 `NotificationInboxRetention.run(now)` 을 직접 부를 수 있다. 프로젝트 모드(`skeleton.projects`)면 서비스 중인 프로젝트마다 돈다.

**종류별 수신 끄기**: 끌 수 있는 종류는 제품이 `skeleton.notification.preferences.types` 로 정한다(기본 빈 목록 = 끌 수 있는 것 없음 — 보안 · 결제 알림처럼 목록에 없는 종류는 끄기 줄이 있어도 무시된다). 사람이 종류를 끄면 브로커가 받는 사람에서 그 사람을 빼고(받은편지함 + 실시간 전달 한 길만),
받는 사람이 정해진 알림을 **모두** 끄면 저장도 전달도 하지 않는다(빈 `recipientIds` 는 전체 공지라 그대로 넘기면 모두에게 간다). 받는 사람이 없는 전체 공지는 끌 수 없다. 끄기 저장소는 `NotificationMuteRepository`(기본 인메모리, `notification-jdbc` 는 표 `notification_mutes`) - 목록이 비면 조회조차 하지 않는다.

**커밋 뒤 전달**: 받은편지함 저장은 호출자 트랜잭션 안에서 바로 하고(롤백되면 알림도 없다), 구독자 전달(SSE · WebSocket · Slack)은 트랜잭션 동기화가 켜져 있으면(`spring-tx` 는 선택 의존 — 클래스가 없으면 바로 전달) **커밋 뒤**에 한다 — 롤백된 알림이 실시간으로 새지 않는다. 미뤄진 발행의 `deliveredSubscribers` 는 0 이고, 트랜잭션 밖 발행은 지금처럼 바로 전달한다. 알림 실패가 업무를 실패시키면 안 되면 커밋 뒤에 부르고 예외를 잡는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.notification.inbox` · `skeleton.notification.retention` · `skeleton.notification.preferences` — [docs/config/modules/notification.yml](../config/modules/notification.yml) |
| 기본 동작 | 켜짐. 기본은 인메모리 브로커다 (프로세스 안에서만 전달). 서블릿 앱이면 받은편지함 엔드포인트도 켜진다. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `NotificationBroker`, `NotificationRecipientResolver`, `NotificationInboxRepository`, `NotificationMuteRepository`, `NotificationInboxRetention`, `NotificationInboxController` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/notifications` |
| 테스트 | `modules/notification/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
