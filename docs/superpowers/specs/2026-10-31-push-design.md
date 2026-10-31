# 푸시 알림(웹 푸시 + 모바일 FCM) — 설계 (초안)

> 2026-10-31. 요청(형 결정): 「웹 + 모바일(FCM)」 — 브라우저 웹 푸시(VAPID · 서비스 워커 · 구독 관리) + 모바일 발송(FCM — 안드로이드 · iOS 앱이 생길 때를 위해 기기 토큰 등록 API 까지, 실제 발송 시험은 Firebase 키가 있을 때만).
> 형 원칙: 공개 스켈레톤 · 캡슐화된 조립식 모듈(새 채널은 알림 채널 확장 지점에 끼우는 모듈) · 모듈 기본값 중립(꺼짐, 앱 yml 이 켬) · 업계 관행 · 회귀 시험은 돈 · 보안 · 동시성 · 데이터 손실만 · 개인 도메인 · 이메일 흔적 없음(`example.com`) · 배포 계약(`docs/deploy.md` §7 비밀 목록 · `DeployGuard`).
> 대상: kotlin-skeleton(새 `notification-push` · `notification-push-jdbc` · `notification-webpush` · `notification-fcm`, `apps/sample` · `apps/workbench`) + react-skeleton(새 `@skeleton/push`, `apps/sample`). 기준: kotlin main `a9342ec0`, react main `c552ab3`.
> 읽은 코드: `notification/{NotificationContracts,InMemoryNotificationBroker,NotificationEvent,NotificationMutes,AfterCommit,NotificationAutoConfiguration}.kt` · `notification-slack/SlackNotificationForwarder.kt`(채널이 브로커에 붙는 꼴) · `notification-jdbc/NotificationInboxErasure.kt` · `job-queue-jdbc/JobQueue.kt` · `reward-jdbc/RewardJobs.kt`(작업 어댑터 선례) · `auth/session/SessionEvents.kt` · `auth/principal/CurrentPrincipal.kt`(`sid`) · `platform` `DeployGuard` · `ExternalHttpClient` · `docs/deploy.md` §7 · 2026-10-28 알림센터 설계 · react `packages/notifications` · `apps/sample/src/routes/SettingsPage.tsx`.

## 0. 관행 조사 → 권장안

| 물음 | 업계 | 권장 |
|---|---|---|
| 웹 푸시 규격 | RFC 8030(전달) · 8291(본문 암호화 `aes128gcm`) · 8292(VAPID, ES256 JWT). 모든 브라우저가 이 하나로 된다(Chrome → `fcm.googleapis.com`, Firefox → Mozilla, Safari 16+ → `web.push.apple.com`, Edge → WNS) | 표준 VAPID 하나. 웹에 Firebase JS SDK 는 쓰지 않는다 |
| VAPID 키 | `web-push generate-vapid-keys` 로 한 번 만들고 고정. 바꾸면 기존 구독이 모두 못 쓰게 된다(구독이 공개 키에 묶인다). `sub`(mailto: · https:) 필수 — Apple 은 없으면 거절 | 공개 키 = 설정(비밀 아님), 개인 키 = **운영자 비밀**, `subject` 필수. 기동 때 두 키가 한 쌍인지 확인. 견본 로컬은 시험용 고정 쌍, 보호 환경에서 그 키면 `DeployGuard` 가 막는다 |
| 키 교체 | 클라이언트가 시작할 때 구독의 `applicationServerKey` 와 서버 공개 키를 비교해 다르면 다시 구독 | 기기 줄에 `vapid_key_id`(공개 키 해시 앞 16자). 다른 키의 줄에는 보내지 않고, 화면이 다음 방문에 다시 구독 |
| 죽은 구독 정리 | 웹 푸시 `404` · `410` → 지운다. FCM `UNREGISTERED`(404) · 토큰을 가리킨 `INVALID_ARGUMENT`(400) · `SENDER_ID_MISMATCH`(403) → 지운다. `429` · `5xx` → 백오프 재시도(`Retry-After`) | 그대로. **`401` · `403`(웹 푸시)은 지우지 않는다** — 우리 VAPID 설정이 틀린 것일 수 있어 지우면 전부 사라진다(영구 실패로 기록 → 죽은 작업 경보) |
| 토큰 갱신 · 오래된 기기 | Firebase: 토큰을 올릴 때마다 시각을 적고, 앱을 열 때마다 다시 올린다. 한 달쯤 안 보인 토큰은 오래된 것(기준은 서비스가), 안드로이드는 270일 비활성에 FCM 이 만료 | 앱 · 화면이 열릴 때마다 `PUT` 으로 `last_seen_at` 갱신. 오래된 기기 정리 작업은 **기본 꺼짐**(보관 정책 = 앱), 켜면 기본 60일 |
| 한 기기 = 한 줄 | Firebase 는 설치 id(FID)를 토큰과 함께 저장하길 권한다 — 토큰이 바뀌어도 같은 설치 | 클라이언트가 만든 무작위 `installationId` 가 기본 열쇠. 같은 주소(endpoint · token)를 다른 설치 · 계정이 등록하면 옛 줄을 지운다(주소는 한 줄에만) |
| 권한 요청 UX | Chrome 은 거절이 많은 사이트에 조용한 요청 UI, Firefox 72+ · Safari 는 사용자 동작 없이 묻지 못한다. 관행은 「먼저 앱 안에서 묻고(soft ask) 수락하면 브라우저 창」, 들어오자마자 묻지 않는다 | 의미 있는 동작 뒤 앱 안 띠 → 「알림 받기」 클릭 안에서만 `requestPermission`. 「나중에」는 14일 쉼. 거절(`denied`)이면 다시 묻지 않고 브라우저 설정 안내 |
| iOS 웹 | iOS · iPadOS 16.4+ 는 홈 화면에 추가한 웹 앱에서만 웹 푸시 | 견본에 `manifest.webmanifest`(`display: standalone`). iOS 브라우저 탭이면 「홈 화면에 추가하면 받을 수 있다」 안내만 |
| 채널 동의 | 기기 단위 켬/끔이 기본, 종류 × 채널 표(GitHub · Slack)는 그 위 | 1차 = **기기 단위 켬/끔**(브라우저 권한 + 앱 스위치) + 앱이 정한 「푸시로 보낼 종류」 + 기존 종류별 끄기(모든 채널). 종류 × 채널 스위치는 **결정 1** |
| 끝점 검증 | 서버가 남이 준 주소로 POST 한다 — 내부망 요청 위조(SSRF) 자리 | `https` + **알려진 푸시 서비스 호스트 허용 목록**(기본: `fcm.googleapis.com` · `updates.push.services.mozilla.com` · `*.push.apple.com` · `*.notify.windows.com`) · 443 · 리다이렉트 따라가지 않음 |
| 광고성 푸시 | 정보통신망법 §50: 광고성 정보는 사전 동의, 21~08시 별도 동의, 2년마다 수신 동의 확인, `(광고)` 표기 | **정보성 알림만**(결정 3). 모듈 문서에 경고 한 줄 |
| 조용한 시간 · 요약 묶음 | OS 집중 모드가 맡는 경우가 많다 | 범위 밖 |
| 로그아웃 · 세션 끊김 | Slack · Google: 로그아웃한 기기는 푸시도 끊긴다 | 기기 줄에 세션 id(`sid`)를 적고 세션 사건(`SessionEventListener`)으로 지운다 — 「모든 기기 로그아웃」 · 재사용 탐지면 그 계정 기기 전부(아직 로그인된 기기는 다음 열 때 다시 등록) |

