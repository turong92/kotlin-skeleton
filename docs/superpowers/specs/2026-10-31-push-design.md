# 푸시 알림(웹 푸시 + 모바일 FCM) — 설계

> 2026-10-31. 요청(형 결정): 「웹 + 모바일(FCM)」 — 브라우저 웹 푸시(VAPID · 서비스 워커 · 구독 관리) + 모바일 발송(FCM — 안드로이드 · iOS 앱이 생길 때를 위해 기기 토큰 등록 API 까지, 실제 발송 시험은 Firebase 키가 있을 때만).
> 형 원칙: 공개 스켈레톤 · 캡슐화된 조립식 모듈(새 채널은 알림 채널 확장 지점에 끼우는 모듈) · 모듈 기본값 중립(꺼짐, 앱 yml 이 켬) · 업계 관행 · 회귀 시험은 돈 · 보안 · 동시성 · 데이터 손실만 · 개인 도메인 · 이메일 흔적 없음(`example.com`) · 배포 계약(`docs/deploy.md` §7 비밀 목록 · `DeployGuard`).
> 결정 반영(2026-10-31): 종류 × 채널 스위치 · 잠금 화면 옵션 · 광고 푸시 1차 포함. 맨 끝 「결정됨」 절은 결정의 기록이고, 본문이 그 결정을 반영한 현재 설계다(어긋나면 결정 절이 이긴다).
> 대상: kotlin-skeleton(새 `notification-push` · `notification-push-jdbc` · `notification-webpush` · `notification-fcm` · `notification-push-legal`, 기존 `legal` · `legal-jdbc` 에 작은 기계장치 둘, `apps/sample` · `apps/workbench`) + react-skeleton(새 `@skeleton/push`, `apps/sample`). 기준: kotlin main `a9342ec0`, react main `c552ab3`.
> 읽은 코드: `legal/{ConsentModel,ConsentService,ConsentHooks}.kt` · `docs/legal.md` · `docs/legal-http-contract.md` · `time/UserTimePreferences.kt` · `notification/{NotificationContracts,InMemoryNotificationBroker,NotificationEvent,NotificationMutes,AfterCommit,NotificationAutoConfiguration}.kt` · `notification-slack/SlackNotificationForwarder.kt`(채널이 브로커에 붙는 꼴) · `notification-jdbc/NotificationInboxErasure.kt` · `job-queue-jdbc/JobQueue.kt` · `reward-jdbc/RewardJobs.kt`(작업 어댑터 선례) · `auth/session/SessionEvents.kt` · `auth/principal/CurrentPrincipal.kt`(`sid`) · `platform` `DeployGuard` · `ExternalHttpClient` · `docs/deploy.md` §7 · 2026-10-28 알림센터 설계 · react `packages/notifications` · `apps/sample/src/routes/SettingsPage.tsx`.

## 0. 관행 조사 → 권장안

| 물음 | 업계 | 권장 |
|---|---|---|
| 웹 푸시 규격 | RFC 8030(전달) · 8291(본문 암호화 `aes128gcm`) · 8292(VAPID, ES256 JWT). 모든 브라우저가 이 하나로 된다(Chrome → `fcm.googleapis.com`, Firefox → Mozilla, Safari 16+ → `web.push.apple.com`, Edge → WNS) | 표준 VAPID 하나. 웹에 Firebase JS SDK 는 쓰지 않는다 |
| VAPID 키 | `web-push generate-vapid-keys` 로 한 번 만들고 고정. 바꾸면 기존 구독이 모두 못 쓰게 된다(구독이 공개 키에 묶인다). `sub`(mailto: · https:) 필수 — Apple 은 없으면 거절 | 공개 키 = 설정(비밀 아님), 개인 키 = **운영자 비밀**, `subject` 필수. 기동 때 두 키가 한 쌍인지 확인. 견본 로컬은 시험용 고정 쌍, 보호 환경에서 그 키면 `DeployGuard` 가 막는다 |
| 키 교체 | 클라이언트가 시작할 때 구독의 `applicationServerKey` 와 서버 공개 키를 비교해 다르면 다시 구독 | 기기 줄에 `vapid_key_id`(공개 키 해시 앞 16자). 다른 키의 줄에는 보내지 않고, 화면이 다음 방문에 다시 구독 |
| 죽은 구독 정리 | 웹 푸시 `404` · `410` → 지운다. FCM `UNREGISTERED`(404) · 토큰을 가리킨 `INVALID_ARGUMENT`(400) → 지운다(`SENDER_ID_MISMATCH`(403)는 지우지 않는다 — 다른 프로젝트의 서비스 계정을 잘못 넣으면 모든 토큰이 받는다). `429` · `5xx` → 백오프 재시도(`Retry-After`) | 그대로. **`401` · `403`(웹 푸시)은 지우지 않는다** — 우리 VAPID 설정이 틀린 것일 수 있어 지우면 전부 사라진다(영구 실패로 기록 → 죽은 작업 경보) |
| 토큰 갱신 · 오래된 기기 | Firebase: 토큰을 올릴 때마다 시각을 적고, 앱을 열 때마다 다시 올린다. 한 달쯤 안 보인 토큰은 오래된 것(기준은 서비스가), 안드로이드는 270일 비활성에 FCM 이 만료 | 앱 · 화면이 열릴 때마다 `PUT` 으로 `last_seen_at` 갱신. 오래된 기기 정리 작업은 **기본 꺼짐**(보관 정책 = 앱), 켜면 기본 60일 |
| 한 기기 = 한 줄 | Firebase 는 설치 id(FID)를 토큰과 함께 저장하길 권한다 — 토큰이 바뀌어도 같은 설치 | 클라이언트가 만든 무작위 `installationId` 가 기본 열쇠. 같은 주소(endpoint · token)를 다른 설치 · 계정이 등록하면 옛 줄을 지운다(주소는 한 줄에만) |
| 권한 요청 UX | Chrome 은 거절이 많은 사이트에 조용한 요청 UI, Firefox 72+ · Safari 는 사용자 동작 없이 묻지 못한다. 관행은 「먼저 앱 안에서 묻고(soft ask) 수락하면 브라우저 창」, 들어오자마자 묻지 않는다 | 의미 있는 동작 뒤 앱 안 띠 → 「알림 받기」 클릭 안에서만 `requestPermission`. 「나중에」는 14일 쉼. 거절(`denied`)이면 다시 묻지 않고 브라우저 설정 안내 |
| iOS 웹 | iOS · iPadOS 16.4+ 는 홈 화면에 추가한 웹 앱에서만 웹 푸시 | 견본에 `manifest.webmanifest`(`display: standalone`). iOS 브라우저 탭이면 「홈 화면에 추가하면 받을 수 있다」 안내만 |
| 채널 동의 | 기기 단위 켬/끔이 기본, 종류 × 채널 표(GitHub · Slack · 인스타그램)는 그 위 | **결정 1(B)**: 기기 단위 켬/끔(브라우저 권한 + 앱 스위치) + 앱이 정한 「푸시로 보낼 종류」 + **종류 × 채널**(종류마다 「푸시만 끄기」 — 알림함에는 남는다, `push_type_mutes`) + 기존 종류별 끄기(모든 채널, 그대로) |
| 끝점 검증 | 서버가 남이 준 주소로 POST 한다 — 내부망 요청 위조(SSRF) 자리 | `https` + **알려진 푸시 서비스 호스트 허용 목록**(기본: `fcm.googleapis.com` · `updates.push.services.mozilla.com` · `*.push.apple.com` · `*.notify.windows.com`) · 443 · 리다이렉트 따라가지 않음 |
| 광고성 푸시 | 정보통신망법 §50: 광고성 정보는 사전 동의, 21~08시 별도 동의, 2년마다 수신 동의 확인, 제목 `(광고)` 표기, 동의 · 철회 처리 결과 통지, 전송자 명칭 · 수신 거부 방법 표시 | **결정 3 — 1차에 넣는다**(§11 · K5 · R3). 동의 증거는 `legal` 이, 발송 규칙은 `notification-push` 가 맡는다. 법률 자문이 아니다(모듈 문서에 한 줄) |
| 잠금 화면 | 대부분 서비스 기본은 제목 · 본문 그대로, 메신저 · 금융은 가림 옵션을 둔다(iOS 「미리보기」 · Android 「민감한 알림」은 OS 가 따로 가린다) | **결정 2**: `lock-screen: FULL\|HIDDEN` 모듈 설정. 모듈 기본 FULL. HIDDEN 이면 실제 제목 · 본문이 푸시 서비스에도 가지 않는다(서버가 중립 문구로 바꿔 보낸다) |
| 조용한 시간 · 요약 묶음 | OS 집중 모드가 맡는 경우가 많다 | 범위 밖 |
| 로그아웃 · 세션 끊김 | Slack · Google: 로그아웃한 기기는 푸시도 끊긴다 | 기기 줄에 세션 id(`sid`)를 적고 세션 사건(`SessionEventListener`)으로 지운다 — 「모든 기기 로그아웃」 · 재사용 탐지면 그 계정 기기 전부(아직 로그인된 기기는 다음 열 때 다시 등록) |

