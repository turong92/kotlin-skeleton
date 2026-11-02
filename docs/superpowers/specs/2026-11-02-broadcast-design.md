# 공지 발송(전체 · 대상 공지 → 알림함 + 푸시) — 설계

> 2026-11-02. 요청(형): 「공지 푸시랑 파일 관련해서 먼저 해보자」 중 **공지 푸시**. 푸시 설계(2026-10-31)가 범위 밖으로 뺀 「받는 사람 없는 전체 공지의 푸시」 · 「대량 발송」 · 「전달 통계」의 다음 판이다. 파일은 따로 스펙.
> 상태: 맨 끝 「결정됨」 4건을 본문에 반영했다(본문 = 결정). 푸시 클릭 열람(결정 3)의 계약은 §2-B.
> 형 원칙: 공개 스켈레톤 · 캡슐화된 조립식 모듈 · 모듈 기본값 중립(꺼짐, 앱 yml 이 켬) · 업계 관행 · 회귀 시험은 돈 · 보안 · 동시성 · 데이터 손실만 · 개인 도메인 · 이메일 흔적 없음(`example.com`).
> 대상: kotlin-skeleton(새 `notification-broadcast` · `notification-broadcast-jdbc`, 기존 `notification` · `notification-jdbc` · `notification-push` · `notification-push-jdbc` · `platform` · `account` · `account-jdbc` · `workspace` · `workspace-jdbc` 에 작은 자리, `apps/sample` · `apps/workbench`) + react-skeleton(`@skeleton/notifications/admin` 새 하위 경로, `apps/sample`).
> 기준: **push 브랜치(kotlin `c3723dd9`, react `74266a1`)가 main 에 합쳐진 뒤** 시작한다 — 광고 게이트 · 푸시 채널 · 처리기를 그대로 쓴다.
> 읽은 코드: `notification/{NotificationContracts,NotificationEvent,InMemoryNotificationBroker,NotificationInbox}.kt` · `notification/advertising/AdvertisingGate.kt` · `notification-jdbc` V 파일 셋 · `JdbcNotificationInboxRepository.save`(줄마다 `recipient_ids_json`) · `notification-push/{PushNotificationChannel,PushDeliveryWorker,PushContracts}.kt` · push 메모(K1~K5b) · `notification-slack` 설정(`minimum-severity: WARNING`) · `job-queue-jdbc/{JobQueue,JobQueueProperties}.kt`(처리기는 트랜잭션 밖, `runAt` 지연 지원) · `reward-jdbc/RewardJobs.kt`(작업 어댑터 · DEAD 고리 선례) · `platform/common/authz/*`(`PermissionDeclaration` · `OperatorAuditLog` 자리 · `RoleHolders` 선례) · `platform/common/account/AccountFacts.kt` · `campaign/CampaignPermissions.kt` · `account-jdbc` V(`account_roles` · 역할 색인) · `workspace/WorkspaceStore.kt` · 권한 설계(2026-10-24) · 알림센터 설계(2026-10-28) · react `packages/notifications` · `packages/rewards/src/admin`(운영 화면 관례 `can` · `labels`) · `apps/sample/src/routes/AdminCampaignsPage.tsx`.

## 0. 관행 조사 → 권장안

