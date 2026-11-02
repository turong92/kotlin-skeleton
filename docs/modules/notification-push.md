# notification-push

알림(`NotificationPublisher.publish`)이 받는 사람의 **기기로도** 가게 하는 푸시 알림의 허리다 — 계약(`PushSender` · `PushMessageFactory` · `PushDeviceStore` · `PushTypeMuteStore` · `PushTasks`), 알림 채널, 발송 작업 처리기, 기기 등록 HTTP(`/api/v1/push`)를 가진다.
실제 전달 수단(웹 푸시 · FCM)은 [notification-webpush](notification-webpush.md) · [notification-fcm](notification-fcm.md), 저장과 작업 큐는 [notification-push-jdbc](notification-push-jdbc.md), 광고 동의 어댑터는 [notification-push-legal](notification-push-legal.md) 가 붙인다. 인메모리 저장소는 없다(재시작하면 구독이 사라져 쓸모없다).

**꺼짐이 기본이다**(모듈 기본값 중립) — 앱 yml 이 켠다: `skeleton.notification.push.enabled=true` + 푸시로도 갈 종류 `types`(기본 빈 목록 = 아무것도 안 간다). 이 모듈을 안 붙인 앱, 붙였어도 안 켠 앱의 동작은 그대로다.

**흐름**: `publish(event)` → 브로커(끈 사람 빼기 · 받은편지함 저장) → 커밋 뒤 `PushNotificationChannel`(슬랙 전달기와 같은 서버 전체 리스너) → 받는 사람의 기기마다 작업 `notification.push` 하나 → 처리기 `PushDeliveryWorker` → `PushSender.send` 의 판정.
- 건너뜀: 받는 사람이 없는 전체 공지 · `types` 와 광고 종류(`skeleton.notification.advertising.types`) 밖의 종류 · 그 종류의 푸시를 끈 사람 · `PushMessageFactory` 가 null · 지금 서버 공개 키와 다른 키로 구독한 웹 푸시 기기. 채널은 **모든 예외를 잡고 로그만** 남긴다(브로커는 던진 구독자를 닫아 채널이 영영 떨어진다). 커밋과 큐 넣기 사이에 죽으면 그 푸시는 잃는다 — 푸시는 최선 노력이고 받은편지함이 원본이다.
- 처리기는 보내기 직전에 다시 확인한다: 기기 줄이 없다 · **기기 주인이 바뀌었다**(공용 PC 에서 다른 사람이 로그인) · `ttl`(기본 24시간)이 지났다 · 큐에서 기다리는 동안 그 종류를 푸시만 끄게 됐다 → 보내지 않는다.
- 판정: `Delivered` → `last_success_at` · `Gone`(404 · 410 · `UNREGISTERED`) → 그 줄만 지운다 · `Retry` → 큐 백오프 재시도(`max-attempts` 기본 5) · `Failed` → 줄은 남기고 죽은 작업(우리 설정 실수가 구독 대량 삭제로 번지지 않게 — 웹 푸시 401 · 403 은 지우지 않는다).

**채널 표지 · 전달 고리**(대량 공지 같은 쓰임을 위한 자리 — 기본은 지금과 같다): 이벤트의 `channels` 가 있고 `push` 가 없으면 채널은 건너뛴다(`channels = null` 이면 지금과 같다). 채널은 작업을 하나라도 넣었으면 `NotificationDeliveryHooks.onQueued(id, "push", 넣은 수)` 를 한 번 부른다. 처리기는 시작할 때 `halted(id)` 면 끝(DROPPED — 취소된 공지의 큐에 남은 푸시, 광고 야간 보류 포함)이고, 결과마다 `onOutcome`: `Delivered` → DELIVERED · `Gone` → FAILED · 건너뜀(멈춤 · 기기 없음 · 주인 바뀜 · 만료 · 푸시만 끔 · 광고 `Skip` · 전달 수단 없음) → DROPPED. 재시도를 다 쓰거나 영구 실패로 죽은 작업은 `notification-push-jdbc` 의 DEAD 고리가 FAILED 로 알린다. 광고 재보류(Hold)와 재시도 중간은 알리지 않는다. 푸시는 채널 이름표 `NotificationChannelDeclaration("push")` 빈도 올린다.

