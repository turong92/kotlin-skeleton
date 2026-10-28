# notification-sse

알림을 Server-Sent Events(`GET /notifications/sse`)로 브라우저에 전달한다.
Spring MVC 서블릿 앱용이다. 구독 토픽과 연결 유지 방식은 설정으로 정한다.
받는 사람이 정해진 알림(`NotificationEvent.recipientIds` 가 비어 있지 않음)은 그 사람(연결한 호출자의 `Principal.name`)에게만 흐르고, 받는 사람이 없는 알림은 모두에게 흐른다. 인증 없는 연결(`public-endpoint=true`)은 후자만 받는다.

**심장박동**: `skeleton.notification.sse.heartbeat`(기본 비움 = 꺼짐)를 `15s` 처럼 켜면 서비스가 데몬 스레드(`notification-sse-heartbeat`) 하나로 열린 연결마다 그 간격으로 SSE 주석(`:` 한 줄)을 보낸다.
`EventSource` 와 `@skeleton/realtime` 파서는 주석을 사건으로 세지 않는다. 조용한 연결을 프록시가 유휴로 끊지 않게 하는 것이 목적이다 - Cloudflare 는 유휴 100초, 흔한 프록시는 60초라 그보다 짧게 잡는다.
보내기에 실패한 연결은 그 연결만 닫히고, 종료(`stop`) 때 스레드도 멈춘다. 짝 클라이언트는 `idleTimeoutMs` 를 심장박동 간격의 세 배쯤(15초면 45초)으로 두면 끊긴 연결을 알아채고 다시 붙는다(`useLiveNotifications`).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-sse"))` |
| 함께 오는 모듈 | `platform`, `notification` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.notification.sse` — [docs/config/modules/notification-sse.yml](../config/modules/notification-sse.yml) |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `NotificationSseService`, `NotificationSseController` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/realtime` |
| 테스트 | `modules/notification-sse/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