| 물음 | 업계 | 권장 |
|---|---|---|
| 전체 공지 푸시를 어떻게 | FCM 주제(topic) 발송은 「구독한 모두」에 한 요청 — 받는 사람별 거르기(끄기 · 광고 동의 · 야간)와 사람별 결과가 없다. 웹 푸시에는 주제가 없다. 알림 SaaS(Knock · Novu · OneSignal)는 대상 목록을 받아 **받는 사람마다 펼친다**(fan-out) | 받는 사람 묶음마다 기존 `NotificationPublisher.publish(recipientIds = 묶음)` 를 부른다 — 끄기 · 「푸시만 끄기」 · 광고 게이트 · 기기 작업 · 재시도가 모두 기존 길로 공짜로 따라온다 |
| 알림함: 쓰기 펼침 vs 읽기 합침 | 공지 표 하나 + 읽음 표(읽기 때 합침)는 쓰기가 싸지만 알림함 조회 · 안 읽은 수 · 묶기를 모두 고쳐야 한다. 대부분의 앱 알림함 서비스는 받는 사람마다 줄(쓰기 펼침) | **쓰기 펼침**(사람마다 `notification_inbox` 한 줄). 기존 알림함 · 안 읽은 수 · SSE 가 손대지 않고 돈다. 열람 통계도 줄의 `read_at` 으로 바로 나온다 |
| 속도 제한 | FCM 은 「대량 발송은 천천히 올려라」 · 프로젝트 쿼터 초과 시 429. OneSignal 「throttling(분당 N)」, Braze 「rate limit(분당)」 | 분당 받는 사람 수(`recipients-per-minute`, 기본 6000)로 **묶음 간격**을 둔다(묶음 200명 → 2초마다 하나). 기기 쪽 429 · 5xx 는 기존 푸시 처리기의 재시도 |
| 대량 처리의 몸체 | 단계 작업을 큐에 이어 붙이고(체크포인트) 죽으면 거기서 다시 | `job-queue-jdbc` 위 **이어 달리기 작업**(한 작업 = 한 묶음, 다음 작업을 같은 트랜잭션에 넣는다). 진행 위치(커서)는 공지 줄에 |
| 중복 방지 | 발송 단위 멱등 키(메시지 × 받는 사람) | ① 상태 전이를 조건부 갱신(두 번 누름) ② 단계 작업마다 `run_token` + `seq` 를 공지 줄 잠금 아래 확인(재시도 · 동시 실행) ③ 알림함 pk `(recipient_id, event_id)` — 같은 공지 id 재저장은 줄이 안 는다 |
| 예약 · 취소 | 예약 · 예약 풀기 · 진행 중 멈춤은 어디나 있다. 이미 간 푸시는 못 거둔다 | 즉시 / 예약(최대 90일). 취소하면 다음 묶음이 없고 **큐에서 기다리던 푸시(야간 보류 포함)도 안 나간다**. 이미 저장된 알림함 줄은 남는다 |
| 테스트 발송 | 「나에게 테스트」는 모든 도구의 기본, 통계에 안 섞는다 | 나에게만, 제목 앞 `[테스트]`, 통계에 안 셈, 운영자당 30초에 한 번 |
| 통계 | 대상 · 보냄 · 전달 · 실패 · 열람(클릭). OneSignal · Braze · FCM 모두 「전달」과 「클릭(열림)」을 따로 센다 | 예상 대상 · 넘긴 사람 · 알림함 저장 · **알림함 읽음** · 채널별(푸시) 넣음 · 성공 · 실패 · 보내지 않음 · **푸시 클릭(`opened`)**. 알림함 읽음과 푸시 클릭은 **따로 센다** — 합친 사람 수는 안 낸다(사람 단위 대조가 필요하고 업계도 채널별로 보인다) |
| 광고성 공지 | 정보통신망법 §50 — 앞 설계 §11 그대로 | 공지 종류가 `skeleton.notification.advertising.types` 안이면 광고. **광고 수신 동의가 없는 사람은 공지에서 통째로 뺀다(알림함 · 푸시 모두 없음)**. 동의한 사람의 푸시는 기존 게이트(야간 보류 · `(광고)`). 정보성 공지는 동의와 무관하게 대상 전원 — 알림함 + 푸시(「푸시 끄기」 설정은 존중) |
| 운영 권한 | 보기와 보내기를 나눈다(Braze 「캠페인 보내기」 권한 분리) | 권한 둘 `notification-broadcast.read` · `.send`(기본 역할 없음 — 전권만, `campaign` 과 같다) |

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| 공지 | 종류(앱이 정한 목록) · 제목 · 본문 · 앱 안 링크 · 대상 · 채널 · 즉시 / 예약 | 이미지 · 첨부(파일 스펙 뒤), 다국어 본문, 중요도(긴급 → 푸시 `high`), 복제 |
| 대상 | 전체(활성 계정) · 역할 · 워크스페이스 + **세그먼트 = 확장 지점**(`AudienceSource` 빈 하나) | 조건 편집기(UI 로 세그먼트 만들기), CSV 대상, 받는 사람 현지 시각 발송 |
| 채널 | 알림함(늘) + 푸시(켬/끔) + 앱이 더하는 채널(메일 등 — 선언 하나 + 구독자) | 채널별 다른 문구 |
| 대량 | 묶음 · 간격(속도) · 재시도 · 중복 방지 · 취소 · 실패 뒤 이어 보내기 | 알림함 회수(이미 들어간 줄 지우기 — `deleteByTopic` 류가 이미 있다), 승인 단계 |
| 통계 | §0 의 숫자(푸시 클릭 포함), 보내는 동안 갱신 | 시간대별 그래프 · 내보내기 |
| 운영 | **서비스 운영자만**: 권한 둘 · `send` 한 사람이 혼자 보냄(2인 승인 없음) · 공지 줄에 누가 · 언제(만듦 · 보냄 · 취소) · `OperatorAuditLog` 호출 · 나에게 테스트 발송 · 대상 수 확인 대화상자 | 2인 승인 · 워크스페이스 관리자의 자기 공지 |

**비목표**: 공개 공지 게시판(로그인 없이 보는 목록 — `board` 가 맡는다), 메일 채널 구현 자체(채널 자리만), FCM 주제 발송, A/B 시험, 사용자 현지 시각 발송, 이미 간 푸시 회수.
**반목표**(깨지면 사고): 한 사람에게 같은 공지가 두 번 간다(재시도 · 동시 실행 · 두 번 누름 · 예약 풀고 다시 예약) / 취소했는데 다음 묶음이나 큐의 푸시가 나간다 / 중간 실패로 일부가 영영 빠진다(이어 보내기로 빠짐 · 중복 없이 끝까지) / 광고 공지가 게이트를 우회한다 / `.read` 만 가진 사람이 보낸다 / 공지 하나가 알림함 줄마다 받는 사람 목록 전체를 복제한다(지금 `recipient_ids_json` — §3) / 이 모듈을 안 붙인 앱의 동작이 바뀐다.

## 2. 모듈 경계 · 확장 지점

```
notification ◀─api── notification-broadcast ◀─api── notification-broadcast-jdbc ──impl──▶ job-queue-jdbc, persistence-jdbc
                  ··(모름)··  notification-push  ← 채널 표지 · 전달 고리만 지킨다(공지를 모른다)
platform(AudienceSource) ◀── account(all · role) · workspace(workspace) · 앱(세그먼트)
```