**푸시 클릭 열람**(`skeleton.notification.push.open-report.secret` 이 **있을 때만** — 기본 비어 있음 = 꺼짐: 페이로드에 `open` 도 보고 경로도 없다): 받는 사람이 푸시를 눌러 연 것을 서버가 안다.
- 페이로드(`v: 1`)에 선택 칸 `open` = `<기기 id>.<mac>` 이 는다. `mac = base64url(HMAC-SHA256(secret, 이벤트 id \0 기기 id \0 받는 사람 id))` 앞 16바이트. 받는 사람 id 는 토큰에 없다 — 서버가 기기 줄의 지금 주인으로 다시 계산해 맞추므로 위조 · 다른 이벤트 id · 주인이 바뀐 기기의 옛 토큰은 거절된다. 만료는 없다(이벤트 id 와 묶여 있어 재사용해도 같은 한 건이다). 비밀은 32자 이상이 아니면 시작이 실패한다. 토큰은 처리기가 보내는 순간 기기마다 만들어 얹는다(큐에 저장되지 않는다).
- 보고: `POST /api/v1/push/opened` `{ "id": "<이벤트 id>", "open": "<토큰>" }`, **로그인 없음**(서비스 워커에는 로그인 쿠키가 없다 — 토큰 서명이 인증이다. 이 경로 하나만 `PublicEndpointContributor` 로 공개). 맞으면 처음이든 중복이든 204, 서명이나 이벤트 id 가 틀리면 400 `PUSH.INVALID_OPEN_TOKEN`, 이미 지워진 기기는 조용히 204. 필드 길이를 제한한다(id 128자 · 토큰 300자). 별도 요청 한도는 없다(검증이 mac 한 번이고 센 값은 사람 × 이벤트당 1 로 막힌다).
- 센 방식: `PushOpenStore.recordFirst(이벤트, 받는 사람)` 이 처음이면 true — 그때만 `onOpened(id, "push")` 고리를 부른다. 알림함 `read_at` 은 건드리지 않는다(눌러 연 것과 알림함에서 읽은 것을 섞지 않는다). 저장소가 없는데 비밀을 주면 시작이 실패한다(`notification-push-jdbc` 를 얹는다).
- 웹 푸시(`notification-webpush`)만 `open` 을 싣는다. FCM 전달기는 아직 싣지 않는다 — 모바일 앱은 같은 필드를 데이터 메시지에 받아 같은 경로로 보내는 계약이다(구현은 앱 · 다음).

**종류 × 채널**: 종류마다 「푸시만 끄기」(`push_type_mutes`, 알림함에는 그대로 쌓인다)는 기존 「모든 채널 끄기」(`skeleton.notification.preferences`)와 **별개**다. 화면은 `GET /preferences` 의 `allChannelsMuted` 가 켜지면 푸시 스위치를 잠근다.

**잠금 화면**(`skeleton.notification.push.lock-screen`): `FULL`(기본, 제목 · 본문 그대로) / `HIDDEN`(실제 내용을 푸시 서비스에 아예 보내지 않고 `hidden-title` 「새 알림이 있습니다」 + 링크만, 묶기 열쇠는 해시). 설정은 **기본 factory 만** 따른다 — 앱이 `PushMessageFactory` 를 바꾸면 가리기도 그 앱의 책임이다.

**세션**: `auth` 가 있으면(컴파일 전용) 기기 줄에 로그인 세션 id 를 적고, 세션이 끊기면(로그아웃 · 「모든 기기 로그아웃」 · 토큰 재사용 탐지) 같이 지운다. 없으면 이 연동만 빠진다.

**HTTP**(`/api/v1/push`, 서블릿 + Spring Security 일 때, `skeleton.notification.push.http.enabled=false` 로 뺀다, 모두 인증): `GET /config` · `GET /devices` · `PUT /devices/{installationId}` · `DELETE /devices/{installationId}` · `POST /devices/{installationId}/test`(계정당 60초에 한 번) · `GET /preferences` · `PUT /preferences/{type}`. 예외 하나: 클릭 보고 `POST /opened` 는 로그인 없이 부르고(토큰이 인증) `open-report.secret` 이 있을 때만 등록된다. 모양: [push-http-contract](../push-http-contract.md).
계정 지우기와 저장은 `notification-push-jdbc` 가 한다. 광고 동의 상태 읽기(`GET /advertising`)는 `notification-push-legal` 이 더한다.