라이브러리: 웹 푸시 암호화 · VAPID 서명은 `com.interaso:webpush` 1.3.0(MIT, 의존 없음 — kotlin-stdlib 만, JDK 암호화)의 `WebPush.getHeaders/getBody` 만 쓰고 HTTP 는 우리 `ExternalHttpClient` 로(상태 코드 판정을 우리가 한다). 맞지 않으면(aes128gcm 미지원 등) 모듈 안 JDK 구현(~150줄)으로 바꾼다 — 어느 쪽이든 RFC 8291 부록 A 시험 벡터로 증명. FCM 은 `google-auth-library-oauth2-http`(서비스 계정 → 접근 토큰, 갱신) + HTTP v1 `POST /v1/projects/{id}/messages:send`. Firebase Admin SDK 는 무겁다(Firestore · Storage 의존) — 쓰지 않는다.

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| 채널 | 알림(`NotificationPublisher.publish`)이 받는 사람의 기기로도 간다 — 웹 푸시 · FCM | 메일 채널, 받는 사람 없는 전체 공지의 푸시(주제 구독) |
| 기기 | 등록(=갱신) · 내 기기 목록 · 지우기 · 시험 발송 · 계정당 상한 · 세션 끊김 · 계정 지우기 | 기기 이름 바꾸기, 운영자 기기 조회 |
| 발송 | 작업 큐로 기기마다 한 작업 · 재시도 · 만료(`ttl`) · 죽은 구독 정리 · 묶기 열쇠 → `Topic` · `collapse_key` · `apns-collapse-id` | 대량 발송 일괄 API(FCM 은 일괄 발송이 폐지됨 — 기기당 한 요청이 정석) · 전달 통계 |
| 설정 | 기기 스위치 + 앱이 정한 푸시 종류(`push.types`) + **종류 × 채널 스위치**(결정 1 B) + 기존 종류별 끄기 + **잠금 화면 옵션**(결정 2) + **광고 푸시**: 수신 동의 · 야간 별도 동의 · 2년 재확인 · `(광고)` · 야간 보류(결정 3) | 조용한 시간 · 광고의 메일 · 슬랙 채널 게이트(§11.9) |
| 모바일 | 토큰 등록 API · 계약 문서 · 안드로이드 · iOS 메시지 모양(FCM 이 APNs 로 넘긴다) · 실제 발송 시험은 키가 있을 때만 | 앱 자체(안드로이드 · iOS 코드), APNs 직접 연결, 프로젝트 모드에서 프로젝트마다 다른 Firebase 프로젝트 |
| 웹 | 서비스 워커(표시 · 클릭 이동) · 권한 요청 UX · 설정 칸 · 로그아웃 때 해지 · 시작 때 다시 맞추기 | 앱이 이미 가진 PWA 서비스 워커와 합치기 · 알림 단추(actions) · 이미지 |

**비목표**: 조용한 시간(광고의 법정 야간 보류는 별개로 §11 에 있다), 사용자 묶음 요약, 푸시 본문 번역(서버는 `title` · `message` 그대로 — 앱이 `PushMessageFactory` 로 바꾼다), 기존 `notification_mutes` 표 · 알림 계약 고치기, SSR 스타터.
**반목표**(깨지면 사고): 끈 사람(모든 채널 끔 · 푸시만 끔) · 다른 계정의 기기로 간다(기기 주인이 바뀐 뒤 쌓인 작업 포함) / 광고성 종류가 수신 동의 없이(철회 · 만료 · 대기 중 철회 포함) 또는 야간 동의 없이 21~08시에 나간다 / HIDDEN 인데(기본 구현에서) 실제 제목 · 본문이 `PushSender` 에 닿는다 / 우리 설정 실수(VAPID · 서비스 계정)로 구독이 대량 삭제된다 / 서버가 허용 목록 밖 주소 · 내부망으로 요청한다 / 개인 키 · 서비스 계정 · 끝점 열쇠가 로그 · 응답에 나온다 / 푸시 채널 오류 한 번에 채널이 브로커에서 영영 떨어진다(브로커는 던진 구독자를 닫는다) / 이 모듈을 안 붙인 앱의 동작이 바뀐다.

## 2. 모듈 경계 · 확장 지점

```
notification ◀─api── notification-push ◀─api── notification-push-jdbc ──impl──▶ job-queue-jdbc, persistence-jdbc, json
                         ▲     ··compileOnly··▶ auth (SessionEventListener · CurrentPrincipal.sessionId — 없으면 세션 연동만 빠진다)
                         ├─api── notification-webpush  (com.interaso:webpush)
                         ├─api── notification-fcm      (google-auth-library-oauth2-http)
                         └─api── notification-push-legal (광고 동의 어댑터 — legal 을 api 로 안다, 광고를 쓰는 앱만)
legal ◀─api── notification-push-legal            (legal-jdbc 는 앱이 이미 얹는다)
platform ◀── 다섯 모두 (ExternalHttpClient · DeployGuard · AccountErasureListener · ProjectContext)
```

- **채널 확장 지점은 지금 그대로다**: `NotificationSubscriptionRegistry.subscribe` — 슬랙 전달기와 같은 꼴로 `PushNotificationChannel` 이 서버 전체 리스너로 붙는다(광고 규칙도 이 안에서 — §11). 그래서 끄기(브로커가 받는 사람에서 뺌) · 커밋 뒤 전달 · 프로젝트 문맥이 공짜로 따라온다. `notification` 은 한 줄도 고치지 않는다.
- **브로커는 던진 구독자를 닫는다**(`InMemoryNotificationBroker.deliver`). 푸시 채널은 모든 예외를 잡고 로그만 남긴다 — 회귀 시험 대상.
- `notification-push` 에 인메모리 저장소는 없다(재시작하면 구독이 사라져 쓸모없다). `push.enabled=true` 인데 `PushDeviceStore` 빈이 없으면 기동 실패(「`notification-push-jdbc` 를 얹어라」).
- 보내는 곳(`PushSender`)은 종류(`WEBPUSH` · `FCM`)마다 빈 하나. 없는 종류의 기기 등록은 400, 이미 있는 줄은 건너뛴다.

