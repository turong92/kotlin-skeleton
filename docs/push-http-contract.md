# 푸시 HTTP 계약 (`/api/v1/push`)

`notification-push` 가 열고(`skeleton.notification.push.enabled=true` + 서블릿 + Spring Security, `skeleton.notification.push.http.enabled=false` 로 뺀다), 웹 화면(`@skeleton/push`)과 모바일 앱이 같이 쓴다.
모든 경로는 **인증 필요**(호출자 = `Authentication.name`, `auth` 에서는 계정 id) — 없으면 401. 응답은 표준 envelope(`{ "value": … }`), 에러는 표준 `ApiError`(`code` · `detail` · `data`).

## 경로

| 경로 | 응답 · 하는 일 |
|---|---|
| `GET /config` | `{ value: { webPush: { publicKey } \| null, fcm: boolean, types: string[], advertisingTypes: string[] } }` — `webPush` 는 웹 푸시 전달 수단이 있고 공개 키가 있을 때, `fcm` 은 FCM 전달 수단이 있을 때. `types` 는 푸시로 갈 종류(광고 종류 제외), `advertisingTypes` 는 광고성 종류 |
| `GET /devices` | `{ value: { items: [{ installationId, kind, platform, label, createdAt, lastSeenAt }] } }` 최근 순. **주소(endpoint · 토큰)와 구독 열쇠는 돌려주지 않는다** |
| `PUT /devices/{installationId}` | 등록 = 갱신. 본문 웹 `{ kind: "WEBPUSH", endpoint, keys: { p256dh, auth }, label? }` · 모바일 `{ kind: "FCM", token, platform: "android"\|"ios", label? }` → `{ value: device }` |
| `DELETE /devices/{installationId}` | 204. **내 것만** 지운다 — 없거나 남의 것이어도 204(아무것도 안 지운다) |
| `POST /devices/{installationId}/test` | 내 기기에 시험 알림 하나(종류 목록 · 끄기와 무관) · 202. 내 기기가 아니면 404 `PUSH.DEVICE_NOT_FOUND`. 계정당 60초에 한 번 — 아니면 429 `PUSH.TEST_TOO_SOON` |
| `GET /preferences` | `{ value: { items: [{ type, push: boolean, allChannelsMuted: boolean }] } }` — `types` 의 종류마다(설정 순서, 광고 종류 제외). `push` = 이 종류의 푸시가 켜져 있다. `allChannelsMuted` = 「모든 채널 끄기」(`/api/v1/notifications/preferences`, 읽기만)가 켜져 있다 — 켜져 있으면 화면은 푸시 스위치를 잠근다 |
| `PUT /preferences/{type}` | 본문 `{ enabled: boolean }` → `{ value: item }`. **푸시만** 끈다/켠다(알림함에는 그대로 쌓인다). 멱등. `types` 밖(광고 종류 포함)은 404 `PUSH.UNKNOWN_TYPE` |

`installationId` 는 클라이언트가 만든 무작위 값, `[A-Za-z0-9_-]{16,64}`(웹: 저장소에 한 번, 앱: 설치마다). 같은 설치는 한 줄이다. 같은 주소(endpoint · 토큰)를 다른 설치 · 계정이 등록하면 옛 줄이 지워진다(주소는 한 줄에만). 계정당 기기 상한(기본 20)을 넘으면 `last_seen_at` 이 가장 오랜 줄부터 지워진다.
광고 수신 동의 · 철회 · 재확인은 이 경로가 아니라 `legal` 의 기존 경로로 한다(아래 「광고 동의」). `GET /advertising` 은 `notification-push-legal` 이 있고 광고 종류가 설정됐을 때만 더해진다.

## 광고 동의 (`notification-push-legal`, 광고 종류가 설정된 앱만)

`GET /api/v1/push/advertising` — 설정 화면이 읽는 동의 상태. 광고 종류가 설정되지 않았거나 모듈이 없으면 경로가 없다(404).
```json
{ "value": {
  "advertisingTypes": ["promo.notes-tips"],
  "senderName": "Example Co.",
  "consent": { "type": "marketing", "state": "AGREED", "agreedAt": "2026-10-02T03:04:05Z", "expiresAt": "2028-10-01T03:04:05Z", "reconfirmDue": false, "effective": true },
  "night":   { "type": "marketing-night", "state": "NONE", "agreedAt": null, "expiresAt": null, "reconfirmDue": false, "effective": false },
  "nightWindow": { "start": "21:00", "end": "08:00", "zone": "Asia/Seoul" } } }
```
- `state`: `AGREED`(동의했고 만료 전) · `EXPIRED`(동의했지만 `expiresAt` 이 지남) · `OUTDATED`(새 판이 시행됐다 - 다시 동의) · `WITHDRAWN` · `NONE`(한 적 없음 또는 문서가 없음). `agreedAt` 은 `WITHDRAWN` · `NONE` 이면 null, `expiresAt` 은 `AGREED` · `EXPIRED` 만 값이 있다.
- `effective` = 실제로 보낼 수 있다. `consent.effective` = `state == AGREED`, `night.effective` = 야간 동의가 `AGREED` **이고** `consent.effective`(광고 동의가 없으면 야간 동의가 남아 있어도 보내지 않는다 - 화면은 야간 체크를 잠근다).
- `reconfirmDue` = `AGREED` 이고 만료까지 30일(`reconfirm-notice-before`) 이내. 만료되면 `EXPIRED`.
- `nightWindow.zone` = 이 사용자에게 적용되는 시간대(앱의 `AdvertisingRecipientZones` → `fallback-zone`).