라이브러리: 웹 푸시 암호화 · VAPID 서명은 `com.interaso:webpush` 1.3.0(MIT, 의존 없음 — kotlin-stdlib 만, JDK 암호화)의 `WebPush.getHeaders/getBody` 만 쓰고 HTTP 는 우리 `ExternalHttpClient` 로(상태 코드 판정을 우리가 한다). 맞지 않으면(aes128gcm 미지원 등) 모듈 안 JDK 구현(~150줄)으로 바꾼다 — 어느 쪽이든 RFC 8291 부록 A 시험 벡터로 증명. FCM 은 `google-auth-library-oauth2-http`(서비스 계정 → 접근 토큰, 갱신) + HTTP v1 `POST /v1/projects/{id}/messages:send`. Firebase Admin SDK 는 무겁다(Firestore · Storage 의존) — 쓰지 않는다.

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| 채널 | 알림(`NotificationPublisher.publish`)이 받는 사람의 기기로도 간다 — 웹 푸시 · FCM | 메일 채널, 받는 사람 없는 전체 공지의 푸시(주제 구독) |
| 기기 | 등록(=갱신) · 내 기기 목록 · 지우기 · 시험 발송 · 계정당 상한 · 세션 끊김 · 계정 지우기 | 기기 이름 바꾸기, 운영자 기기 조회 |
| 발송 | 작업 큐로 기기마다 한 작업 · 재시도 · 만료(`ttl`) · 죽은 구독 정리 · 묶기 열쇠 → `Topic` · `collapse_key` · `apns-collapse-id` | 대량 발송 일괄 API(FCM 은 일괄 발송이 폐지됨 — 기기당 한 요청이 정석) · 전달 통계 |
| 설정 | 기기 스위치 + 앱이 정한 푸시 종류(`push.types`) + 기존 종류별 끄기 | 종류 × 채널 스위치(결정 1 의 B) · 조용한 시간 · 광고성 동의(결정 3 의 B) |
| 모바일 | 토큰 등록 API · 계약 문서 · 안드로이드 · iOS 메시지 모양(FCM 이 APNs 로 넘긴다) · 실제 발송 시험은 키가 있을 때만 | 앱 자체(안드로이드 · iOS 코드), APNs 직접 연결, 프로젝트 모드에서 프로젝트마다 다른 Firebase 프로젝트 |
| 웹 | 서비스 워커(표시 · 클릭 이동) · 권한 요청 UX · 설정 칸 · 로그아웃 때 해지 · 시작 때 다시 맞추기 | 앱이 이미 가진 PWA 서비스 워커와 합치기 · 알림 단추(actions) · 이미지 |