- `notification-broadcast` 는 `notification` 만 안다 — 푸시 · `account` · `workspace` · `legal` 을 모른다. 대상은 `platform` 의 `AudienceSource` 빈으로, 채널은 브로커 구독자로, 통계는 `notification` 의 전달 고리로 받는다.
- **알림 코어에 더하는 것**(모두 기본은 지금과 같다):
  1. `NotificationEvent.channels: Set<String>? = null` — null = 모든 채널(지금과 같다), 집합이면 **그 안의 채널만**. 좁히기만 한다(채널 자신의 종류 규칙 — 예: `push.types` — 은 그대로). 알림함 저장 · SSE 는 채널이 아니라 늘 간다.
  2. `NotificationChannelDeclaration(key)` 빈 — 붙은 채널 목록(공지 화면이 고를 칸). 푸시가 `push` 를 선언한다.
  3. `NotificationDeliveryHook`(빈 여럿, 모두 기본 메서드): `halted(eventId): Boolean = false`(보내기 직전 「멈췄나」) · `onQueued(eventId, channel, count)` · `onOutcome(eventId, channel, DELIVERED|FAILED|DROPPED)` · `onOpened(eventId, channel)`(사람이 그 알림을 눌러 연 **첫** 한 번 — §2-B). 채널이 부르고 던지면 삼킨다. 받는 사람 id 는 싣지 않는다(통계만).
  4. `NotificationInboxRepository.eventStats(eventId): InboxEventStats(stored, read)?` — 기본 null(모름). `notification-jdbc` 가 구현.
- **푸시가 지킬 것**: 채널은 `event.channels` 에 `push` 가 없으면 건너뛰고, 기기 작업을 넣은 수로 `onQueued` 한 번. 처리기는 보내기 전 `halted` 면 끝(DROPPED), 결과마다 `onOutcome`(Delivered → DELIVERED · Gone → FAILED · 건너뜀(기기 없음 · 주인 바뀜 · 만료 · 푸시만 끔 · 광고 Skip) → DROPPED · 재시도를 다 쓰거나 영구 실패로 DEAD → `notification-push-jdbc` 의 DEAD 고리가 FAILED). 광고 재보류(Hold)는 아무것도 안 센다.
### 2-B. 푸시 클릭 열람 (결정 3 B)

푸시를 눌러 연 것을 서버가 안다 — 서비스 워커(웹)와 모바일 앱이 「열었다」를 알리고, 서버는 사람×공지 당 한 번만 센다. 최소 계약:

- **페이로드**(푸시 계약 `v: 1` 에 칸 하나 추가): 이미 `id`(= 이벤트 id, 공지면 `bc_…`) · `type` · `title` · `body` · `link` · `tag` 가 있다. 여기에 `open`(문자열, 선택)을 더한다. `open = "<deviceId>.<mac>"` — `deviceId` 는 기기 표의 불투명 id(계정 id 아님), `mac` = `base64url(HMAC-SHA256(secret, id \0 deviceId \0 recipientId))` 앞 16바이트. 받는 사람 id 는 토큰에 **싣지 않고**, 서버가 기기 표로 현재 주인을 찾아 mac 을 다시 계산해 맞춘다 — 그래서 위조 · 다른 공지 id 로 바꿔 쓰기 · 기기 주인이 바뀐 뒤의 옛 토큰이 모두 거절된다. 만료는 없다(`id` 와 묶여 있어 재사용해도 같은 한 건이고, 푸시 자체가 만료 시간이 있다). 비밀 `skeleton.notification.push.open-report.secret` 가 비면 기능이 꺼진다 — 페이로드에 `open` 이 없고 엔드포인트도 없다(기본 꺼짐). 켰는데 길이가 짧으면(< 32자) 기동 실패.
- **보고 경로**: `POST /api/v1/push/opened` `{ "id": "<이벤트 id>", "open": "<토큰>" }`, **인증 없음**(서비스 워커는 로그인 쿠키 · CSRF 토큰이 없다 — `notificationclick` 은 앱 창 밖의 서비스 워커에서 돈다. 토큰의 서명이 인증이다). 보안 설정에서 이 경로 하나만 `permitAll` + CSRF 제외. 응답은 맞으면 204(처음이든 중복이든 같게), 서명이 틀리거나 `id` 가 안 맞으면 400 `PUSH.INVALID_OPEN_TOKEN`, 기기가 없어졌으면 204 로 조용히 버린다. 바디 크기 제한 1KB. 한도는 따로 두지 않는다(검증이 mac 한 번이고, 센 값은 사람×공지 당 1 로 막혀 있다).
- **저장**: `notification-push-jdbc` 의 새 표 `push_opens(event_id varchar(40), recipient_id varchar(128), opened_at, pk(event_id, recipient_id))` — `insert … on conflict do nothing`(MySQL `insert ignore`) 이 1줄이면 첫 보고, 0줄이면 중복. 알림함 줄의 `read_at` 은 건드리지 않는다(눌러 연 것과 알림함에서 읽은 것을 섞지 않는다). 푸시 클릭만 새 표를 쓰고 기존 표는 그대로. 보관 정리는 1차에 없다(줄이 작다 — 문서 한 줄, 필요해지면 다음).
- **전달 고리**: 첫 보고일 때만 `NotificationDeliveryHook.onOpened(id, "push")` 를 부른다(던지면 삼킨다). 공지 모듈은 푸시를 모른다 — 접두사 `bc_` 인 것만 `broadcast_channel_stats(push).opened` 를 원자 `+1` 한다(`bct_` 테스트는 안 센다).
- **통계 모양**: 별도 칸 `channels.push.opened` 하나. `inbox.read`(알림함 읽음)와 합치지 않는다. 화면의 「클릭률」 = `opened / delivered`.
- **클라이언트**: 서비스 워커는 `notificationclick` 에서 `open` 이 있을 때만 `fetch('/api/v1/push/opened', { method: 'POST', keepalive: true, headers, body })` 를 **창 열기와 병행**한다(보고가 실패해도 창은 열린다 · 기다리지 않는다). 푸시 알림의 `data` 에 `id` · `open` 을 `link` 와 같이 넣어 둔다. 모바일 앱(FCM · APNs)은 같은 필드를 데이터 메시지에 받아 같은 경로로 보낸다 — 이 스켈레톤은 웹만 구현한다.
- **모듈 경계**: 토큰 발급(페이로드에 싣기) · 검증 · 보고 컨트롤러 · `push_opens` 는 모두 `notification-push`(+`-jdbc`)가 가진다. 공지 모듈은 고리 `onOpened` 만 받는다. `notification` 코어는 고리 메서드 하나만 늘어난다.

