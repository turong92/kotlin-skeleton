# notification-sse

알림을 Server-Sent Events(`GET /notifications/sse`)로 브라우저에 전달한다.
Spring MVC 서블릿 앱용이다. 구독 토픽과 연결 유지 방식은 설정으로 정한다.
받는 사람이 정해진 알림(`NotificationEvent.recipientIds` 가 비어 있지 않음)은 그 사람(연결한 호출자의 `Principal.name`)에게만 흐르고, 받는 사람이 없는 알림은 모두에게 흐른다. 인증 없는 연결(`public-endpoint=true`)은 후자만 받는다.

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