**비목표**: 광고 · 마케팅 푸시(결정 3), 조용한 시간, 사용자 묶음 요약, 푸시 본문 번역(서버는 `title` · `message` 그대로 — 앱이 `PushMessageFactory` 로 바꾼다), 기존 `notification_mutes` 표 · 알림 계약 고치기, SSR 스타터.
**반목표**(깨지면 사고): 끈 사람 · 다른 계정의 기기로 간다(기기 주인이 바뀐 뒤 쌓인 작업 포함) / 우리 설정 실수(VAPID · 서비스 계정)로 구독이 대량 삭제된다 / 서버가 허용 목록 밖 주소 · 내부망으로 요청한다 / 개인 키 · 서비스 계정 · 끝점 열쇠가 로그 · 응답에 나온다 / 푸시 채널 오류 한 번에 채널이 브로커에서 영영 떨어진다(브로커는 던진 구독자를 닫는다) / 이 모듈을 안 붙인 앱의 동작이 바뀐다.

## 2. 모듈 경계 · 확장 지점

```
notification ◀─api── notification-push ◀─api── notification-push-jdbc ──impl──▶ job-queue-jdbc, persistence-jdbc, json
                         ▲     ··compileOnly··▶ auth (SessionEventListener · CurrentPrincipal.sessionId — 없으면 세션 연동만 빠진다)
                         ├─api── notification-webpush  (com.interaso:webpush)
                         └─api── notification-fcm      (google-auth-library-oauth2-http)
platform ◀── 넷 모두 (ExternalHttpClient · DeployGuard · AccountErasureListener · ProjectContext)
```

- **채널 확장 지점은 지금 그대로다**: `NotificationSubscriptionRegistry.subscribe` — 슬랙 전달기와 같은 꼴로 `PushNotificationChannel` 이 서버 전체 리스너로 붙는다. 그래서 끄기(브로커가 받는 사람에서 뺌) · 커밋 뒤 전달 · 프로젝트 문맥이 공짜로 따라온다. `notification` 은 한 줄도 고치지 않는다.
- **브로커는 던진 구독자를 닫는다**(`InMemoryNotificationBroker.deliver`). 푸시 채널은 모든 예외를 잡고 로그만 남긴다 — 회귀 시험 대상.
- `notification-push` 에 인메모리 저장소는 없다(재시작하면 구독이 사라져 쓸모없다). `push.enabled=true` 인데 `PushDeviceStore` 빈이 없으면 기동 실패(「`notification-push-jdbc` 를 얹어라」).
- 보내는 곳(`PushSender`)은 종류(`WEBPUSH` · `FCM`)마다 빈 하나. 없는 종류의 기기 등록은 400, 이미 있는 줄은 건너뛴다.

| 확장 지점 | 자리 | 고른 이유 |
|---|---|---|
| `PushSender`(`kind` · `send(device, message): PushResult` — `Delivered` · `Gone` · `Retry(after?)` · `Failed(reason)`) | `notification-push`, 구현 webpush · fcm | 새 전달 수단(APNs 직접 · 다른 회사)이 같은 자리에 끼운다. 판정 4개로 정리 · 재시도 규칙을 한 곳에 둔다 |
| `PushMessageFactory`(`NotificationEvent → PushMessage?`, null 이면 안 보냄) | `notification-push` | 잠금 화면 문구 · 번역 · 가리기는 앱 몫(결정 2). 기본 = `title ?: type`, `message`, `link`, 묶기 열쇠 |
| `PushDeviceStore` | `notification-push`, 구현 `-jdbc` | 다른 모듈과 같은 포트/어댑터 짝 |
| `PushTasks`(`enqueue(job)`) | `notification-push`, 구현 `-jdbc`(job-queue) | `payment` · `reward` 와 같다 — 자체 폴링 워커를 만들지 않는다 |
| `skeleton.notification.push.types` | 설정 | 푸시로도 갈 종류는 제품이 정한다(기본 빈 목록 = 아무것도 안 간다). 끌 수 있는 종류 목록과 같은 꼴 |

## 3. 데이터 (`notification-push-jdbc`, 두 방언 짝 V 파일 · `migrations.lock`, 시각 PG `timestamptz` · MySQL `datetime(6)` UTC)

`V<UTC>__push_devices.sql`:

| 칸 | 모양 | 뜻 |
|---|---|---|
| `id` | `varchar(40)` pk `pd_…` | |
| `account_id` | `varchar(128)` not null, 색인 | 주인. 등록하는 사람으로 바뀔 수 있다 |
| `installation_id` | `varchar(64)` not null **unique** | 클라이언트가 만든 무작위 값(웹: 저장소, 앱: 설치마다) |
| `session_id` | `varchar(64)` null, 색인 | 등록한 로그인 세션(`sid`) — 없으면 null |
| `kind` · `platform` | `varchar(16)` | `WEBPUSH`\|`FCM` · `web`\|`android`\|`ios` |
| `address` | `varchar(2048)` not null | 웹 푸시 endpoint 또는 FCM 토큰 |
| `address_hash` | `char(64)` not null **unique** | sha256(`kind` + `\n` + `address`) — 긴 주소의 유니크(MySQL 색인 길이) |
| `p256dh` · `auth_secret` | `varchar(128)` · `varchar(64)` null | 웹 푸시만 |
| `vapid_key_id` | `varchar(16)` null | 구독할 때의 서버 공개 키(sha256 앞 16자) — 다르면 보내지 않는다 |
| `label` | `varchar(100)` null | 「Chrome · macOS」 같은 보이는 이름(클라이언트가 준다, 잘라 저장) |
| `created_at` · `last_seen_at` · `last_success_at`(null) | 시각 | `last_seen_at` 색인(오래된 기기 정리) |