- 슬랙 전달기는 그대로다 — 공지는 늘 `INFO` 라 기본 `minimum-severity: WARNING` 에 걸려 슬랙으로 가지 않는다(문서 한 줄).

| 확장 지점 | 자리 | 고른 이유 |
|---|---|---|
| `AudienceSource`(`kind` · `param: NONE\|ROLE\|WORKSPACE\|TEXT` · `validate(param)` · `count(param)` · `page(param, after, limit)` — 계정 id 오름차순 · 겹침 없음) | `platform` `common/audience` | **세그먼트 = 확장 지점**. `RoleHolders` · `AccountFacts` 처럼 `account` · `workspace` 가 공지를 모르고 구현한다. 앱의 세그먼트(「지난달 결제한 사람」)도 빈 하나 |
| 기본 구현: `all`(활성 계정) · `role`(그 역할을 가진 활성 계정) | `account` + `account-jdbc`(키셋 쿼리) | 계정 표를 아는 곳이 계정 모듈뿐 |
| 기본 구현: `workspace`(그 워크스페이스의 활성 멤버) | `workspace` + `-jdbc` | 같은 이유. 닫힌 워크스페이스는 `validate` 에서 거절 |
| `BroadcastStore` · `BroadcastTasks` | `notification-broadcast`, 구현 `-jdbc` | 다른 모듈과 같은 포트/어댑터 짝(`PushTasks` · `RewardTasks` 선례) |
| 채널 | `NotificationChannelDeclaration` + 브로커 구독자 | 메일 채널을 붙이는 앱은 선언 하나 + `channels` 를 지키는 구독자 하나(+ 통계를 원하면 고리) |
| 설정 `skeleton.notification.broadcast.*` | §6 | 공지 종류 · 속도 · 묶음은 제품이 정한다 |

## 3. 데이터 (`notification-broadcast-jdbc`, 두 방언 짝 V 파일 · `migrations.lock`, 시각 PG `timestamptz` · MySQL `datetime(6)` UTC)

`broadcasts`:

| 칸 | 모양 | 뜻 |
|---|---|---|
| `id` | `varchar(40)` pk `bc_…` | **알림 이벤트 id 로도 쓴다**(모든 묶음이 같은 id — 알림함 pk 로 멱등, 고리가 접두사 `bc_` 로 자기 것을 안다) |
| `status` | `varchar(16)` | `DRAFT` · `SCHEDULED` · `SENDING` · `SENT` · `CANCELLED` · `FAILED` |
| `type` · `title` · `body` · `link` | `varchar(128)` · `(255)` · `(2000)` null · `(1000)` null | 알림 종류(설정 목록 안) · 제목 ≤ 100자 · 본문 ≤ 2000자 · 링크는 `NotificationEvent.isValidLink` |
| `channels` | `varchar(255)` | 쉼표 목록(`""` = 알림함만) |
| `audience_kind` · `audience_param` | `varchar(64)` · `(128)` null | |
| `scheduled_at` | 시각 null | null = 즉시 |
| `run_token` · `next_seq` · `cursor_id` | `varchar(32)` null · `int` · `varchar(128)` null | 이어 달리기 상태(§4). 끝나면 `cursor_id` 를 지운다 |
| `estimate` · `recipients` | `bigint` null · `bigint` | 시작 때 `count` · 실제로 넘긴 사람 수 |
| `created_by` · `sent_by` · `cancelled_by` | `varchar(128)` | 운영자 계정 id(이름 · 이메일 아님) |
| `created_at` · `updated_at` · `send_requested_at` · `started_at` · `finished_at` · `cancelled_at` | 시각 | |
| `failure_reason` | `varchar(500)` null | 예외 종류 한 줄(비밀 · 받는 사람 없음) |

색인 `(status, id)`. `broadcast_channel_stats(broadcast_id, channel varchar(32), queued, delivered, failed, dropped bigint, pk(broadcast_id, channel))` — 보낼 때 고른 채널마다 한 줄, 고리가 `+1` 갱신(원자 증가).
**받는 사람 데이터가 없다**(수만) — 계정 지우기 대상이 아니다. 프로젝트 모드면 프로젝트 스키마마다.

**`notification-jdbc` 새 V 파일**: `notification_inbox(event_id)` 색인(열람 통계 · 줄 많은 표면 한가한 때 배포 — 기존 문서 문장 재사용). 그리고 **`save` 가 줄마다 `recipient_ids_json` 에 그 받는 사람 한 명만 적는다** — 지금은 이벤트의 받는 사람 전체를 줄마다 복제한다(200명 묶음 × 10만 줄이면 수 GB, 남의 id 도 남는다). 옛 줄은 그대로.

## 4. 흐름

