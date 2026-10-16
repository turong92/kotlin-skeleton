# notification-websocket

알림을 STOMP over WebSocket(`/ws/notifications`)으로 전달한다. 인증 · trace 인터셉터와 하트비트를 갖춘 인프로세스 브로커를 쓴다.
`skeleton.notification-websocket.authentication.enabled=true` 가 되기 전까지 엔드포인트는 열려 있다. 푸시 전용이라 클라이언트의 브로커 목적지 `SEND` 와 알림 토픽·사용자 목적지 밖 `SUBSCRIBE` 는 거절한다(`/app` 아래 `@MessageMapping` 은 앱이 직접 인가한다).

프로젝트 여럿([multi-project](../multi-project.md)): 세션의 프로젝트는 **연결(핸드셰이크) 요청의 Host** 다 — 서버가 세션 속성에 적고 클라이언트는 바꿀 수 없다(프로젝트 없이 온 연결은 받지 않는다). 토큰(`NotificationWebSocketTokenVerifier`)은 그 프로젝트 문맥에서 검증하고(프로젝트마다 JWT 키가 다르다), 세션의 사용자 이름은 `<프로젝트>|<계정 id>`(`ProjectUserPrincipal`)가 된다 — 브리지가 같은 키로 보내므로 두 프로젝트에 같은 계정 id 가 있어도 사용자 알림이 섞이지 않는다(앱의 `@MessageMapping` 이 받는 `Principal` 도 이 이름이다).
전체 공개 토픽은 브로커 구독 선택자(`selector`)로 나눈다 — 서버가 `project` 머리글이 같은 알림만 받는 선택자를 구독에 붙이고(클라이언트가 보낸 `selector` 는 덮어쓴다), 브리지는 알림을 발행한 프로젝트의 머리글을 단다. 프로젝트 없이 발행한 알림은 어느 프로젝트의 공개 토픽에도 가지 않는다. `extra-subscribe-destination-prefixes` 로 연 앱 자신의 토픽은 선택자를 붙이지 않는다 — 프로젝트를 나누는 것은 앱의 몫이다. 허용 출처(`endpoint.allowed-origin-patterns`)는 서버 공통이다. 목록이 비면 지금과 같다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-websocket"))` |
| 함께 오는 모듈 | `platform`, `notification` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.notification-websocket` — [docs/config/modules/notification-websocket.yml](../config/modules/notification-websocket.yml) |
| 기본 동작 | 켜짐 (인프로세스 simple broker). |
| 부팅에 필요한 것 | 서블릿 웹 앱. |
| 교체 지점 | `NotificationWebSocketAuthenticationInterceptor`, `NotificationWebSocketInboundAuthorizationInterceptor`, `NotificationWebSocketDestinationResolver`, `NotificationWebSocketTraceInterceptor` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/realtime` |
| 테스트 | `modules/notification-websocket/src/test` |

자세히: [WebSocket 알림 상세](../notification-websocket.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
