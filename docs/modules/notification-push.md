# notification-push

알림(`NotificationPublisher.publish`)이 받는 사람의 **기기로도** 가게 하는 푸시 알림의 허리다 — 계약(`PushSender` · `PushMessageFactory` · `PushDeviceStore` · `PushTypeMuteStore` · `PushTasks`), 알림 채널, 발송 작업 처리기, 기기 등록 HTTP(`/api/v1/push`)를 가진다.
실제 전달 수단(웹 푸시 · FCM)은 [notification-webpush](notification-webpush.md) · [notification-fcm](notification-fcm.md), 저장과 작업 큐는 [notification-push-jdbc](notification-push-jdbc.md), 광고 동의 어댑터는 [notification-push-legal](notification-push-legal.md) 가 붙인다. 인메모리 저장소는 없다(재시작하면 구독이 사라져 쓸모없다).

**꺼짐이 기본이다**(모듈 기본값 중립) — 앱 yml 이 켠다: `skeleton.notification.push.enabled=true` + 푸시로도 갈 종류 `types`(기본 빈 목록 = 아무것도 안 간다). 이 모듈을 안 붙인 앱, 붙였어도 안 켠 앱의 동작은 그대로다.

**흐름**: `publish(event)` → 브로커(끈 사람 빼기 · 받은편지함 저장) → 커밋 뒤 `PushNotificationChannel`(슬랙 전달기와 같은 서버 전체 리스너) → 받는 사람의 기기마다 작업 `notification.push` 하나 → 처리기 `PushDeliveryWorker` → `PushSender.send` 의 판정.
- 건너뜀: 받는 사람이 없는 전체 공지 · `types` 밖의 종류 · 그 종류의 푸시를 끈 사람 · `PushMessageFactory` 가 null · 지금 서버 공개 키와 다른 키로 구독한 웹 푸시 기기. 채널은 **모든 예외를 잡고 로그만** 남긴다(브로커는 던진 구독자를 닫아 채널이 영영 떨어진다). 커밋과 큐 넣기 사이에 죽으면 그 푸시는 잃는다 — 푸시는 최선 노력이고 받은편지함이 원본이다.
- 처리기는 보내기 직전에 다시 확인한다: 기기 줄이 없다 · **기기 주인이 바뀌었다**(공용 PC 에서 다른 사람이 로그인) · `ttl`(기본 24시간)이 지났다 · 큐에서 기다리는 동안 그 종류를 푸시만 끄게 됐다 → 보내지 않는다.
- 판정: `Delivered` → `last_success_at` · `Gone`(404 · 410 · `UNREGISTERED`) → 그 줄만 지운다 · `Retry` → 큐 백오프 재시도(`max-attempts` 기본 5) · `Failed` → 줄은 남기고 죽은 작업(우리 설정 실수가 구독 대량 삭제로 번지지 않게 — 웹 푸시 401 · 403 은 지우지 않는다).

**종류 × 채널**: 종류마다 「푸시만 끄기」(`push_type_mutes`, 알림함에는 그대로 쌓인다)는 기존 「모든 채널 끄기」(`skeleton.notification.preferences`)와 **별개**다. 화면은 `GET /preferences` 의 `allChannelsMuted` 가 켜지면 푸시 스위치를 잠근다.

**잠금 화면**(`skeleton.notification.push.lock-screen`): `FULL`(기본, 제목 · 본문 그대로) / `HIDDEN`(실제 내용을 푸시 서비스에 아예 보내지 않고 `hidden-title` 「새 알림이 있습니다」 + 링크만, 묶기 열쇠는 해시). 설정은 **기본 factory 만** 따른다 — 앱이 `PushMessageFactory` 를 바꾸면 가리기도 그 앱의 책임이다.

**세션**: `auth` 가 있으면(컴파일 전용) 기기 줄에 로그인 세션 id 를 적고, 세션이 끊기면(로그아웃 · 「모든 기기 로그아웃」 · 토큰 재사용 탐지) 같이 지운다. 없으면 이 연동만 빠진다.

**HTTP**(`/api/v1/push`, 서블릿 + Spring Security 일 때, `skeleton.notification.push.http.enabled=false` 로 뺀다, 모두 인증): `GET /config` · `GET /devices` · `PUT /devices/{installationId}` · `DELETE /devices/{installationId}` · `POST /devices/{installationId}/test`(계정당 60초에 한 번) · `GET /preferences` · `PUT /preferences/{type}`. 모양: [push-http-contract](../push-http-contract.md).
계정 지우기와 저장은 `notification-push-jdbc` 가 한다. **광고성 종류**(`advertising-types`)의 동의 게이트는 `notification-push-legal` 이 붙는 조각에서 켜진다 — 그 전에는 이 종류의 푸시가 나가지 않는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-push"))` |
| 함께 오는 모듈 | `notification`, `platform` |
| 컴파일 전용 | `auth` |
| 설정 접두사 | `skeleton.notification.push` — [docs/config/modules/notification-push.yml](../config/modules/notification-push.yml) |
| 기본 동작 | 꺼짐. `skeleton.notification.push.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | 켰다면 `PushDeviceStore` · `PushTypeMuteStore` · `PushTasks` 빈(`notification-push-jdbc` 가 낸다) — 없으면 시작이 실패하고 무엇을 얹을지 말한다. 전달 수단(`PushSender`)은 없어도 뜬다(기기 등록이 400). |
| 교체 지점 | `PushSender`, `PushMessageFactory`, `PushDeviceStore`, `PushTypeMuteStore`, `PushTasks`, `PushSessionIdResolver`, `PushNotificationChannel`, `PushDeliveryWorker`, `PushDeviceService`, `PushController` |
| 마이그레이션 | 없음 (표는 `notification-push-jdbc`) |
| 프론트 짝 | 없음 — 짝 프런트 패키지(@skeleton/push)는 react-skeleton 에서 준비 중 |
| 테스트 | `modules/notification-push/src/test`, `modules/notification-push/src/noOptionalTest` (`auth` 없이 뜨는지) |

자세히: [notification](notification.md) · [푸시 HTTP 계약](../push-http-contract.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