동의 · 철회 · 재확인은 **`legal` 의 기존 경로**다([legal-http-contract](legal-http-contract.md)) - 증거(IP · UA · 판 해시)가 한 곳에 쌓인다. 동의하기 전에 `GET /api/v1/legal/documents` 로 현재 판을 읽어 그 `version` 을 싣는다.

| 하는 일 | 호출 |
|---|---|
| 광고 수신 동의 | `POST /api/v1/legal/consents` `{ "consents": [ { "type": "marketing", "version": "<현재 판>", "locale": "ko" } ], "source": "push-settings" }` |
| 야간 수신 동의 | 같은 경로, `type: "marketing-night"` (별도 문서 · 별도 체크 - 광고 동의와 한 호출에 같이 실어도 되지만 화면이 따로 체크받는다) |
| **재확인** | 같은 경로 · 같은 본문에 `"source": "reconfirm"` - 현재 판에 이미 동의한 상태여도 사건이 더해져 2년이 새로 시작된다. 만료 뒤에도 같은 단추로 다시 동의할 수 있다 |
| 철회 | `POST /api/v1/legal/consents/marketing/withdraw` (본문 없음) · 야간만 `…/marketing-night/withdraw` |

동의 · 철회 · 재확인 직후 정보성 알림 `advertising.consent.changed`(전송자 · 처리 일시 · 무엇을 · 철회 방법)가 그 사람에게 간다. 만료 30일 전에는 `advertising.consent.reconfirm` 이 동의 주기당 한 번 간다(만료 뒤에도 한 번). 둘 다 알림함에 쌓이고, 단말에 가려면 앱이 `push.types` 에 넣는다. 이 알림은 광고가 아니라 정보성이다.
광고를 못 받는다고 서비스 이용을 막지 않는다(선택 종류 - 재동의 필터 대상이 아니다).

서버가 하는 약속(광고): 미동의 · 철회 · 만료 · 새 판 · 동의 조회 실패면 광고를 보내지 않는다. 야간(받는 사람 시간대 21~08시)에 야간 동의가 없으면 다음 08시로 미루고, 기다리는 동안 철회하면 보내지 않는다. 제목 앞에 「(광고)」가 붙는다.

## 에러

| 상태 · `code` | 언제 | `data` |
|---|---|---|
| 400 `PUSH.INVALID_DEVICE` | 등록 본문이 틀리다 | `{ reason }` — 짧은 기계용 이유: `installation_id` · `kind` · `endpoint` · `keys` · `token` · `platform` · `address`, 그리고 전달 수단이 정한 이유(예: 끝점 허용 목록 밖). 요청 값(주소 · 열쇠)은 싣지 않는다 |
| 400 `PUSH.KIND_DISABLED` | 그 종류의 전달 수단이 켜져 있지 않다 | — |
| 404 `PUSH.UNKNOWN_TYPE` | `PUT /preferences/{type}` 가 푸시 종류가 아닌 type | — |
| 404 `PUSH.DEVICE_NOT_FOUND` | 시험 발송 대상이 내 기기가 아니다 | — |
| 429 `PUSH.TEST_TOO_SOON` | 시험 발송을 60초 안에 또 했다 | — |

## 모바일 앱이 지킬 것

1. 앱이 열릴 때마다, 그리고 FCM 토큰이 갱신될 때마다 `PUT /devices/{installationId}` — `last_seen_at` 이 갱신되고 토큰이 바뀌어도 같은 설치로 이어진다.
2. 로그아웃하기 **전에** `DELETE /devices/{installationId}`(로그아웃 뒤엔 인증이 없다). 서버도 세션이 끊기면(`auth` 가 있을 때) 그 세션이 등록한 기기를 지운다.
3. 푸시의 데이터 칸 `link` 는 앱 안 경로다 — 탭하면 그 화면으로 이동한다. 잠금 화면 설정이 `HIDDEN` 이면 제목은 중립 문구이고 실제 내용은 앱을 열어 알림함에서 본다.
4. 같은 `eventId` 의 푸시가 두 번 올 수 있다(재시도) — 앱에서 중복을 걸러도 된다.

## 서버가 하는 약속

- 푸시는 **최선 노력**이다. 원본은 받은편지함(`/api/v1/notifications`)이다.
- 끈 종류(모든 채널 끔 · 푸시만 끔)는 보내지 않는다 — 큐에서 기다리는 동안 끈 경우도 보내기 직전에 다시 확인한다.
- 기기 주인이 바뀐 뒤에는 옛 주인 앞으로 쌓인 푸시가 가지 않는다.
- 만료(`ttl`, 기본 24시간)가 지난 푸시는 보내지 않는다.
