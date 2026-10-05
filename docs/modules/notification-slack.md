# notification-slack

알림과 `@SlackException` 예외 알림을 Slack 웹훅으로 보낸다. 메시지 모양은 `SlackAlertMessageFactory` 가 만든다.
민감값은 `platform` 의 마스커로 가린다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-slack"))` |
| 함께 오는 모듈 | `platform`, `notification` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.notification.slack` — [docs/config/modules/notification-slack.yml](../config/modules/notification-slack.yml) |
| 기본 동작 | 조건부. 연결은 되어 있지만 `enabled=true` 와 `webhook-url` 이 있어야 보낸다. |
| 부팅에 필요한 것 | 없음. 켤 때 웹훅 URL 과 인터넷이 필요하다. |
| 교체 지점 | `SlackAlertSender`, `SlackAlertMessageFactory` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/notification-slack/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