```
DRAFT ──보내기──▶ SENDING ──묶음 없음──▶ SENT
  │  ╲──예약──▶ SCHEDULED ──시각──▶ SENDING
  │              │ 예약 풀기 → DRAFT          SCHEDULED|SENDING ──취소──▶ CANCELLED
  └ 고치기 · 지우기는 DRAFT 만               SENDING ──작업 DEAD──▶ FAILED ──이어 보내기──▶ SENDING
```

**보내기 / 예약** (`POST …/{id}/send {scheduledAt?}`): 한 트랜잭션 — `update … set status, run_token = 새 값, next_seq = 0, estimate = count(...) where id = ? and status = 'DRAFT'`(0줄이면 409) → 채널 통계 줄 → 첫 단계 작업 `notification.broadcast.step {id, token, seq: 0}`(`runAt = scheduledAt`) → `OperatorAuditLog.record`.
**단계 작업**(처리기는 트랜잭션 밖 → 스스로 연다):
1. 공지 줄을 잠근다(`for update`). 상태가 `SCHEDULED`/`SENDING` 이 아니거나 `token`/`seq` 가 줄과 다르면 **아무것도 안 하고 끝**(취소 · 중복 · 옛 예약).
2. `ids = source.page(param, after = cursor_id, limit = batch-size)` — `> cursor_id` 아닌 것 · 겹침은 버린다. 비었으면 `SENT` · `finished_at` · 커서 지움, 끝.
3. 광고 종류면 받는 사람마다 `AdvertisingGate.decide` — `Skip`(동의 없음)은 이 공지에서 통째로 뺀다(알림함 · 푸시 모두 없음). 정보성 종류는 이 단계를 건너뛰어 전원이 남는다. 제목 · 본문은 `gate.brand`(알림함에도 `(광고)`, 멱등이라 푸시에서 두 번 붙지 않는다).
4. `publish(NotificationEvent(id = 공지 id, topic = 설정 topic, type, recipientIds = ids, title, message, link, channels, severity = INFO, payload = {broadcastId}))` — 알림함 저장은 이 트랜잭션 안.
5. `cursor_id = ids.last`, `next_seq + 1`, `recipients += ids.size`, `started_at`(처음이면), 다음 작업(`seq + 1`, `runAt = now + 간격`)을 **같은 트랜잭션에** 넣는다. 커밋.
6. 커밋 뒤 브로커가 채널(푸시 등)에 넘긴다 → 기기마다 푸시 작업(기존). 커밋과 넣기 사이에 죽으면 그 묶음의 푸시만 잃는다(앞 설계와 같은 최선 노력 — 알림함이 원본).

간격 = `batch-size / recipients-per-minute` 분(기본 200 / 6000 = 2초). 큐의 재시도(`step-max-attempts` 기본 5, 큐 백오프)를 다 쓰면 DEAD 고리가 `FAILED` + `failure_reason`. **이어 보내기**(`FAILED` 만): 새 `run_token`, `seq = next_seq`, 같은 커서부터. 대상은 진행하면서 읽는다(키셋) — 보내는 중 가입한 사람이 뒤쪽 id 면 포함될 수 있다(Braze 와 같은 「발송 시점 평가」, 문서 한 줄).
**취소**: `update … set CANCELLED where status in (SCHEDULED, SENDING)` → 다음 단계는 1 에서 끝. 큐의 푸시는 처리기가 `halted(bc_…)` 를 물어 DROPPED(공지 상태를 5초 캐시 — 취소 뒤 최대 5초 안 나간 것은 나갈 수 있다, 문서 한 줄).
**테스트 발송** `POST …/{id}/test`: 상태 무관 · 지금 내용으로 `publish(id = bct_…, recipientIds = {나}, 제목 앞 test-title-prefix, channels 그대로)` — 고리가 `bc_` 가 아니라 통계에 안 셈. 광고 종류면 내 동의에 따라 푸시가 안 올 수 있다(화면이 알린다). 운영자당 `test-cooldown`(30초) — 429 `BROADCAST.TEST_TOO_SOON`.
**통계**: `GET …/{id}` 때 계산 — `estimate` · `recipients` · `inbox: eventStats(id)`(없으면 null) · 채널 줄(푸시는 `opened` 포함 — §2-B). 알림함 보관 정리(`retention`)를 켠 앱은 그 기간 뒤 열람 수가 줄어든다(문서 한 줄, 굳히기는 다음).

## 5. API (`/api/v1/admin/broadcasts` — `skeleton.notification.broadcast.admin.enabled` 기본 꺼짐 → 404, 서블릿 + Security, 권한은 `OperatorAccess`)

| 경로 | 권한 | 하는 일 |
|---|---|---|
| `GET /options` | read | `{ types: [{ type, advertising, push }], channels: ["push", …], audiences: [{ kind, param }] }` — `push` = 그 종류가 푸시로 갈 수 있나(앱의 `push.types` · 광고 종류), 화면이 칸을 잠근다 |
| `GET /audience-count?kind&param` | read | `{ count }` (미리보기) |
| `GET /` `?status&before&limit` | read | 최근 순 목록(키셋), 줄마다 진행(`recipients` / `estimate`) |
| `POST /` | send | `{ type, title, body?, link?, channels[], audience{kind, param?} }` → DRAFT |
| `GET /{id}` | read | 상세 + 통계 `{ estimate, recipients, inbox: { stored, read } \| null, channels: { push: { queued, delivered, failed, dropped, opened } } }` |
| `PUT /{id}` · `DELETE /{id}` | send | DRAFT 만(아니면 409 `BROADCAST.INVALID_STATE`) |
| `POST /{id}/test` | send | 202 |
| `POST /{id}/send` | send | `{ scheduledAt?: ISO-8601 }` — 지금 ~ +`max-schedule-ahead`(90일) 밖이면 400 `BROADCAST.INVALID_SCHEDULE` |
| `POST /{id}/unschedule` · `/cancel` · `/resume` | send | SCHEDULED → DRAFT · (SCHEDULED\|SENDING) → CANCELLED · FAILED → SENDING |

