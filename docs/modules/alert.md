# alert

주인 경보 — 주인이 **먼저 알아야 하는 일**(5xx 몰림 · 기동 실패 · 죽은 작업, 그리고 앱이 정한 종류)을 Discord 호환 웹훅(과 선택적으로 메일)으로 알린다.
스켈레톤의 **에러 수집 답**이다: Sentry 가 아니라 주인에게 가는 경보. 웹훅 주소가 없으면 꺼져 있고, 켜도 업무 흐름에 던지지 않는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:alert"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `job-queue-jdbc`, `notification-mail` |
| 설정 접두사 | `skeleton.alert` — [docs/config/modules/alert.yml](../config/modules/alert.yml) |
| 기본 동작 | 꺼짐. `skeleton.alert.webhook-url` 을 정하면 켜진다 — 없어도 `OwnerAlerts` 는 있고 로그만 남긴다. |
| 부팅에 필요한 것 | 없음. 켤 때 웹훅 주소(환경변수)와 인터넷이 필요하다. |
| 교체 지점 | `OwnerAlerts`, `AlertStore`, `AlertChannel` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/alert/src/test`, `modules/alert/src/noOptionalTest` |

자세히: [주인 경보 상세](../alert.md) · DB 기록은 `alert-jdbc` 모듈 (모듈 색인)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