**광고성 푸시**(`skeleton.notification.advertising.types`, 기본 빈 목록 = 꺼짐 — 아래 규칙은 아무것도 하지 않는다): 광고 종류는 정보성 경로(`types`)로 가지 않고 **동의 게이트**(`AdvertisingGate` — 채널과 무관한 부품, 모듈 [notification](notification.md))를 거친다. 푸시 채널과 처리기는 게이트를 부르기만 하고, 설정은 푸시가 아니라 `skeleton.notification.advertising.*` 다. 법률 자문이 아니다 — 문서 내용 · 전송자 정보 · 법 해석은 앱의 몫.
- 받는 사람마다 `AdvertisingConsentPolicy.consentOf` 를 읽는다. **미동의 · 철회 · 2년 만료 · 새 판(OUTDATED) · 조회 실패는 안 보낸다**(작업이 안 생긴다 — 웹 푸시 · FCM 기기 모두). 정보성 종류는 영향이 없다.
- 야간(받는 사람 시간대 21:00 이상 ~ 08:00 미만)이고 **야간 동의가 없으면** 버리지 않고 그 시간대의 **다음 08시**로 보류한다(`runAt`, 서머타임은 `ZonedDateTime` 이 푼다). 처리기가 보류 작업을 집을 때 **다시 같은 판정**을 한다 — 대기 중 철회 · 만료면 `PushSender.send` 를 부르지 않고, 앞당겨 집혔는데 아직 야간이면 다시 보류한다. `ttl` 은 12시간 이상이어야 한다(보류가 최대 11시간).
- 제목 앞 `title-prefix`(기본 「(광고)」)는 채널이 `PushMessageFactory` **뒤에서** 붙인다 — 앱 factory 가 잊어도 빠지지 않고 두 번 붙지 않는다. 본문 끝에 `body-suffix`(예: 수신 거부 안내)를 붙일 수 있다. 광고 종류는 `lock-screen: HIDDEN` 이어도 내용을 보인다(기본 factory 기준).
- 시간대: 앱이 구현한 `AdvertisingRecipientZones`(예: 계정 프로필의 시간대) → `fallback-zone`. 시스템 기본 시간대는 쓰지 않는다.
- 시작 검사(광고 종류가 있을 때): 정책 빈(`notification-legal`) · `fallback-zone` · `sender-name` · `night-start`/`night-end` 형식 · `title-prefix` 비어 있지 않음 · `ttl` ≥ 12시간 · 두 동의 종류가 서로 다름 · 광고 종류가 알림 종류 `advertising.consent.*` 와 겹치지 않음 — 하나라도 어긋나면 시작이 실패한다.
- 지금 광고 게이트는 푸시 채널에만 걸린다 — 광고성 메일 · 슬랙 등을 보내는 앱은 보내기 전에 이 부품을 불러야 한다(정보통신망법 §50).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-push"))` |
| 함께 오는 모듈 | `notification`, `platform` |
| 컴파일 전용 | `auth` |
| 설정 접두사 | `skeleton.notification.push` — [docs/config/modules/notification-push.yml](../config/modules/notification-push.yml) |
| 기본 동작 | 꺼짐. `skeleton.notification.push.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | 켰다면 `PushDeviceStore` · `PushTypeMuteStore` · `PushTasks` 빈(`notification-push-jdbc` 가 낸다. 클릭 보고를 켜면 `PushOpenStore` 도) — 없으면 시작이 실패하고 무엇을 얹을지 말한다. 전달 수단(`PushSender`)은 없어도 뜬다(기기 등록이 400). |
| 교체 지점 | `PushSender`, `PushMessageFactory`, `PushDeviceStore`, `PushTypeMuteStore`, `PushTasks`, `PushSessionIdResolver`, `PushNotificationChannel`, `PushDeliveryWorker`, `PushDeviceService`, `PushController`, `PushOpenController`, `PushOpenStore`, `PushOpenReporter`, `PushOpenTokens`, `AdvertisingGate`, `AdvertisingConsentPolicy`, `AdvertisingRecipientZones` |
| 마이그레이션 | 없음 (표는 `notification-push-jdbc`) |
| 프론트 짝 | `@skeleton/push` (HTTP 계약: [push-http-contract.md](../push-http-contract.md)) — 기기 등록 · 설정 칸 · 서비스 워커 · 앱 안 띠 |
| 테스트 | `modules/notification-push/src/test`, `modules/notification-push/src/noOptionalTest` (`auth` 없이 뜨는지) |

자세히: [notification](notification.md) · [푸시 HTTP 계약](../push-http-contract.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