오류: `BROADCAST.NOT_FOUND` 404 · `INVALID_STATE` 409 · `UNKNOWN_TYPE` · `UNKNOWN_CHANNEL` · `UNKNOWN_AUDIENCE` · `INVALID_AUDIENCE`(`detail.reason` = 소스의 `validate`) · `INVALID_LINK` · `INVALID_SCHEDULE` 400 · `TEST_TOO_SOON` 429. 프런트 `errorCodes.ts` 에도. 성공한 보내기 · 예약 · 취소 · 이어 보내기는 `OperatorAuditLog.record("notification-broadcast.notice.<sent|scheduled|cancelled|resumed>", target = id)`.

## 6. 설정 · 기동 검사

```yaml
skeleton.notification.broadcast:
  enabled: false                 # 모듈 기본 꺼짐
  types: []                      # 운영자가 고를 알림 종류(예: notice.service · promo.event). 켰는데 비면 기동 실패
  topic: broadcast               # 알림함 topic (화면의 「공지」 탭 거르기에 쓴다)
  batch-size: 200
  recipients-per-minute: 6000
  step-max-attempts: 5
  max-schedule-ahead: P90D
  test-title-prefix: "[테스트] "
  test-cooldown: 30s
  admin: { enabled: false, base-path: /api/v1/admin/broadcasts }
```
기동 실패: 켰는데 `BroadcastStore`/`BroadcastTasks` 빈 없음(「`notification-broadcast-jdbc` 를 얹어라」) · `types` 빈 목록 · `AudienceSource` 빈 하나도 없음(「`account` 를 얹거나 대상 빈을 만들어라」) · `kind` 겹침 · `batch-size` 1~1000 밖. 공지 종류를 끌 수 있게 할지(`preferences.types`)와 푸시로 보낼지(`push.types`)는 기존 설정 그대로 앱이 정한다(서비스 필수 공지는 끌 수 없게 두라고 문서 한 줄).

## 7. 화면 (react `@skeleton/notifications/admin`, 패키지 1.3.0)

- `createBroadcastAdminApi(client, { basePath })` · 훅(`useBroadcasts` · `useBroadcast`(SENDING 이면 10초마다 다시 읽음) · 변경 훅들) — `rewards/admin` 과 같은 꼴(`can` · `labels` ko/en · `locale`).
- `<AdminBroadcasts>` — 목록(상태 칩 · 종류 · 대상 · 예약/보낸 시각 · 진행 막대) + 「새 공지」 단추.
- `<AdminBroadcastEditor>` — 종류(광고면 「광고성 — 수신 동의한 사람에게만, 제목에 (광고)」 안내) · 제목 · 본문(글자 수) · 링크(앱 안 경로) · 대상(종류 고르기 → 역할은 `roleChoices` prop, 워크스페이스는 `workspaceChoices` prop 또는 id 입력, 앱 세그먼트는 `TEXT`) + 「대상 n명」 미리보기 · 채널(알림함 잠긴 체크 + 푸시, 종류가 푸시 불가면 잠김) · 즉시 / 예약(브라우저 시간대 표시, 광고 + 21~08시면 「야간 동의 없는 사람은 08시 이후 받는다」) · 「나에게 테스트」 · 「보내기」 → **확인 대화상자(대상 n명 · 채널 · 시각을 다시 보여 준다)**.
- `<AdminBroadcastDetail>` — 통계 카드(예상 대상 · 넘긴 사람 · 알림함 저장 · 알림함 읽음(%) · 푸시 넣음/성공/실패/보내지 않음/클릭) · 상태별 단추(예약 풀기 · 취소(확인) · 이어 보내기) · 누가 · 언제. 단추는 `can('notification-broadcast.send')` 로 숨긴다(서버가 다시 판정).
- 받는 사람 쪽 화면은 바뀌지 않는다 — 공지는 기존 `NotificationList` · 벨에 그대로 나온다.
- 스토리(`play`): 목록(빈 · 여럿) · 편집(광고 종류 · 푸시 불가 종류 · 예약 야간 경고) · 상세(보내는 중 · 끝 · 실패 · 취소). 눌리는 것은 `Button`.

## 8. 견본 조립

**kotlin `apps/sample`**: 의존 둘 + yml: `broadcast.enabled: true` · `types: [notice.service, promo.notes-tips]`(뒤의 것은 이미 광고 종류) · `admin.enabled: true` · `push.types` 에 `notice.service` 추가. 견본 권한 매핑은 전권만(기본). `apps/workbench` 는 의존 한 줄씩(꺼짐 — 공존 증명).
**react `apps/sample`**: `/admin/broadcasts` · `/admin/broadcasts/new` · `/admin/broadcasts/:id`(`RequirePermission notification-broadcast.read`), `AdminLinks` 한 줄, `roleChoices` 는 `GET /admin/roles`(권한 있을 때), 종류 이름 라벨 ko/en.

## 9. 검증 (깨지면 사고 나는 것만)

