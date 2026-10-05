# async-notification

`async` 의 처리되지 않은 예외(`AsyncUncaughtExceptionHandler`)를 `notification` 으로 흘려 보낸다.
두 계약 모듈을 잇는 얇은 어댑터라 둘 다 따라온다. 알림을 어디로 보낼지는 `notification-*` 모듈이 정한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:async-notification"))` |
| 함께 오는 모듈 | `async`, `notification`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.async-notification` — [docs/config/modules/async-notification.yml](../config/modules/async-notification.yml) |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `AsyncUncaughtExceptionHandler` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/async-notification/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
