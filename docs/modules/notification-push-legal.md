# notification-push-legal

광고성 푸시의 동의 어댑터 — 규칙(동의 없이는 안 보냄 · 야간 보류 · 제목 `(광고)` · 재확인)은 `notification-push` 코어가 갖고, **동의를 읽는 일**을 `legal` 의 동의 기록으로 이 모듈이 구현한다(`-jdbc` 와 같은 포트/어댑터 짝 — 코어가 `legal` 을 몰라야 광고를 안 쓰는 앱이 `legal` 없이 푸시를 쓴다).
**지금은 모듈의 자리만 잡혀 있다**(Gradle 모듈 · 카탈로그 · 이 문서). 정책 구현 · 변경 통지 · 재확인 스캔 · `GET /api/v1/push/advertising` 은 이어지는 조각에서 채워지고, 그때 이 문서가 채워진다.
그 전에는 `skeleton.notification.push.advertising-types` 의 광고성 종류가 푸시로 나가지 않는다(안전 쪽). 법률 자문이 아니다 — 광고 문서 내용 · 전송자 정보 · 법 해석은 앱의 몫이다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-push-legal"))` |
| 함께 오는 모듈 | `notification-push`, `notification`, `platform`, `legal` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 꺼짐. 구현이 들어오기 전에는 아무 빈도 없다. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | 없음 |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/notification-push-legal/src/test` |

자세히: [notification-push](notification-push.md) · [legal](legal.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