| 확장 지점 | 자리 | 고른 이유 |
|---|---|---|
| `PushSender`(`kind` · `send(device, message): PushResult` — `Delivered` · `Gone` · `Retry(after?)` · `Failed(reason)`) | `notification-push`, 구현 webpush · fcm | 새 전달 수단(APNs 직접 · 다른 회사)이 같은 자리에 끼운다. 판정 4개로 정리 · 재시도 규칙을 한 곳에 둔다 |
| `PushMessageFactory`(`NotificationEvent → PushMessage?`, null 이면 안 보냄) | `notification-push` | 번역 · 문구는 앱 몫. 기본 구현은 `lock-screen` 설정을 따른다(결정 2 — FULL: `title ?: type`, `message`, `link`, 묶기 열쇠 / HIDDEN: 중립 문구 + `link` + 해시한 묶기 열쇠). 설정은 기본 구현만 따른다 — 앱이 빈을 바꾸면 가리기도 그 앱의 책임이다(교체는 열려 있다) |
| `PushDeviceStore` | `notification-push`, 구현 `-jdbc` | 다른 모듈과 같은 포트/어댑터 짝 |
| `PushTasks`(`enqueue(job)`) | `notification-push`, 구현 `-jdbc`(job-queue) | `payment` · `reward` 와 같다 — 자체 폴링 워커를 만들지 않는다 |
| `skeleton.notification.push.types` | 설정 | 푸시로도 갈 종류는 제품이 정한다(기본 빈 목록 = 아무것도 안 간다). 끌 수 있는 종류 목록과 같은 꼴 |
| `PushTypeMuteStore`(`mutedTypes(accountId)` · `mutedAmong(type, accountIds)` · `setMuted`) | `notification-push`, 구현 `-jdbc`(`push_type_mutes`) | 결정 1 B — 「푸시만 끄기」. 모든 채널 끄기(`NotificationMuteRepository`)와 별개의 표 |
| `skeleton.notification.push.lock-screen` = `FULL`\|`HIDDEN` (+ `hidden-title` · `hidden-body`) | 설정 | 결정 2. 모듈 기본 `FULL`. `HIDDEN` 문구 기본 제목 「새 알림이 있습니다」 · 본문 없음, 설정으로 덮는다. 접두사는 레포 관례(`skeleton.notification.push.*`) |
| `skeleton.notification.push.advertising-types` + `advertising.*` | 설정 | 결정 3. 어느 종류가 광고성인지는 제품이 정한다(기본 빈 목록 = 광고 규칙 꺼짐). 나머지 키는 §11 |
| `AdvertisingConsentPolicy`(`consentOf(accountId, now): AdvertisingConsent`) · `PushRecipientZones`(`zoneOf(accountId): ZoneId?`) | `notification-push`, 구현 `notification-push-legal`(동의) · 앱(시간대) | 결정 3. 광고 종류가 하나라도 설정돼 있는데 정책 빈이 없으면 기동 실패(「`notification-push-legal` 을 얹어라」) |

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

`V<UTC>__push_type_mutes.sql`(결정 1 B): `push_type_mutes(account_id varchar(128), type varchar(128), created_at, pk(account_id, type))` — 「푸시만 끈」 종류만 줄이 있다(끈 것만 기억하는 `notification_mutes` 와 같은 꼴). 두 방언 짝 · `migrations.lock`.
`V<UTC>__push_advertising_notices.sql`(결정 3): `push_advertising_notices(account_id varchar(128), type varchar(128), agreed_at timestamp, notified_at, pk(account_id, type, agreed_at))` — 「재확인 안내를 이 동의 주기에 이미 보냈다」 표시(§11). 계정 지우기가 두 표 모두 지운다.
암호화 없음 — endpoint 는 우리 VAPID 개인 키 없이는 쓸 수 없는 주소다(모듈 문서에 적는다). 프로젝트 모드면 프로젝트 스키마마다 생긴다.

## 4. 흐름

**등록(=갱신)** `PUT /api/v1/push/devices/{installationId}` — 화면 · 앱이 열릴 때마다 부른다.
1. 검사: 종류가 켜져 있나(`PushSender` 빈) · 웹 푸시면 endpoint `https` + 허용 호스트 + 443, `p256dh` 65바이트 · `auth` 16바이트(base64url) · FCM 이면 토큰 1~4096자 · `platform` 값. 실패 400 `PUSH.INVALID_DEVICE`(`detail.reason`), 꺼진 종류 400 `PUSH.KIND_DISABLED`.
2. 한 트랜잭션: 같은 `address_hash` 의 **다른** `installation_id` 줄을 지운다 → `installation_id` 로 넣기(`insertIgnore`) → 있으면 갱신(주인 · 세션 · 주소 · 열쇠 · 키 id · `last_seen_at`) → 그 계정 기기가 `max-devices-per-account`(기본 20)를 넘으면 `last_seen_at` 이 가장 오랜 것부터 지운다.
   주인이 바뀌는 등록(공용 PC 에서 다른 사람이 로그인)은 옛 주인의 줄을 그대로 넘겨받는다 — 옛 주인 앞으로 쌓인 작업은 발송 때 걸러진다(아래 3).

**발송** 앱 → `publish(event)` → 브로커(끈 사람 빼기 · 받은편지함 저장) → 커밋 뒤 `PushNotificationChannel.onNotification(event)`:
1. 건너뜀: `recipientIds` 가 비었다(전체 공지) · `event.type` 이 `push.types` 와 `advertising-types` 둘 다 밖 · **푸시를 끈 사람**(결정 1 B — `PushTypeMuteStore.mutedAmong`; 모든 채널을 끈 사람은 브로커가 이미 뺐다) · **광고 종류면 §11 의 게이트**(동의 없음 → 그 사람 건너뜀, 야간 동의 없는 야간 → 보류) · `PushMessageFactory` 가 null.
2. 받는 사람들의 기기를 한 번에 읽고(`vapid_key_id` 가 지금 키와 다른 웹 줄 제외) 기기마다 작업 `notification.push` 하나: `{deviceId, recipientId, message{eventId, type, title, body, link, tag, urgency, createdAt}, advertising: bool}`(보류면 작업의 `runAt` = 다음 08시). 본문은 UTF-8 3000바이트에서 자른다(웹 푸시 레코드 4096 한도 · FCM 4KB). 모든 예외를 잡고 로그(채널이 떨어지지 않게). 커밋과 넣기 사이에 죽으면 그 푸시는 잃는다 — 푸시는 최선 노력(받은편지함이 원본).
3. 작업 처리기: 기기 줄을 읽는다 → 없으면 끝 · **`account_id ≠ recipientId` 면 끝**(주인이 바뀌었다) · `createdAt + ttl`(기본 24h) 지났으면 끝 · **그 종류를 푸시만 끈 사람이면 끝**(큐에서 기다리는 동안 끈 경우) · **광고면 지금 동의·야간을 다시 확인**(§11 — 대기 중 철회는 안 보낸다) → `PushSender.send` →
   `Delivered` → `last_success_at` · `Gone` → 그 줄 지움 · `Retry` → 던짐(큐 백오프, 최대 `max-attempts` 기본 5) · `Failed` → `PermanentJobFailureException`(줄은 남김, 죽은 작업 → `alert` 가 있으면 경보).
4. 판정 표:

| 전달 수단 | Delivered | Gone(지움) | Retry | Failed(남김) |
|---|---|---|---|---|
| 웹 푸시 | 201 | 404 · 410 | 429 · 5xx · 시간 초과 | 400 · 401 · 403 · 413 · 그 밖 |
| FCM | 200 | `UNREGISTERED` · `INVALID_ARGUMENT` 중 `fieldViolations` 가 `message.token` 인 것 | `QUOTA_EXCEEDED` · `UNAVAILABLE` · `INTERNAL` · 시간 초과 | 그 밖(`SENDER_ID_MISMATCH` — 다른 프로젝트 서비스 계정 설정 실수일 수 있어 지우지 않는다 · `THIRD_PARTY_AUTH_ERROR` — APNs 키 문제 등) |

5. 웹 푸시 머리: `TTL`(남은 초) · `Urgency`(`ERROR` · `WARNING` 이면 `high`, 아니면 `normal`) · `Topic`(묶기 열쇠의 sha256 → base64url 32자 — 안 받은 같은 열쇠 푸시를 대신한다). 본문 JSON `{v:1, id, type, title, body, link, tag}`.
   FCM: `notification{title, body}` + `data{eventId, type, link}` + `android{collapse_key, ttl, notification.tag}` + `apns{headers{apns-collapse-id(≤64바이트 해시), apns-expiration}}`.

**메시지 모양(결정 2)**: 기본 `PushMessageFactory` — `FULL`: 제목 `title ?: type` · 본문 `message` · `link` · 묶기 열쇠. `HIDDEN`: 제목 = `hidden-title`(기본 「새 알림이 있습니다」) · 본문 = `hidden-body`(기본 없음) · `link` 는 그대로(탭하면 알림 위치로) · 묶기 열쇠는 해시. 실제 `title` · `message` 는 작업 본문에도 푸시 서비스에도 가지 않는다(앱을 열면 알림함이 보여 준다). **광고 종류는 이 설정을 따르지 않고 늘 내용을 보인다**(법정 `(광고)` 표기가 제목에 보여야 하고, 가릴 개인정보가 아니다).

