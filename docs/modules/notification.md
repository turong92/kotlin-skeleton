# notification

제공자 중립 알림 계약(`NotificationBroker`, `NotificationRecipientResolver`, `NotificationInboxRepository`)과 기본 인메모리 브로커다.
전달 수단(SSE · WebSocket · Slack · 메일)과 영속(`notification-jdbc`)은 어댑터 모듈이 붙인다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification"))` |
| 함께 오는 모듈 | 없음 |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. 기본은 인메모리 브로커다 (프로세스 안에서만 전달). |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `NotificationBroker`, `NotificationRecipientResolver`, `NotificationInboxRepository` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/notification/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
