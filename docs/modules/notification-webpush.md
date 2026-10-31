# notification-webpush

웹 푸시(VAPID) 전달 수단 — `notification-push` 의 `PushSender`(kind `WEBPUSH`)를 구현해 브라우저 구독으로 푸시를 보낸다.
**지금은 모듈의 자리만 잡혀 있다**(Gradle 모듈 · 카탈로그 · 이 문서). 구현(암호화 · VAPID 서명 · 끝점 허용 목록 · 판정 표 · 키 쌍 확인 · `DeployGuard`)은 이어지는 조각에서 채워지고, 그때 이 문서의 설정 · 교체 지점 · 부팅 조건이 함께 채워진다.
그때까지 이 모듈을 얹어도 코드가 없어 아무 일도 하지 않는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-webpush"))` |
| 함께 오는 모듈 | `notification-push`, `notification`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 꺼짐. 구현이 들어오기 전에는 아무 빈도 없다. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | 없음 |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/notification-webpush/src/test` |

자세히: [notification-push](notification-push.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