**해지** 화면의 스위치 끔 · 로그아웃 직전 → `DELETE /api/v1/push/devices/{installationId}`(내 것만 지운다, 없거나 남의 것이어도 204). **세션 끊김**(`auth` 가 있을 때): `SESSION_REVOKED(sid)` → 그 세션의 기기, `SESSIONS_REVOKED` · `REFRESH_REUSE_DETECTED` → 그 계정 기기 전부. **계정 지우기**: `AccountErasureListener` 가 그 계정 줄(기기 · `push_type_mutes` · 재확인 안내 표시)을 모두 지운다(멱등). 동의 기록은 `legal` 이 익명화한다. **오래된 기기 정리**(켰을 때): `interval` 마다 `last_seen_at < now − stale-after` 를 1000줄씩 지운다(알림 보관 정리와 같은 꼴, `skeletonTaskScheduler`, 프로젝트마다).

## 5. API (`/api/v1/push` — `push.enabled` + 서블릿 + Security 일 때, `skeleton.notification.push.http.enabled=false` 면 빠진다, 모두 인증 · 호출자 = `Authentication.name`)

| 경로 | 응답 · 하는 일 |
|---|---|
| `GET /config` | `{ value: { webPush: { publicKey } \| null, fcm: boolean, types: string[], advertisingTypes: string[] } }` — 화면이 구독에 쓸 공개 키 |
| `GET /devices` | `{ value: { items: [{ installationId, kind, platform, label, createdAt, lastSeenAt }] } }` 최근 순 |
| `PUT /devices/{installationId}` | 본문 웹 `{ kind: "WEBPUSH", endpoint, keys: { p256dh, auth }, label? }` · 모바일 `{ kind: "FCM", token, platform: "android"\|"ios", label? }` → `{ value: device }` |
| `DELETE /devices/{installationId}` | 204 |
| `POST /devices/{installationId}/test` | 내 기기에 시험 알림 하나(종류 목록 무관) · 202. 계정당 60초에 한 번 `429 PUSH.TEST_TOO_SOON` |
| `GET /preferences` | `{ value: { items: [{ type, push: boolean, allChannelsMuted: boolean }] } }` — `push.types` 의 종류마다(광고 종류 제외 — 광고는 동의로 정해진다, §11). `push` = 이 종류의 푸시가 켜져 있다(`push_type_mutes` 에 줄 없음). `allChannelsMuted` = 기존 「모든 채널 끄기」(읽기만 — 켜고 끄는 곳은 `/api/v1/notifications/preferences`), 켜져 있으면 화면은 푸시 스위치를 잠근다 |
| `PUT /preferences/{type}` | 본문 `{ enabled: boolean }` → `{ value: item }`. 푸시만 끈다/켠다(알림함에는 그대로 쌓인다). `push.types` 밖(광고 종류 포함) 404 `PUSH.UNKNOWN_TYPE`. 멱등 |
| `GET /advertising` | (`notification-push-legal` 가 있고 광고 종류가 설정됐을 때만) 광고 동의 상태 — 모양은 §11 |

`installationId` 는 `[A-Za-z0-9_-]{16,64}`. 광고 동의 · 철회는 이 경로가 아니라 `legal` 의 기존 경로(§11)로 한다 — 증거가 한 곳에 쌓이게. 모바일 계약(토큰 갱신 때 `PUT` · 로그아웃 때 `DELETE` · 데이터 칸 `link` 로 앱 안 이동)은 새 `docs/push-http-contract.md` 한 장.

## 6. 화면 (react 새 패키지 `@skeleton/push` 1.0.0)