결정 1 이 B 면 `V<UTC>__push_type_mutes.sql`: `push_type_mutes(account_id, type varchar(128), created_at, pk(account_id, type))`.
암호화 없음 — endpoint 는 우리 VAPID 개인 키 없이는 쓸 수 없는 주소다(모듈 문서에 적는다). 프로젝트 모드면 프로젝트 스키마마다 생긴다.

## 4. 흐름

**등록(=갱신)** `PUT /api/v1/push/devices/{installationId}` — 화면 · 앱이 열릴 때마다 부른다.
1. 검사: 종류가 켜져 있나(`PushSender` 빈) · 웹 푸시면 endpoint `https` + 허용 호스트 + 443, `p256dh` 65바이트 · `auth` 16바이트(base64url) · FCM 이면 토큰 1~4096자 · `platform` 값. 실패 400 `PUSH.INVALID_DEVICE`(`detail.reason`), 꺼진 종류 400 `PUSH.KIND_DISABLED`.
2. 한 트랜잭션: 같은 `address_hash` 의 **다른** `installation_id` 줄을 지운다 → `installation_id` 로 넣기(`insertIgnore`) → 있으면 갱신(주인 · 세션 · 주소 · 열쇠 · 키 id · `last_seen_at`) → 그 계정 기기가 `max-devices-per-account`(기본 20)를 넘으면 `last_seen_at` 이 가장 오랜 것부터 지운다.
   주인이 바뀌는 등록(공용 PC 에서 다른 사람이 로그인)은 옛 주인의 줄을 그대로 넘겨받는다 — 옛 주인 앞으로 쌓인 작업은 발송 때 걸러진다(아래 3).

**발송** 앱 → `publish(event)` → 브로커(끈 사람 빼기 · 받은편지함 저장) → 커밋 뒤 `PushNotificationChannel.onNotification(event)`:
1. 건너뜀: `recipientIds` 가 비었다(전체 공지) · `event.type` 이 `push.types` 밖 · (결정 1 B) 푸시를 끈 사람 · `PushMessageFactory` 가 null.
2. 받는 사람들의 기기를 한 번에 읽고(`vapid_key_id` 가 지금 키와 다른 웹 줄 제외) 기기마다 작업 `notification.push` 하나: `{deviceId, recipientId, message{eventId, type, title, body, link, tag, urgency, createdAt}}`. 본문은 UTF-8 3000바이트에서 자른다(웹 푸시 레코드 4096 한도 · FCM 4KB). 모든 예외를 잡고 로그(채널이 떨어지지 않게). 커밋과 넣기 사이에 죽으면 그 푸시는 잃는다 — 푸시는 최선 노력(받은편지함이 원본).
3. 작업 처리기: 기기 줄을 읽는다 → 없으면 끝 · **`account_id ≠ recipientId` 면 끝**(주인이 바뀌었다) · `createdAt + ttl`(기본 24h) 지났으면 끝 → `PushSender.send` →
   `Delivered` → `last_success_at` · `Gone` → 그 줄 지움 · `Retry` → 던짐(큐 백오프, 최대 `max-attempts` 기본 5) · `Failed` → `PermanentJobFailureException`(줄은 남김, 죽은 작업 → `alert` 가 있으면 경보).
4. 판정 표:

| 전달 수단 | Delivered | Gone(지움) | Retry | Failed(남김) |
|---|---|---|---|---|
| 웹 푸시 | 201 | 404 · 410 | 429 · 5xx · 시간 초과 | 400 · 401 · 403 · 413 · 그 밖 |
| FCM | 200 | `UNREGISTERED` · `SENDER_ID_MISMATCH` · `INVALID_ARGUMENT` 중 `fieldViolations` 가 `message.token` 인 것 | `QUOTA_EXCEEDED` · `UNAVAILABLE` · `INTERNAL` · 시간 초과 | 그 밖(`THIRD_PARTY_AUTH_ERROR` — APNs 키 문제 등) |

5. 웹 푸시 머리: `TTL`(남은 초) · `Urgency`(`ERROR` · `WARNING` 이면 `high`, 아니면 `normal`) · `Topic`(묶기 열쇠의 sha256 → base64url 32자 — 안 받은 같은 열쇠 푸시를 대신한다). 본문 JSON `{v:1, id, type, title, body, link, tag}`.
   FCM: `notification{title, body}` + `data{eventId, type, link}` + `android{collapse_key, ttl, notification.tag}` + `apns{headers{apns-collapse-id(≤64바이트 해시), apns-expiration}}`.

