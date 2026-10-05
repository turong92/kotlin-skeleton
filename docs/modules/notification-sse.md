# notification-sse

알림을 Server-Sent Events(`GET /notifications/sse`)로 브라우저에 전달한다.
Spring MVC 서블릿 앱용이다. 구독 토픽과 연결 유지 방식은 설정으로 정한다.

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