**kotlin**
- 중복(동시성): 같은 단계 작업을 두 번 · 동시에 → 알림함 한 줄 · 푸시 작업 한 벌 · `recipients` 한 번 · 보내기 동시 두 번 → 하나만 시작 · 예약 풀고 다시 예약 → 옛 작업은 아무것도 안 함 · 통계 증가가 동시에 와도 잃지 않는다.
- 취소: 취소 뒤 다음 묶음 없음 · 큐에 있던 푸시(야간 보류 포함)가 `PushSender` 에 닿지 않는다.
- 데이터 손실: 묶음 중간 DEAD → `FAILED` → 이어 보내기 → 대상 전원이 정확히 한 번(빠짐 · 중복 없음, 두 방언).
- 광고: 동의 없는 사람에게 알림함 · 푸시 모두 없음 · 알림함 제목에도 `(광고)` · 두 번 안 붙음. 정보성 공지는 동의 없는 사람에게도 알림함 + 푸시(푸시를 끈 사람만 푸시 없음).
- 푸시 클릭 열람(§2-B, 보안 · 집계 정합 회귀 하나): 같은 클릭 보고를 두 번 → `opened` 1 · 남의 토큰 · 위조 토큰(서명 틀림 · 다른 공지 id · 주인이 바뀐 기기) → 400 이고 세지 않는다.
- 보안: `.read` 만으로 보내기 · 테스트 · 취소 403 · 권한 없음 403 · 잘못된 링크 400 · `admin.enabled=false` 면 404.
- 채널 표지: `channels = []` 면 푸시 작업 없음 · `null` 이면 지금과 같다(기존 시험이 증명).
- 저장: 묶음 공지의 알림함 줄마다 `recipient_ids_json` 이 한 명.
- 대상: `all` · `role` · `workspace` 키셋이 정지 · 대기 · 지워진 계정 · 떠난 멤버를 내지 않고 경계에서 빠짐 · 겹침 없음(두 방언 `dbTest`).
- 조립: 꺼짐 = 이 모듈 없는 앱과 같다 · 켰는데 `-jdbc` · 종류 · 대상 없음 → 기동 실패 메시지 · `MigrationLockTest` · `CapabilitiesCatalogTest` · `ModuleDocumentationTest` · `ModuleConfigSnippetsTest`.
- 견본 통합 시험 하나: 시드 3명 → 공지 보내기(작업을 손으로 돌림) → 알림함 3줄 · 가짜 보내기(`Recorder`)가 기기 수만큼 · 한 명이 읽음 → 통계 `read = 1` · 클릭 보고 한 번 → `opened = 1`.

**react**: api 경로 기록 · 확인 대화상자 없이는 보내지 않는다 · 푸시 불가 종류면 푸시 칸 잠김 · `can` 없으면 단추 없음 · 스토리 `play` · `capabilities` 검사.
**e2e 한 여정**(react sample + kotlin sample): admin 로그인 → 새 공지(역할 `USER`, 알림함만) → 나에게 테스트(벨에 `[테스트]`) → 보내기 확인 → 상세가 `SENT` → user 로그인 → 벨에 공지 → 읽음 → admin 상세 열람 1. 푸시 경로(클릭 보고 포함)는 kotlin 통합 시험 + 앞 설계의 e2e 가 맡는다. react 는 서비스 워커 `notificationclick` 이 보고를 보내는지(토큰이 있을 때만 · 실패해도 창은 연다)를 단위 시험 하나로. qa 캡처는 화면당 2장까지.

## 10. 내가 정한 것 (관행대로 — 바꾸려면 말해 줘)

1. 대량 발송은 새 전달 경로가 아니라 **기존 `publish` 를 묶음마다** 부른다 — 끄기 · 푸시 · 광고 게이트 · SSE 가 그대로 따라온다. FCM 주제 발송은 안 쓴다.
2. 알림함은 쓰기 펼침(사람마다 줄). 공지 id = 이벤트 id 라 재저장이 멱등이다.
3. 몸체는 `job-queue-jdbc` 이어 달리기(한 작업 = 한 묶음, 다음 작업을 같은 트랜잭션에), 속도는 묶음 간격. 자체 스케줄러 · 폴러를 만들지 않는다.
4. 대상은 발송하면서 읽는다(키셋, 미리 굳히지 않음) — 10만 줄 스냅숏 표가 없다. 예상 수는 시작 때 한 번 센다.
5. 대상 계약은 `platform` 에(`RoleHolders` 선례), 기본 셋은 `account`(전체 · 역할) · `workspace`. 활성 계정 · 활성 멤버만.
6. 채널 고르기는 이벤트의 `channels` 표지(좁히기만). 알림함은 늘 — 통계와 기록의 원본이라.
7. 통계는 코어의 전달 고리로 받는다(공지가 푸시를 모르게). 열람 = 알림함 읽음. 숫자는 공지 줄 · 채널 줄의 원자 증가와 알림함 셈.
8. 취소는 「더 보내지 않음」(큐의 푸시 포함, 5초 캐시). 이미 저장된 알림함 줄은 남긴다(회수는 다음).
9. 중간 실패는 `FAILED` 로 멈추고 운영자가 「이어 보내기」 — 자동으로 끝없이 다시 돌지 않는다.
10. 공지는 늘 `INFO` — 슬랙 운영 전달로 새지 않는다. 중요도 선택은 다음.
11. 권한 둘(read · send), 기본 역할 없음. 운영 기록은 공지 줄의 칸 + `OperatorAuditLog` 호출(권한 설계의 「자리」).
12. `notification-jdbc` 의 `recipient_ids_json` 을 줄마다 한 명으로 고친다(대량에서 저장 폭증 · 남의 id 를 막는다). 옛 줄은 안 옮긴다.
13. 예약은 한 시각(서버는 UTC 시각만 받는다), 최대 90일. 받는 사람 현지 시각 발송은 다음.
14. 테스트 발송은 나에게만 · `[테스트]` · 통계 밖 · 30초 간격.
15. 광고성 공지는 동의 없는 사람을 통째로 뺀다(알림함 · 푸시), 정보성은 전원(푸시 끄기만 존중). 혼자 보냄 · 서비스 운영자만 — 앞 단계의 결정.
16. 푸시 클릭 열람은 서명 토큰(`deviceId.mac`) + 인증 없는 보고 경로 하나 + `push_opens` 유니크 + 고리 `onOpened`. 알림함 읽음과 합치지 않고 `push.opened` 로 따로 센다.