**해지** 화면의 스위치 끔 · 로그아웃 직전 → `DELETE /api/v1/push/devices/{installationId}`(내 것만 지운다, 없거나 남의 것이어도 204). **세션 끊김**(`auth` 가 있을 때): `SESSION_REVOKED(sid)` → 그 세션의 기기, `SESSIONS_REVOKED` · `REFRESH_REUSE_DETECTED` → 그 계정 기기 전부. **계정 지우기**: `AccountErasureListener` 가 그 계정 줄을 모두 지운다(멱등). **오래된 기기 정리**(켰을 때): `interval` 마다 `last_seen_at < now − stale-after` 를 1000줄씩 지운다(알림 보관 정리와 같은 꼴, `skeletonTaskScheduler`, 프로젝트마다).

## 5. API (`/api/v1/push` — `push.enabled` + 서블릿 + Security 일 때, `skeleton.notification.push.http.enabled=false` 면 빠진다, 모두 인증 · 호출자 = `Authentication.name`)

| 경로 | 응답 · 하는 일 |
|---|---|
| `GET /config` | `{ value: { webPush: { publicKey } \| null, fcm: boolean, types: string[] } }` — 화면이 구독에 쓸 공개 키 |
| `GET /devices` | `{ value: { items: [{ installationId, kind, platform, label, createdAt, lastSeenAt }] } }` 최근 순 |
| `PUT /devices/{installationId}` | 본문 웹 `{ kind: "WEBPUSH", endpoint, keys: { p256dh, auth }, label? }` · 모바일 `{ kind: "FCM", token, platform: "android"\|"ios", label? }` → `{ value: device }` |
| `DELETE /devices/{installationId}` | 204 |
| `POST /devices/{installationId}/test` | 내 기기에 시험 알림 하나(종류 목록 무관) · 202. 계정당 60초에 한 번 `429 PUSH.TEST_TOO_SOON` |
| (결정 1 B) `GET /preferences` · `PUT /preferences/{type}` `{ enabled }` | 푸시 종류별 스위치. 목록 밖 404 |

`installationId` 는 `[A-Za-z0-9_-]{16,64}`. 모바일 계약(토큰 갱신 때 `PUT` · 로그아웃 때 `DELETE` · 데이터 칸 `link` 로 앱 안 이동)은 새 `docs/push-http-contract.md` 한 장.

## 6. 화면 (react 새 패키지 `@skeleton/push` 1.0.0)

