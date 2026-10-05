# notification

제공자 중립 알림 계약(`NotificationBroker`, `NotificationRecipientResolver`, `NotificationInboxRepository`)과 기본 인메모리 브로커다.
전달 수단(SSE · WebSocket · Slack · 메일)과 영속(`notification-jdbc`)은 어댑터 모듈이 붙인다.

호출자의 받은편지함 HTTP 엔드포인트도 이 모듈이 연다 (서블릿 웹 앱 + Spring Security 가 있을 때, 인증 필요 · 기본 켜짐):
`GET /api/v1/notifications?page&size&unreadOnly&topic`(페이지 envelope) · `PATCH /api/v1/notifications/{eventId}/read` · `PATCH /api/v1/notifications/read-all`.
호출자는 `Authentication.name`(auth 모듈에서는 계정 id)이고 남의 알림은 404 다. 앱이 자기 컨트롤러를 두거나 끄려면 `skeleton.notification.inbox.enabled=false`.
알림을 보내는 쪽(`NotificationPublisher.publish`)은 앱 코드가 부른다 — 받는 사람은 `NotificationEvent.recipientIds`.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.notification.inbox` — [docs/config/modules/notification.yml](../config/modules/notification.yml) |
| 기본 동작 | 켜짐. 기본은 인메모리 브로커다 (프로세스 안에서만 전달). 서블릿 앱이면 받은편지함 엔드포인트도 켜진다. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `NotificationBroker`, `NotificationRecipientResolver`, `NotificationInboxRepository`, `NotificationInboxController` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/notifications` |
| 테스트 | `modules/notification/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