## 11. 작업 계획 (이 절이 계획이다 — 따로 계획 파일을 만들지 않는다)

| 키 | 레포 | 조각 | 선행 | 크기 | 예상 |
|---|---|---|---|---|---|
| K1 | kotlin | 알림 코어 자리(§2 의 1~4, 고리 `onOpened` 포함) + `notification-jdbc`(V `event_id` 색인 · `eventStats` · `recipient_ids_json` 한 명) + 푸시 쪽(채널 표지 · `push` 선언 · `onQueued` · `halted` · `onOutcome` · `-jdbc` DEAD 고리) + **푸시 클릭 열람 서버 경로(§2-B: 토큰 · 페이로드 `open` · `POST /api/v1/push/opened` · `push_opens` · `onOpened`)** + 문서 · 시험 | push 합친 뒤 | 중 | 5h |
| K2 | kotlin | `platform` `AudienceSource` + `account`/`-jdbc`(`all` · `role` 키셋 · 두 방언 `dbTest`) + `workspace`/`-jdbc`(`workspace`) + 문서 | — (K1 과 나란히) | 중 | 2.5h |
| K3 | kotlin | `notification-broadcast` + `-jdbc`: 모델 · 상태 전이 · 단계 작업 · 간격 · 취소 · 이어 보내기 · 테스트 발송 · 고리(통계 · `halted` · `onOpened` → `opened` 증가 · 상세 응답에 합침) · HTTP §5 · 권한 선언 · 운영 기록 · 설정 · 기동 검사 · V 파일 두 방언 · 모듈 문서 2 · 설정 스니펫 · 카탈로그 · 회귀 시험 §9 | K1 · K2 계약 | 대 | 6.5h |
| K4 | kotlin | 견본 · workbench 조립 · yml · 견본 통합 시험 · CHANGELOG · 「따라가며 더할 것」 | K3 | 소 | 1.5h |
| R1 | react | `@skeleton/notifications/admin`: api · 훅 · 목록 · 편집 · 상세(클릭 칸) · 확인 대화상자 · 오류 코드 · 라벨 · 스토리 · README · 카탈로그 · CHANGELOG + **`@skeleton/push` 서비스 워커 `notificationclick` 보고(`open` 이 있을 때만 `keepalive` fetch, `data` 에 `id` · `open`) + 단위 시험** | §5 표(가짜 API) — K1 과 나란히 | 대 | 5.5h |
| R2 | react | 견본 라우트 · 메뉴 · 라벨 + e2e 한 여정 | R1 · K4 합친 뒤 | 중 | 2.5h |

- 묶음: ① K1 ∥ K2 ∥ R1 → ② K3 → ③ K4 → R2 → 끝 검사(지휘: kotlin 바뀐 모듈만 한 번 · react 전체 + `e2e:sample` 한 번, 리뷰 kotlin 1 · react 1, qa 1). 동시 최대 3.
- developer 6 + 리뷰 2 + qa 1 = 에이전트 9. 벽시계 대략 16h(K1 5h → K3 6.5h → K4 1.5h → R2 2.5h 가 긴 줄).
- 같이 만지는 파일: `settings.gradle.kts` · `capabilities.json` · `docs/modules/README.md` 는 K3 만(K1 · K2 는 기존 모듈 문서의 자기 줄만), 견본 yml 은 K4 만. `notification-push` 는 K1 만 만진다. 서비스 워커(`packages/push/src/serviceWorker.ts`)는 R1 만 만진다 — 보고 계약은 §2-B, 서버 구현 전에는 가짜 API 로 시험.
- 계약이 바뀌면 kotlin 조각이 보고에 적고 지휘가 R 조각에 넘긴다. 스펙과 계획이 어긋나면 스펙이 이긴다.

## 정해야 할 것

결정됨 참조 — 네 가지 모두 아래 「결정됨」으로 닫혔고 본문에 반영했다. 남은 질문은 없다.

## 결정됨 (2026-11-02)

형이 골랐다. 이 절이 본문보다 우선한다.

1. 광고성 공지 — 형: "광고는 빼주는거고. 나머지 중요 공지는 보내야겠지?" → **A**: 광고성 공지는 광고 수신 동의가 없는 사람을 통째로 뺀다(알림함 · 푸시 모두 없음). 정보성(중요) 공지는 동의와 무관하게 대상 전원에게 간다(알림함 + 푸시, 푸시 끄기 설정은 존중).
2. 보내기 승인 — **A**: send 권한 하나로 혼자 보낸다 + 나에게 테스트 발송 + 대상 수 확인 대화상자.
3. 열람 통계 — **B: 푸시 클릭까지**. 알림함 읽음 + 푸시를 눌러 연 것(서비스 워커 · 앱이 「열었다」를 보고하는 경로, 푸시 계약에 공지 id · 추적 키 추가). 중복 보고는 한 번으로 센다.
4. 팀 관리자 공지 — **A**: 1차는 서비스 운영자만.