- `createPushApi(client, { basePath = '/push' })` — §5 그대로. `getInstallationId(storage)` — 무작위 값을 `localStorage` 에 한 번 만든다.
- `pushSupport()` → `supported` \| `needs-install`(iOS 이고 standalone 아님) \| `unsupported`(서비스 워커 · `PushManager` · `Notification` 중 없음).
- `usePush(api, { accountId })` → `{ support, permission, enabled, enable(), disable(), sendTest() }`. **`enable` 은 클릭 처리기 안에서만** `Notification.requestPermission()` → `pushManager.subscribe({ userVisibleOnly: true, applicationServerKey })` → `PUT`. 켬 표시는 계정별로 저장(다른 사람이 같은 브라우저에서 로그인해도 자동으로 켜지지 않는다).
- `usePushSync(api, { accountId })` — 로그인 뒤 · 창이 다시 보일 때: 켬 표시가 있고 권한이 `granted` 면 구독이 없거나 공개 키가 다르면 다시 구독 → `PUT`(=`last_seen_at` 갱신). 권한이 `denied` 로 바뀌었으면 `DELETE` 하고 표시를 지운다.
- `unregisterPush(api)` — 앱의 로그아웃이 토큰을 지우기 **전에** 부른다.
- `<PushPrompt api labels? snoozeDays=14 />` — 앱 안 띠(soft ask). `support === 'supported'` · `permission === 'default'` · 쉬는 중 아님일 때만 보인다. 「알림 받기」(→ `enable`) · 「나중에」(쉼). 띠를 언제 띄울지는 앱이 정한다(부품은 `open` prop).
- `<PushSettings api labels? />` — 설정 칸: 「이 기기에서 푸시 받기」 `SwitchRow` + 상태 문구(거절됨 → 브라우저 설정에서 허용하는 법 · `needs-install` → 홈 화면에 추가 안내 · `unsupported`) + 「시험 알림 보내기」 + 내 다른 기기 표(이름 · 마지막으로 본 때 · 지우기). (결정 1 B 면 종류별 푸시 스위치 목록.)
- 서비스 워커: `@skeleton/push/vite` 의 `pushServiceWorker({ fileName: 'push-sw.js', icon? })` Vite 플러그인이 빌드 뿌리에 의존 없는 작은 워커를 낸다(개발 서버도 같은 주소로 내준다, `base` 를 따른다). 워커: `push` → JSON `v:1` 읽어 **항상** `showNotification(title, { body, tag, icon, data: { link } })`(Chrome 은 `userVisibleOnly` 라 안 보이면 기본 문구를 띄운다) · `notificationclick` → `link` 가 `/` 로 시작하고 `//` · `\` 가 없을 때만, 같은 출처의 열린 창을 앞으로 + 이동, 없으면 새 창 · `pushsubscriptionchange` 는 아무것도 안 한다(인증이 없어 서버에 못 알림 — 다음 `usePushSync` 가 고친다).
- 스토리(부품마다 `play`): `PushPrompt`(보임 · 쉼) · `PushSettings`(켬 · 꺼짐 · 거절됨 · 설치 필요 · 미지원 · 기기 여럿). 문구 ko/en `labels`, 눌리는 것은 눌리게(`Button`), 날 요소 없음.

## 7. 견본 앱 조립

**kotlin `apps/sample`**: 의존 넷(`notification-push` · `-jdbc` · `notification-webpush` · `notification-fcm`). yml(이 앱의 선택): `push.enabled: true` · `push.types: [NOTE_UPDATED, comment.created, comment.replied, reward.granted, badge.awarded]` · `push.stale-cleanup.enabled: true`(60d) · `webpush.enabled: true` · `subject: mailto:ops@example.com`(환경변수로 덮는다) · `fcm.enabled: ${…_FCM_ENABLED:false}`. `application-local.yml` 에 시험용 VAPID 고정 쌍. `email.extra-exclude-paths` 에 `PUT|DELETE /api/v1/push/devices/*` · `POST …/test`(자기 기기 설정). `apps/workbench` 는 의존 한 줄씩만(모두 꺼짐 — 공존 증명).
**react `apps/sample`**: `vite.config.ts` 에 `pushServiceWorker()` · `public/manifest.webmanifest` · 설정 페이지에 `PushSettings`(알림 끄기 칸 아래) · 노트를 처음 저장한 뒤 `PushPrompt` · 로그아웃이 `unregisterPush` 먼저 · 로그인 뒤 `usePushSync`.
**배포**: `docs/deploy.md` §7 에 두 줄 — webpush: `<P>_NOTIFICATION_WEBPUSH_VAPID_PRIVATE_KEY`(**운영자**, `secrets:` + `.local.env`) · `_VAPID_PUBLIC_KEY` · `_SUBJECT`(env:), 켰는데 비었거나 · 쌍이 안 맞거나 · 시험 키면 보호 환경 기동 실패(`DeployGuard` `notification-webpush`), **바꾸면 모든 웹 구독이 다음 방문까지 끊긴다 — 서버를 옮길 때 들고 간다**. fcm: `<P>_NOTIFICATION_FCM_CREDENTIALS`(**운영자**, 서비스 계정 JSON 의 base64), 켰는데 비었거나 못 읽으면 보호 환경 기동 실패. 「따라가며 더할 것」 표 한 줄, `docs/real-provider-setup.md` 에 VAPID 키 만들기(`npx web-push generate-vapid-keys`) · Firebase 프로젝트 · 서비스 계정(역할 「Firebase Cloud Messaging API Admin」만) · APNs 키 올리기.

## 8. 검증 (깨지면 사고 나는 것만)

**kotlin**
- 보안: 주인이 바뀐 기기로 옛 주인 작업이 가지 않는다 · 같은 주소를 다른 계정이 등록하면 옛 줄이 없어진다 · 허용 목록 밖 · `http` · 사설 IP · 다른 포트 endpoint 는 400 이고 요청이 나가지 않는다 · 리다이렉트를 따라가지 않는다 · 남의 `installationId` 지우기는 아무것도 안 지운다 · 로그 · 응답 · `toString` 에 개인 키 · 서비스 계정 · `auth_secret` 없음 · VAPID 쌍 불일치 · 시험 키 · 빈 자격 · 허용 목록의 `http://` 항목(e2e 가짜 푸시 서비스용) → 보호 환경 기동 실패.
- 데이터 손실: 웹 푸시 401 · 403 · 413, FCM 의 토큰 아닌 `INVALID_ARGUMENT` 는 줄을 지우지 않는다 · 404 · 410 · `UNREGISTERED` 는 그 줄만 지운다.
- 암호화: RFC 8291 부록 A 벡터, 그리고 가짜 푸시 서비스(시험 안 HTTP 서버)가 시험용 브라우저 키로 본문을 풀어 내용 · `TTL` · `Topic` · VAPID JWT(`aud` · `exp` · `sub`)를 확인한다.
- 개인정보: 끈 종류 · `push.types` 밖 · 전체 공지는 작업이 안 생긴다 · 세션 끊김 · 계정 지우기 뒤 기기 없음.
- 동시성: 같은 `installationId` 동시 `PUT` 둘 → 한 줄 · 계정 상한 경계(21번째 등록 → 20줄).
- 채널 안정: 작업 넣기가 던져도 다음 알림이 다시 푸시된다(채널이 안 떨어진다) · 만료된 작업은 보내지 않는다.
- FCM 실제 발송: 환경변수 `FCM_TEST_CREDENTIALS` · `FCM_TEST_TOKEN` 이 있을 때만 도는 시험 하나(`@EnabledIfEnvironmentVariable`) — 평소 건너뜀.
- 조립: `auth` 없이(`src/noOptionalTest` — 세션 연동만 빠짐) · `-jdbc` 없이 켜면 기동 실패 메시지 · 이 모듈 없는 `apps/api` 그대로. 두 방언 `dbTest` · `MigrationLockTest` · `CapabilitiesCatalogTest` · `ModuleDocumentationTest`.

**react**: api 경로 기록 시험 · `enable` 이 클릭 밖에서 권한을 묻지 않는다 · 키가 바뀌면 다시 구독 · `denied` 면 `DELETE` · 로그아웃 순서 · 워커의 `link` 거르기(`//x` · `/\x` · `https:` 거절) 단위 시험 · 스토리 `play` · `capabilities` 검사.
**e2e 한 여정**(react sample + kotlin sample, Chromium): 권한 허용(`grantPermissions`) · 가짜 `PushManager`(시작 스크립트 — 끝점은 e2e 가 띄운 가짜 푸시 서비스, 허용 목록은 e2e 환경변수로만) → 노트 저장 → 띠 → 「알림 받기」 → 설정에 기기 1 → 다른 계정이 노트에 댓글 → 가짜 푸시 서비스가 암호화된 요청 하나를 받는다 → CDP `ServiceWorker.deliverPushMessage` 로 같은 JSON → `registration.getNotifications()` 제목 확인 → 로그아웃 → 다시 댓글 → 요청 없음. 실제 브라우저 푸시(진짜 Chrome)는 qa 가 수동으로 한 번(캡처 2장까지).

## 9. 내가 정한 것 (관행대로 — 바꾸려면 말해 줘)

1. 새 모듈 넷: 핵심(`notification-push`) · 저장(`-jdbc`) · 전달 수단 둘. 채널은 지금 브로커 구독으로 붙고 `notification` 은 안 고친다.
2. 발송은 `job-queue-jdbc` 위 기기당 작업 하나 — 재시도가 다른 기기에 중복을 만들지 않게. 커밋 직후 넣기 전 죽으면 잃는다(최선 노력, 받은편지함이 원본).
3. 전체 공지(`recipientIds` 빈 알림)는 푸시하지 않는다.
4. 웹 푸시 401 · 403 은 지우지 않는다(우리 설정 실수가 전체 삭제로 번지지 않게).
5. 끝점 허용 목록 + https + 리다이렉트 금지(SSRF).
6. 한 설치 = 한 줄(`installationId`), 주소는 한 줄에만, 계정당 20대(안전 장치라 모듈 기본값, `max-devices-per-account` 로 덮는다).
7. 만료 24h · 시도 5번 · 본문 3000바이트에서 자름 — 모듈 기본값.
8. 오래된 기기 정리는 기본 꺼짐(켜면 60일) — 보관 정책은 앱.
9. 세션에 묶는다: 로그아웃한 세션 · 「모든 기기 로그아웃」 · 토큰 재사용 탐지면 기기도 끊긴다(`auth` 는 compileOnly).
10. 서비스 워커는 늘 알림을 띄운다(창이 앞에 있어도) — Chrome 규칙. 앞 창 중복 억제는 다음.
11. 권한 요청은 앱 안 띠를 거쳐 클릭 안에서만, 「나중에」 14일, 거절이면 다시 안 묻는다. 견본은 첫 노트 저장 뒤에 띄운다.
12. 라이브러리: `com.interaso:webpush`(암호화 · 서명만) · `google-auth-library-oauth2-http`. Firebase Admin SDK · Firebase JS SDK 안 씀.
13. 프로젝트 모드: VAPID 키 하나로 모든 프로젝트(키는 출처에 묶이지 않는다), FCM 자격은 하나(프로젝트마다 다른 앱은 다음 — 프로젝트 모드에서 FCM 을 켜면 경고 한 줄).

## 10. 작업 계획 (계획 `docs/superpowers/plans/2026-10-31-push.md`)

| 키 | 레포 | 조각 | 선행 | 크기 | 예상 |
|---|---|---|---|---|---|
| K1 | kotlin | `notification-push` + `-jdbc`: 계약(`PushSender` · `PushMessageFactory` · `PushDeviceStore` · `PushTasks`) · 채널 · 작업 처리기(가짜 보내기로 판정 시험) · HTTP §5 · 세션 연동 · 계정 지우기 · 오래된 기기 정리 · V 파일 두 방언 · 모듈 문서 2 · 설정 문서 · 카탈로그 · `docs/push-http-contract.md` | — | 대 | 5h |
| K2 | kotlin | `notification-webpush`: VAPID · 암호화(라이브러리, 벡터 시험) · 허용 목록 · 판정 표 · 머리 · 키 쌍 확인 · `DeployGuard` · 가짜 푸시 서비스 시험 · 문서 | K1 계약(SPI 만 먼저 합의) | 중 | 3h |
| K3 | kotlin | `notification-fcm`: 서비스 계정 → 토큰 · HTTP v1 · 판정 표 · 메시지 모양 · `DeployGuard` · 키 있을 때만 실제 발송 시험 · 문서 · `real-provider-setup.md` | K1 계약 | 중 | 2.5h |
| K4 | kotlin | 견본 · workbench 조립 · yml · 시험 키 · `deploy.md` §7 · 따라가기 표 · CHANGELOG · 견본 통합 시험(노트 댓글 → 작업 → 가짜 보내기) | K1 · K2 · K3 | 중 | 2h |
| R1 | react | `@skeleton/push`: api · 지원 판정 · `usePush` · `usePushSync` · `unregisterPush` · `PushPrompt` · `PushSettings` · Vite 플러그인 + 워커 · 스토리 · README · 카탈로그 · CHANGELOG | §5 표(가짜 API) | 대 | 4h |
| R2 | react | 견본 조립(플러그인 · manifest · 설정 · 띠 · 로그아웃 · 동기화) · e2e 한 여정(가짜 푸시 서비스 · CDP) | R1 · K4 합친 뒤 | 중 | 3h |

- 묶음: ① K1 ∥ R1 → ② K2 ∥ K3 → ③ K4 → R2 → 끝 검사(지휘: kotlin 바뀐 모듈만 한 번 · react 전체 + `e2e:sample` 한 번, 리뷰 kotlin 1 · react 1, qa 1 — 화면당 캡처 2장까지). 동시 최대 2~3.
- developer 6회 + 리뷰 2 + qa 1 = 에이전트 9. 벽시계 대략 11h(K1 → K2 → K4 → R2 가 긴 줄). 결정 1 이 B 면 K1 +1h · R1 +0.5h.
- 같이 만지는 파일: `settings.gradle.kts` · `capabilities.json` · `docs/modules/README.md` 는 K1 이 넷 모두의 자리를 한 번에 만들고(K2 · K3 은 자기 항목 내용만), 견본 yml · `deploy.md` 는 K4 만.
- 계약이 바뀌면 kotlin 조각이 보고에 적고 지휘가 react 조각에 넘긴다. 스펙과 계획이 어긋나면 스펙이 이긴다.

## 정해야 할 것

1. **푸시를 종류마다 따로 끌 수 있게 하나** — 표 · API · 설정 화면 모양이 갈린다.
   - **A. 기기 단위 켬/끔 + 앱이 정한 푸시 종류 + 기존 종류별 끄기(모든 채널에 적용) (추천)** — 요청 문장(구독 관리)에 맞는 최소. 「알림함엔 두고 푸시만 끄기」는 안 된다.
   - B. A + 종류별 푸시 스위치(GitHub · Slack 의 종류 × 채널) — 표 하나 · 경로 둘 · 설정 칸 하나 더, 약 1.5h.
2. **잠금 화면에 무엇을 보이나(모듈 기본)** — 개인정보 노출 수준.
   - **A. 알림 제목 · 본문 그대로, 가리고 싶은 앱은 `PushMessageFactory` 를 바꾼다 (추천)** — 대부분 서비스의 기본이고 푸시를 누를 이유가 보인다.
   - B. 기본은 「새 알림이 있습니다」만, 앱이 켜야 내용이 보인다 — 메신저 · 금융형 기본. 견본은 내용을 켠다.
3. **광고 · 마케팅 푸시를 1차에 다루나** — 법(정보통신망법 §50) 의무가 붙는다.
   - **A. 다루지 않는다 — 정보성 알림만, 모듈 문서에 「광고는 별도 동의 없이 보내지 마라」 경고 (추천)** — 스켈레톤 알림은 지금 모두 정보성이다.
   - B. 넣는다 — `legal` 동의 기록으로 광고 수신 동의 · 야간(21~08시) 별도 동의 · 2년 재확인 · `(광고)` 표기 · 야간 보류. 대 조각 하나(약 4h) 추가.

## 결정됨 (2026-10-31)

형이 골랐다. 이 절이 본문보다 우선한다.

1. 종류별 끄기 — 형: "국룰로 갑시다". 업계 관행(GitHub · Slack · 인스타그램 식)대로 **B: 종류 × 채널 스위치**를 넣는다. 기기 단위 켬/끔 + 종류별로 「푸시만 끄기」(알림함에는 남는다). 기존 종류별 끄기(모든 채널)는 그대로 둔다.
2. 잠금 화면 내용 — 형: "옵션에 따라". 모듈 설정 하나로 고른다(예: `skeleton.notification-push.lock-screen: FULL | HIDDEN`). 모듈 기본값은 관행대로 FULL(제목 · 본문), HIDDEN 이면 「새 알림이 있습니다」만. 그 위에 `PushMessageFactory` 교체도 그대로 열어 둔다. 견본 앱은 yml 에 FULL 을 명시한다.
3. 광고 · 마케팅 푸시 — 형: "아냐 다루자 광고도". **1차에 넣는다**(정해야 할 것 3 의 B): legal 동의 기록으로 광고 수신 동의 · 야간(21~08시) 별도 동의 · 2년 재확인 · 「(광고)」 표기 · 동의 없는 야간 발송 보류. 정보성과 광고성은 알림 종류 단위로 구분하고, 광고성 종류는 동의가 없으면 어느 채널로도 푸시하지 않는다. 대 조각 하나(약 4시간)가 더 붙는다 — 작업 계획에 K5(서버) · R3(동의 화면)로 더한다.
