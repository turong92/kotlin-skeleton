# notification-websocket

알림을 STOMP over WebSocket(`/ws/notifications`)으로 전달한다. 인증 · trace 인터셉터와 하트비트를 갖춘 인프로세스 브로커를 쓴다.
`skeleton.notification-websocket.authentication.enabled=true` 가 되기 전까지 엔드포인트는 열려 있다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-websocket"))` |
| 함께 오는 모듈 | `platform`, `notification` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.notification-websocket` — [docs/config/modules/notification-websocket.yml](../config/modules/notification-websocket.yml) |
| 기본 동작 | 켜짐 (인프로세스 simple broker). |
| 부팅에 필요한 것 | 서블릿 웹 앱. |
| 교체 지점 | `NotificationWebSocketAuthenticationInterceptor`, `NotificationWebSocketDestinationResolver`, `NotificationWebSocketTraceInterceptor` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/realtime` |
| 테스트 | `modules/notification-websocket/src/test` |

자세히: [WebSocket 알림 상세](../notification-websocket.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