- `createPushApi(client, { basePath = '/push' })` — §5 그대로(`getPreferences` · `setPreference(type, enabled)` 포함). `getInstallationId(storage)` — 무작위 값을 `localStorage` 에 한 번 만든다.
- `pushSupport()` → `supported` \| `needs-install`(iOS 이고 standalone 아님) \| `unsupported`(서비스 워커 · `PushManager` · `Notification` 중 없음).
- `usePush(api, { accountId })` → `{ support, permission, enabled, enable(), disable(), sendTest() }`. **`enable` 은 클릭 처리기 안에서만** `Notification.requestPermission()` → `pushManager.subscribe({ userVisibleOnly: true, applicationServerKey })` → `PUT`. 켬 표시는 계정별로 저장(다른 사람이 같은 브라우저에서 로그인해도 자동으로 켜지지 않는다).
- `usePushSync(api, { accountId })` — 로그인 뒤 · 창이 다시 보일 때: 켬 표시가 있고 권한이 `granted` 면 구독이 없거나 공개 키가 다르면 다시 구독 → `PUT`(=`last_seen_at` 갱신). 권한이 `denied` 로 바뀌었으면 `DELETE` 하고 표시를 지운다.
- `unregisterPush(api)` — 앱의 로그아웃이 토큰을 지우기 **전에** 부른다.
- `<PushPrompt api labels? snoozeDays=14 />` — 앱 안 띠(soft ask). `support === 'supported'` · `permission === 'default'` · 쉬는 중 아님일 때만 보인다. 「알림 받기」(→ `enable`) · 「나중에」(쉼). 띠를 언제 띄울지는 앱이 정한다(부품은 `open` prop).
- `<PushSettings api labels? />` — 설정 칸: 「이 기기에서 푸시 받기」 `SwitchRow` + 상태 문구(거절됨 → 브라우저 설정에서 허용하는 법 · `needs-install` → 홈 화면에 추가 안내 · `unsupported`) + 「시험 알림 보내기」 + 내 다른 기기 표(이름 · 마지막으로 본 때 · 지우기). **종류별 푸시 스위치 목록**(결정 1 B — `GET /preferences` 의 종류마다 `SwitchRow`, `allChannelsMuted` 면 잠그고 「알림이 전체 꺼져 있어요」, 광고 종류는 이 목록에 없다).
- 서비스 워커: `@skeleton/push/vite` 의 `pushServiceWorker({ fileName: 'push-sw.js', icon? })` Vite 플러그인이 빌드 뿌리에 의존 없는 작은 워커를 낸다(개발 서버도 같은 주소로 내준다, `base` 를 따른다). 워커: `push` → JSON `v:1` 읽어 **항상** `showNotification(title, { body, tag, icon, data: { link } })`(Chrome 은 `userVisibleOnly` 라 안 보이면 기본 문구를 띄운다) · `notificationclick` → `link` 가 `/` 로 시작하고 `//` · `\` 가 없을 때만, 같은 출처의 열린 창을 앞으로 + 이동, 없으면 새 창 · `pushsubscriptionchange` 는 아무것도 안 한다(인증이 없어 서버에 못 알림 — 다음 `usePushSync` 가 고친다).
- 광고 동의 화면 `<AdvertisingSettings>` 는 R3 — §11.7. `PushSettings` 아래에 놓는다.
- 스토리(부품마다 `play`): `PushPrompt`(보임 · 쉼) · `PushSettings`(켬 · 꺼짐 · 거절됨 · 설치 필요 · 미지원 · 기기 여럿 · 종류 스위치 켬/끔 · 전체 꺼짐으로 잠긴 종류). 문구 ko/en `labels`, 눌리는 것은 눌리게(`Button`), 날 요소 없음.

## 7. 견본 앱 조립

**kotlin `apps/sample`**: 의존 다섯(`notification-push` · `-jdbc` · `notification-webpush` · `notification-fcm` · `notification-push-legal`). yml(이 앱의 선택): `push.enabled: true` · **`push.lock-screen: FULL`(모듈 기본과 같아도 일부러 명시 — 견본은 내용을 보이는 쪽을 고른 것이라는 표시, 결정 2)** · `push.types: [NOTE_UPDATED, comment.created, comment.replied, reward.granted, badge.awarded, advertising.consent.changed, advertising.consent.reconfirm]` · 광고: `push.advertising-types: [promo.notes-tips]` · `advertising.sender-name`(견본 이름) · `advertising.fallback-zone: Asia/Seoul` · `legal/manifest.json` 에 `marketing` · `marketing-night` 견본 문서(DRAFT · 견본임을 적음, ko/en). 계정 시간대는 `PushRecipientZones` 빈이 `account` 프로필의 `timeZone` 으로 답한다(`ProfileService` 로 읽을 수 있는지 K4 가 먼저 확인, 안 되면 `fallback-zone` 만 쓰고 보고) — 견본에 광고를 보내는 화면은 없다(보내는 쪽은 제품마다 다르다), 통합 시험이 `publish` 로 증명한다 · `push.stale-cleanup.enabled: true`(60d) · `webpush.enabled: true` · `subject: mailto:ops@example.com`(환경변수로 덮는다) · `fcm.enabled: ${…_FCM_ENABLED:false}`. `application-local.yml` 에 시험용 VAPID 고정 쌍. `email.extra-exclude-paths` 에 `PUT|DELETE /api/v1/push/devices/*` · `POST …/test`(자기 기기 설정). `apps/workbench` 는 의존 한 줄씩만(모두 꺼짐 — 공존 증명).
**react `apps/sample`**: 설정 페이지에 `AdvertisingSettings`(`PushSettings` 아래, R3 뒤에 R2 가 조립) · `vite.config.ts` 에 `pushServiceWorker()` · `public/manifest.webmanifest` · 설정 페이지에 `PushSettings`(알림 끄기 칸 아래) · 노트를 처음 저장한 뒤 `PushPrompt` · 로그아웃이 `unregisterPush` 먼저 · 로그인 뒤 `usePushSync`.
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
- 결정 1: 푸시만 끈 종류는 작업이 안 생기고 알림함에는 남는다 · 큐에서 기다리는 동안 끈 작업은 처리기가 보내지 않는다 · 다른 계정의 줄은 건드리지 않는다 · 모든 채널 끔과 서로 독립. 결정 2: HIDDEN 이면 `PushSender` 에 닿는 메시지 · 작업 본문 어디에도 원래 제목 · 본문이 없다(기본 factory) · 광고 종류는 HIDDEN 이어도 내용 + `(광고)`.
- 광고(K5): §11.8 의 회귀 목록.
- 조립: `auth` 없이(`src/noOptionalTest` — 세션 연동만 빠짐) · `-jdbc` 없이 켜면 기동 실패 메시지 · 이 모듈 없는 `apps/api` 그대로. 두 방언 `dbTest` · `MigrationLockTest` · `CapabilitiesCatalogTest` · `ModuleDocumentationTest`.

**react**: api 경로 기록 시험 · 종류 스위치가 `PUT /preferences/{type}` 를 부르고 `allChannelsMuted` 면 잠긴다 · (R3 는 §11.7) · `enable` 이 클릭 밖에서 권한을 묻지 않는다 · 키가 바뀌면 다시 구독 · `denied` 면 `DELETE` · 로그아웃 순서 · 워커의 `link` 거르기(`//x` · `/\x` · `https:` 거절) 단위 시험 · 스토리 `play` · `capabilities` 검사.
**e2e 한 여정**(react sample + kotlin sample, Chromium): 권한 허용(`grantPermissions`) · 가짜 `PushManager`(시작 스크립트 — 끝점은 e2e 가 띄운 가짜 푸시 서비스, 허용 목록은 e2e 환경변수로만) → 노트 저장 → 띠 → 「알림 받기」 → 설정에 기기 1 → 다른 계정이 노트에 댓글 → 가짜 푸시 서비스가 암호화된 요청 하나를 받는다 → CDP `ServiceWorker.deliverPushMessage` 로 같은 JSON → `registration.getNotifications()` 제목 확인 → 로그아웃 → 다시 댓글 → 요청 없음. 실제 브라우저 푸시(진짜 Chrome)는 qa 가 수동으로 한 번(캡처 2장까지).

## 9. 내가 정한 것 (관행대로 — 바꾸려면 말해 줘. 14~18 은 결정 1~3 을 풀면서 정한 것)

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
14. (결정 1) 「푸시만 끄기」는 끈 것만 줄이 있는 별도 표(`push_type_mutes`), 광고 종류는 이 목록에서 뺀다(광고는 동의가 정한다). 처리기가 보내기 직전에 다시 확인한다(큐에서 기다리는 동안 끈 경우).
15. (결정 2) 잠금 화면 설정은 기본 factory 만 따른다. HIDDEN 은 실제 내용을 푸시 서비스에 아예 보내지 않고, 광고 종류는 가리지 않는다(`(광고)` 가 보여야 한다). 문구 기본은 ko 「새 알림이 있습니다」, 설정으로 덮는다.
16. (결정 3) 광고 규칙은 코어, 동의 읽기는 어댑터 `notification-push-legal`. 동의 · 철회는 `legal` 의 기존 경로로 받아 증거를 한 곳에 둔다.
17. (결정 3) 만료된 동의는 없는 것으로 본다. 새 판이 시행돼 유예가 끝난 `OUTDATED` 도 없는 것으로 본다(안전 쪽 — 문서에 「동의 범위가 바뀔 때만 새 판」 이라고 적는다). 동의 조회가 실패하면 보내지 않는다.
18. (결정 3) 시간대는 앱이 정한 출처(`PushRecipientZones`) → 서버 설정 `fallback-zone`; 시스템 기본 시간대는 쓰지 않는다. 야간 21~08시와 2년은 법정 값이라 모듈 기본으로 둔다.
19. FCM 의 `SENDER_ID_MISMATCH` 는 지우지 않는다(실패로 남긴다) - 반목표 「설정 실수로 대량 삭제」 가 우선이다. 다른 프로젝트의 서비스 계정을 넣으면 모든 토큰이 이 답을 받는다. 정말 다른 프로젝트 토큰이면 오래된 기기 정리가 치운다.

## 10. 작업 계획 (계획 `docs/superpowers/plans/2026-10-31-push.md`)

| 키 | 레포 | 조각 | 선행 | 크기 | 예상 |
|---|---|---|---|---|---|
| K1 | kotlin | `notification-push` + `-jdbc`: 계약(`PushSender` · `PushMessageFactory` · `PushDeviceStore` · `PushTypeMuteStore` · `PushTasks`) · 채널 · 작업 처리기(가짜 보내기로 판정 시험) · HTTP §5(`/preferences` 포함) · 종류 × 채널 끄기(`push_type_mutes`, 채널 건너뜀 · 처리기 재확인) · `lock-screen` FULL/HIDDEN 기본 factory · 세션 연동 · 계정 지우기 · 오래된 기기 정리 · V 파일 두 방언 · 모듈 문서 2 · 설정 문서 · 카탈로그(다섯 모듈 자리를 한 번에 — `notification-push-legal` 은 빈 껍데기 포함) · `docs/push-http-contract.md` | — | 대 | 6h |
| K2 | kotlin | `notification-webpush`: VAPID · 암호화(라이브러리, 벡터 시험) · 허용 목록 · 판정 표 · 머리 · 키 쌍 확인 · `DeployGuard` · 가짜 푸시 서비스 시험 · 문서 | K1 계약(SPI 만 먼저 합의) | 중 | 3h |
| K3 | kotlin | `notification-fcm`: 서비스 계정 → 토큰 · HTTP v1 · 판정 표 · 메시지 모양 · `DeployGuard` · 키 있을 때만 실제 발송 시험 · 문서 · `real-provider-setup.md` | K1 계약 | 중 | 2.5h |
| K5 | kotlin | 광고 푸시 서버(§11): 코어 게이트 · 야간 보류 · `(광고)` · 처리기 재확인 · 설정 + 기동 검사 · `push_advertising_notices` V 파일 · `notification-push-legal`(정책 · 변경 통지 · 재확인 스캔 · `GET /push/advertising`) · `legal`/`legal-jdbc` 기계장치 셋(`reconfirm` source · `ConsentChangeListener` · `latestAgreementsBefore` + 두 방언 `dbTest`) · 모듈 문서 · `docs/legal.md` · 계약 문서 · 카탈로그 · 회귀 시험 §11.8 | K1 | 대 | 5h |
| K4 | kotlin | 견본 · workbench 조립(광고 설정 · 견본 `legal` 문서 · 시간대 빈 포함) · yml · 시험 키 · `deploy.md` §7 · 따라가기 표 · CHANGELOG · 견본 통합 시험(노트 댓글 → 작업 → 가짜 보내기, 광고 종류 publish → 동의 게이트) | K1 · K2 · K3 · K5 | 중 | 2.5h |
| R1 | react | `@skeleton/push`: api(`preferences` 포함) · 지원 판정 · `usePush` · `usePushSync` · `unregisterPush` · `PushPrompt` · `PushSettings`(종류별 스위치 포함) · Vite 플러그인 + 워커 · 스토리 · README · 카탈로그 · CHANGELOG | K1 계약(§5 표, 가짜 API) | 대 | 4.5h |
| R3 | react | `@skeleton/push/advertising`: `getAdvertising` · `useAdvertising` · `AdvertisingSettings`(광고 수신 · 야간 별도 · 재확인 띠 · 철회 확인) · `@skeleton/legal` 선택 peer · 스토리 · README · 카탈로그 · CHANGELOG (§11.7) | R1 · K5 계약(§11.6 표, 가짜 API) | 중 | 3h |
| R2 | react | 견본 조립(플러그인 · manifest · 설정 · 띠 · 로그아웃 · 동기화 · `AdvertisingSettings`) · e2e 한 여정(가짜 푸시 서비스 · CDP) + 광고 동의 짧은 여정(동의 → 야간 체크 → 철회 → 상태 표시) | R1 · R3 · K4 합친 뒤 | 중 | 3.5h |

- 순서: K1 → K2 ∥ K3 → K4, **K5 는 K1 뒤**(K2 ∥ K3 ∥ K5 로 나란히 가능), R1 은 K1 계약 뒤(K1 과 나란히), **R3 는 K5 계약 뒤**(§11.6 표가 계약이라 K5 합치기 전에도 가짜 API 로 시작 가능), **R2 마지막**.
- 묶음: ① K1 ∥ R1 → ② K2 ∥ K3 ∥ K5 → ③ K4 ∥ R3 → R2 → 끝 검사(지휘: kotlin 바뀐 모듈만 한 번 · react 전체 + `e2e:sample` 한 번, 리뷰 kotlin 1 · react 1, qa 1 — 화면당 캡처 2장까지). 동시 최대 2~3.
- developer 8회 + 리뷰 2 + qa 1 = 에이전트 11. 벽시계 대략 16h(K1 6h → K5 5h → K4 2.5h → R2 3.5h 가 긴 줄). 결정 1 B 는 K1 +1h · R1 +0.5h 로, 결정 3 은 K5 5h + R3 3h(+ K4 · R2 에 각 0.5h)로 이미 표에 들어 있다. 요청 때 어림 「약 4h」보다 큰 것은 `legal` 쪽 기계장치 셋(재확인 스캔 포함)과 두 방언 시험 때문이다.
- 같이 만지는 파일: `settings.gradle.kts` · `capabilities.json` · `docs/modules/README.md` 는 K1 이 다섯 모두의 자리를 한 번에 만들고(K2 · K3 · K5 는 자기 항목 내용만), 견본 yml · `deploy.md` 는 K4 만(K5 는 `modules/**` · 자기 모듈 문서 · `legal` 문서만). 코어 `PushNotificationChannel` · 처리기는 K1 이 먼저 합친 뒤 K5 가 고친다(그래서 K5 는 K1 뒤).
- 계약이 바뀌면 kotlin 조각이 보고에 적고 지휘가 react 조각에 넘긴다. 스펙과 계획이 어긋나면 스펙이 이긴다.

## 11. 광고 · 마케팅 푸시 (결정 3 — K5 서버 · R3 화면)

> 법률 자문이 아니다. 모듈은 「동의를 증거로 남기고, 동의 없이는 보내지 않고, 야간에는 보류한다」는 기계장치를 준다. 광고 문서의 내용 · 전송자 정보 · 법 해석은 앱(주인)과 법률 검토의 몫이다(`docs/legal.md` 와 같은 선).

### 11.1 모듈 경계 — 새 모듈 `notification-push-legal` (어댑터 짝)

- **규칙은 `notification-push` 코어, 동의 읽기는 `notification-push-legal`.** 코어는 `AdvertisingConsentPolicy` 포트만 보고 돈다 — 코어가 `legal` 을 몰라야 광고를 안 쓰는 앱이 `legal` 없이 푸시를 쓰고(캡슐화), 광고 규칙 시험이 가짜 정책으로 된다. 동의 증거는 `legal` 의 일이라 어댑터가 `legal` 을 `api` 로 안다(`-jdbc` 와 같은 포트/어댑터 짝, 코어에 `compileOnly` 다리를 두지 않는다).
- 모듈 기본값은 꺼짐(중립): `advertising-types` 가 빈 목록이면 광고 규칙은 없는 것과 같고, 이 모듈을 안 붙인 앱의 동작은 바뀌지 않는다. 광고 종류를 설정했는데 `AdvertisingConsentPolicy` 빈이 없으면 기동 실패(「`notification-push-legal` 을 얹어라」) — 동의 확인 없이 광고가 나가는 구성이 조용히 생기지 않게.
- **`legal` 에 더하는 기계장치 셋**(기본 동작은 그대로, 옵트인 사용처가 없으면 닿지 않는다):
  1. `source = "reconfirm"` 인 동의는 이미 현재 판에 동의한 상태에서도 `AGREED` 사건을 더한다(기존 「같은 판이면 아무것도 안 함」 멱등의 단 하나의 예외). 일일 한도 · 판 검사는 그대로. 새 경로는 없다 — `POST /legal/consents` 에 `source: "reconfirm"` 을 실을 뿐.
  2. `ConsentChangeListener`(`fun interface`, `onChange(event: ConsentEvent)`) — 사건이 저장된 뒤 불린다. 빈이 없으면 아무 일도 없고, 던져도 삼킨다(증거는 이미 저장됐다). 동의 · 철회가 어느 경로(`legal` HTTP · 가입 · 앱 코드)로 와도 같은 통지를 보내려는 자리.
  3. `ConsentStore.latestAgreementsBefore(type, before, afterSubjectId, limit)` — 종류 `type` 의 **가장 최근 사건이 `AGREED` 이고 `at < before` 인 주체**를 주체 id 순으로 한 쪽씩. `legal-jdbc` 에 두 방언 쿼리 + `dbTest`.

### 11.2 관행(정보통신망법 §50) → 설계

| 의무 · 관행 | 설계 |
|---|---|
| 광고성 정보는 수신자의 **사전 동의** | 광고 종류는 `consent-type`(기본 `marketing`) 의 유효한 동의가 있을 때만 어느 푸시 채널(웹 푸시 · FCM · 앞으로 붙는 `PushSender`)로도 나간다. 동의 기록은 `legal` 의 append-only 사건(IP · UA · 판 해시) |
| **21~08시 전송은 별도 동의** | `night-consent-type`(기본 `marketing-night`)는 **다른 문서 · 다른 체크박스**(미리 체크 금지는 화면 규칙). 야간 동의는 광고 동의가 유효할 때만 유효(철회 한 번이면 광고 전체가 멈춘다 — 계산으로, 연쇄 쓰기 없이) |
| **2년마다 수신 동의 확인** | 동의 유효 기간 = 가장 최근 `AGREED` 사건 + `reconfirm-after`(기본 `P730D`). **지난 동의는 없는 것으로 본다**(조용히 안 나간다). 만료 `reconfirm-notice-before`(기본 30일) 전에 재확인 안내 알림을 한 번 보내고, 사용자가 `source: "reconfirm"` 으로 동의하면 기간이 새로 시작한다 |
| 제목에 **`(광고)` 표기** | 코어 채널이 `PushMessageFactory` **뒤에서** 제목 앞에 `title-prefix`(기본 `(광고)`)를 붙인다 — 앱 factory 가 잊어도 빠지지 않고, 이미 붙어 있으면 두 번 붙이지 않는다 |
| 전송자 명칭 · 수신 거부 방법 | 안내 알림에 `sender-name`(광고를 켜면 필수 설정)을 쓴다. 광고 본문 끝에 `body-suffix`(기본 없음, 예 「수신 거부: 설정 > 알림」)를 붙일 수 있다. 문서(`marketing`)에 회사 사실 · 연락처를 싣는 것은 앱 몫(`legal` 의 `{{fact}}`) |
| 동의 · 철회 · 재확인 **처리 결과 통지** | 동의 · 철회 · 재확인이 저장되면 곧바로(법정 기한 안) 정보성 알림 `advertising.consent.changed` 를 보낸다 — 전송자 명칭 · 처리 일시 · 무엇을(광고 / 야간) · 철회 방법. 광고 동의가 없는 사람에게도 나간다(정보성이라 게이트 밖) |
| 철회 방법은 동의만큼 쉽게 | 설정 화면의 스위치 하나 → `POST /legal/consents/{type}/withdraw` |
| 동의 없는 광고의 **야간 보류** | 야간(받는 사람 시간대 21:00~08:00)이고 야간 동의가 없으면 버리지 않고 **다음 08시 이후로 보류** 후 발송. 작업 큐가 지연 실행을 지원한다(`JobQueue.enqueue(..., runAt)` — 확인함) |

### 11.3 설정 (`skeleton.notification.push.*`, 모듈 기본 = 광고 꺼짐)

```yaml
skeleton.notification.push:
  advertising-types: []              # 광고성 종류(제품이 정한다). 비어 있으면 아래 규칙은 아무것도 하지 않는다
  advertising:
    consent-type: marketing          # legal 문서 종류 — 광고 수신 동의
    night-consent-type: marketing-night  # 야간(21~08시) 별도 동의
    night-start: "21:00"             # 법정 값이라 모듈 기본으로 둔다
    night-end: "08:00"
    fallback-zone:                   # 받는 사람 시간대를 모를 때. 켜면 필수 — ZoneId.systemDefault() 는 쓰지 않는다
    sender-name:                     # 전송자 명칭. 켜면 필수
    reconfirm-after: P730D
    reconfirm-notice-before: P30D
    reconfirm-scan-interval: PT6H
    reconfirm-link: /settings/notifications   # 안내 알림을 누르면 가는 앱 안 경로
    title-prefix: "(광고)"
    body-suffix: ""
```

기동 검사(`advertising-types` 가 비어 있지 않을 때): 정책 빈 있음 · `fallback-zone` · `sender-name` 있음 · `night-start`/`night-end` 가 읽힘 · `ttl` ≥ 12시간(야간 보류가 최대 11시간이라 만료로 광고가 사라지지 않게) · 두 동의 종류가 다름 · `advertising-types` 가 알림 종류 `advertising.consent.*` 와 겹치지 않음. 안내 알림 종류가 단말에 가려면 앱이 `push.types` 에 넣는다(안 넣으면 알림함에만 쌓인다).

### 11.4 발송 규칙 (`PushNotificationChannel` + 작업 처리기)

받는 사람마다(광고 종류일 때만):

1. `consent = policy.consentOf(accountId, now)` → `(send, night)`. **예외가 나면 `send = false`**(실패 시 닫힘, 로그만).
2. `send = false` → 그 사람 건너뜀(작업 없음).
3. `zone = PushRecipientZones.zoneOf(accountId) ?: fallback-zone`. 지금이 그 시간대 `[night-start, night-end)` 안이고 `night = false` → 작업을 **`runAt` = 그 시간대의 다음 `night-end`** 로 넣는다(자정 전이면 내일 08:00, 자정 후면 오늘 08:00; 서머타임 구간은 `ZonedDateTime` 이 푼다). 아니면 지금 넣는다.
4. 처리기가 작업을 집었을 때 **다시 같은 판정**을 한다 — 철회 · 만료로 `send = false` 면 보내지 않고 끝, 아직 야간이고 `night = false` 면 같은 규칙으로 새 `runAt` 작업을 넣고 끝(앞당겨 집혔을 때의 안전망). 대기 중 철회가 광고를 내보내면 사고라서 이 재확인이 핵심이다.
5. 보내기 직전 제목에 `(광고)`, 본문에 `body-suffix`. 광고는 `lock-screen: HIDDEN` 이어도 가리지 않는다(§4).

묶기: 보류 중 같은 `collapseKey` 광고는 `Topic` / `collapse_key` 가 쌓인 것을 하나로 합쳐 아침에 몰리지 않게 한다(기존 규칙 그대로). 받는 사람 선별(광고를 누구에게 보낼지)은 앱 몫이고, 이 게이트는 안전망이다. 동의 조회는 받는 사람 한 명당 읽기 한 번(1차).

**시간대 출처**(우선순위): ① `PushRecipientZones` 빈(앱이 구현 — 견본은 계정 프로필의 `timeZone`) → ② `advertising.fallback-zone`(서버 설정). 요청 헤더 시간대(`time` 모듈의 `TimeContext`)는 쓰지 않는다 — 발송은 배경 작업이라 요청이 없다.

### 11.5 재확인 (2년)

- **유효성**: 정책 구현은 `legal` 상태가 `CURRENT`/`GRACE`(철회 · 미동의 · 새 판 시행 뒤 유예 밖 `OUTDATED` 는 아님) **그리고** 가장 최근 `AGREED` 사건 `at + reconfirm-after > now` 일 때만 유효. 광고 문서(`marketing`)의 새 판은 동의 범위가 바뀔 때만 올리라고 문서에 적는다(올리면 다시 동의를 받아야 한다 — 안전 쪽).
- **안내 스캔**(`notification-push-legal`, `reconfirm-scan-interval` 마다, 프로젝트마다 `skeletonTaskScheduler`): 각 동의 종류마다 `latestAgreementsBefore(type, now − (reconfirm-after − reconfirm-notice-before), …)` 를 쪽 단위로 읽고, `push_advertising_notices` 에 `(account, type, agreed_at)` 표시가 없으면 `advertising.consent.reconfirm` 알림을 **결정적 이벤트 id**(`evt_` + 해시)로 발행하고 표시를 남긴다(발행 → 표시 순서: 중간에 죽으면 다음 스캔이 한 번 더 보내도 받은편지함은 같은 id 를 한 줄로 합친다). 안내 알림 내용: 전송자 명칭 · 동의한 날짜 · 만료일 · 「확인」 링크(`reconfirm-link`) · 철회 방법. 이미 만료된 동의에도 안내는 한 번 간다(보낼 수 있는 정보성 알림).
- **재확인 수단**: 화면의 「수신 동의 확인」 단추 → `POST /legal/consents` `source: "reconfirm"` (§11.6). 만료 뒤에도 같은 단추로 다시 동의할 수 있다(미동의와 같은 경로, `source` 만 다르다).

### 11.6 API

새 경로는 읽기 하나뿐이고(`notification-push-legal`), 동의 · 철회는 **`legal` 의 기존 경로를 그대로** 쓴다(증거가 한 곳에 쌓이고, 화면이 `@skeleton/legal` 의 `createLegalApi` 를 재사용한다).

`GET /api/v1/push/advertising` (인증, 광고 종류가 설정됐을 때만):
```json
{ "value": {
  "advertisingTypes": ["promo.notes-tips"],
  "senderName": "…",
  "consent": { "type": "marketing", "state": "AGREED", "agreedAt": "2026-10-02T03:04:05Z", "expiresAt": "2028-10-01T03:04:05Z", "reconfirmDue": false, "effective": true },
  "night":   { "type": "marketing-night", "state": "NONE", "agreedAt": null, "expiresAt": null, "reconfirmDue": false, "effective": false },
  "nightWindow": { "start": "21:00", "end": "08:00", "zone": "Asia/Seoul" } } }
```
`state`: `AGREED`(동의했고 만료 전) · `EXPIRED`(동의했지만 `expiresAt` 지남) · `OUTDATED`(새 판, 다시 동의 필요) · `WITHDRAWN` · `NONE`(한 적 없음). `effective` = 실제로 보낼 수 있다(`consent.effective` = `state == AGREED`, `night.effective` = 야간 동의가 `AGREED` **이고** `consent.effective`). `reconfirmDue` = `AGREED` 이고 만료까지 `reconfirm-notice-before` 이내. `nightWindow.zone` = 이 사용자에게 적용되는 시간대(§11.4 의 출처 순서).

`legal` 경로(이미 있음, 계약 `docs/legal-http-contract.md`):

| 하는 일 | 호출 |
|---|---|
| 광고 수신 동의 | `POST /api/v1/legal/consents` `{ "consents": [ { "type": "marketing", "version": "<GET /legal/documents 의 현재 판>", "locale": "ko" } ], "source": "push-settings" }` |
| 야간 수신 동의 | 같은 경로, `type: "marketing-night"` (별도 문서 · 별도 체크, 광고 동의와 한 호출에 같이 실어도 되지만 화면이 따로 체크받는다) |
| **재확인** | 같은 경로 · 같은 본문에 `"source": "reconfirm"` — 현재 판에 이미 동의한 상태여도 사건이 더해져 2년이 새로 시작한다 |
| 철회 | `POST /api/v1/legal/consents/marketing/withdraw` (본문 없음) · 야간만 `…/marketing-night/withdraw` |

철회 · 동의 · 재확인 직후 `advertising.consent.changed` 알림이 간다(§11.2). 가입 화면의 선택 체크박스(`marketing`)는 기존 가입 동의 경로 그대로다(소셜 · 매직 링크 첫 로그인은 동의 화면에서).

### 11.7 화면 (R3 — `@skeleton/push/advertising`, `@skeleton/legal` 은 선택 peer)

- `useAdvertising(pushApi, legalApi)` — `GET /advertising` + 동의 · 재확인 · 철회 호출과 갱신. `createPushApi` 에 `getAdvertising()` 추가.
- `<AdvertisingSettings push legal labels? />` — 설정 칸(`PushSettings` 아래):
  - 「광고성 정보 수신」 — 꺼짐이면 「동의하기」(눌리는 단추) → `DocumentDialog`(본문 `marketing` 을 보여 주고 확인 단추에서 동의 호출. **미리 체크 없음**), 켜짐이면 스위치 · 동의한 날짜 · 만료일.
  - 「야간(21~08시) 광고도 받기」 — 별도 체크 · 별도 문서 보기. 광고 수신이 꺼져 있으면 잠긴다. 안내 문구에 시간대(`nightWindow.zone`).
  - 재확인 띠 — `reconfirmDue` 면 「수신 동의를 확인해 주세요 (D-n)」 + 「확인」 단추, `EXPIRED` 면 「동의가 만료되어 광고를 받지 않고 있어요」 + 「다시 동의」. 둘 다 `source: "reconfirm"`.
  - 철회 — 스위치 끔 → 확인 대화상자 → 철회 호출 → 「철회했어요 (일시)」.
- 앱 안 동의 요구가 아니다: 광고를 못 받는다고 서비스 이용을 막지 않는다(`ReconsentGate` 대상 아님, 선택 종류).
- 스토리(`play`): 미동의 · 동의 · 야간 동의 · 재확인 임박 · 만료 · 철회 중 · 서버 오류. 문구 ko/en `labels`, 눌리는 것은 눌리게(`Button`).
- 시험: 동의 호출의 본문 · `source`(`push-settings` / `reconfirm`) 기록 · 체크 미리 선택 없음 · 광고 꺼짐이면 야간 잠김 · 철회 확인 단계 · `capabilities` 검사.

### 11.8 검증 (K5 회귀 — 광고는 돈 · 법 · 개인정보라서 이 목록은 모두 시험으로 둔다)

- **동의 우회 금지**: 미동의 · 철회 · 만료(`reconfirm-after` 경계 전후) · 새 판 `OUTDATED` 인 사람에게 광고 작업이 안 생긴다 — 웹 푸시 기기 · FCM 기기 모두. 정보성 종류는 영향이 없다.
- **대기 중 철회**: 큐에 있는 광고(보류 포함)를 처리기가 집었을 때 철회 · 만료면 `PushSender.send` 가 불리지 않는다.
- **조회 실패 = 안 보냄**: 정책이 던지면 작업이 없다.
- **야간 보류**: 받는 사람 시간대로 20:59 / 21:00 / 07:59 / 08:00 경계 · 야간 동의 있으면 즉시 · 야간 동의가 있어도 광고 동의가 없으면 아무것도 안 감 · 보류 `runAt` 이 다음 08:00(자정 전 · 후, 서머타임이 있는 시간대) · 앞당겨 집힌 작업은 다시 보류 · 시간대 출처 우선순위(계정 → 폴백).
- **표기**: 앱 factory 가 접두를 안 붙여도 `(광고)` 가 제목 앞에 있다 · 두 번 붙지 않는다 · HIDDEN 이어도 광고는 내용이 보인다.
- **재확인**: `source: "reconfirm"` 이 같은 판에도 사건을 더하고 만료가 새로 시작된다(`legal`) · 안내가 동의 주기당 한 번 · 안내는 광고 동의가 없는 사람에게도 간다 · `latestAgreementsBefore` 가 철회된 사람 · 최신 사건이 아닌 주체를 내지 않는다(두 방언 `dbTest`).
- **통지**: 동의 · 철회 · 재확인 어느 경로든 `advertising.consent.changed` 가 한 번 · 리스너가 던져도 동의 기록은 남는다.
- **조립**: 광고 종류를 켰는데 정책 빈 · `fallback-zone` · `sender-name` 이 없으면 기동 실패 · `ttl` < 12시간이면 기동 실패 · 광고를 안 켜면 이 모듈 없는 앱과 동작이 같다.

### 11.9 범위 밖 · 앱의 몫

- **푸시 밖 채널**: 이 게이트는 푸시 채널(웹 푸시 · FCM)에만 있다. 메일 · 슬랙 · SSE 로 광고를 보내는 앱은 직접 동의를 본다(`AdvertisingConsentPolicy` 는 공개 포트라 `notification-mail` 이 나중에 같은 정책을 쓸 수 있다). 받은편지함 저장은 전송이 아니라 앱 안 기록이다. 모듈 문서에 경고 한 줄.
- 광고 문서(`marketing` · `marketing-night`) 본문 · 전송자 연락처 · 청소년 등 다른 동의 · 법 해석은 앱 + 법률 검토.
- 대량 발송 일괄 API · 전달 통계 · 받는 사람 세분화는 다음.

## 정해야 할 것

없음 — 처음 세 가지(종류 × 채널 · 잠금 화면 · 광고 푸시)는 아래 「결정됨」으로 정해졌고 본문에 반영됐다. 설계 중 새로 생긴 갈림길은 지휘 보고에 따로 올린다.

## 결정됨 (2026-10-31)

형이 골랐다. 이 절이 본문보다 우선한다.

1. 종류별 끄기 — 형: "국룰로 갑시다". 업계 관행(GitHub · Slack · 인스타그램 식)대로 **B: 종류 × 채널 스위치**를 넣는다. 기기 단위 켬/끔 + 종류별로 「푸시만 끄기」(알림함에는 남는다). 기존 종류별 끄기(모든 채널)는 그대로 둔다.
2. 잠금 화면 내용 — 형: "옵션에 따라". 모듈 설정 하나로 고른다(예: `skeleton.notification-push.lock-screen: FULL | HIDDEN`). 모듈 기본값은 관행대로 FULL(제목 · 본문), HIDDEN 이면 「새 알림이 있습니다」만. 그 위에 `PushMessageFactory` 교체도 그대로 열어 둔다. 견본 앱은 yml 에 FULL 을 명시한다.
3. 광고 · 마케팅 푸시 — 형: "아냐 다루자 광고도". **1차에 넣는다**(정해야 할 것 3 의 B): legal 동의 기록으로 광고 수신 동의 · 야간(21~08시) 별도 동의 · 2년 재확인 · 「(광고)」 표기 · 동의 없는 야간 발송 보류. 정보성과 광고성은 알림 종류 단위로 구분하고, 광고성 종류는 동의가 없으면 어느 채널로도 푸시하지 않는다. 대 조각 하나(약 4시간)가 더 붙는다 — 작업 계획에 K5(서버) · R3(동의 화면)로 더한다.

반영 메모: 결정 2 의 설정 키는 레포 관례(`skeleton.notification.slack` · `.sse` 처럼 `skeleton.notification.<채널>`)에 맞춰 `skeleton.notification.push.lock-screen` 으로 적었다. 결정 1~3 은 본문 §2 · §3 · §4 · §5 · §6 · §7 · §8 · §10 · §11 에 풀어 놓았다.
