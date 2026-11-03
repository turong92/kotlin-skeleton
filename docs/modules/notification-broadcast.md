# notification-broadcast

공지 발송 — 서비스 운영자가 **전체 또는 대상(역할 · 워크스페이스 · 앱이 만든 세그먼트)** 에게 공지를 보낸다. 알림함에는 늘 들어가고, 푸시는 고른 공지만 간다. 저장은 어댑터 `notification-broadcast-jdbc`, 운영자 화면은 `@skeleton/notifications/admin`.
대량 발송을 새 전달 길로 만들지 않는다 — **받는 사람 묶음마다 기존 `NotificationPublisher.publish` 를 부른다**(끄기 · 「푸시만 끄기」 · 광고 게이트 · 기기 작업 · 재시도가 그대로 따라온다). FCM 주제 발송은 쓰지 않는다.

**흐름** `DRAFT → (보내기) SENDING | (예약) SCHEDULED → SENT`, 취소 `CANCELLED`, 중간에 큐 작업이 죽으면 `FAILED`(→ 이어 보내기로 `SENDING`). 고치기 · 지우기는 DRAFT 만. 상태를 바꾸는 일은 모두 조건부 갱신이라 두 번 누름 · 동시 요청에서 하나만 이긴다(나머지 409).
**단계 작업** `notification.broadcast.step {id, token, seq}` 하나 = 대상 한 묶음(`batch-size`). 한 트랜잭션 안에서 공지 줄을 잠그고 → 토큰 · 순번이 줄과 같을 때만(취소 · 중복 · 옛 예약은 아무것도 안 함) → 대상(`AudienceSource`)을 키셋으로 한 쪽 읽고 → 광고 종류면 동의 없는 사람을 뺀 뒤 → `publish`(알림함 저장) → 커서 · 순번 · 수를 올리고 → 다음 작업(`묶음 수 / recipients-per-minute` 간격)을 같은 트랜잭션에 넣는다. 푸시는 커밋 뒤 브로커가 넘긴다 - 커밋과 넣기 사이에 프로세스가 죽으면 그 묶음의 푸시만 잃는다(알림함이 원본). 공지 id(`bc_…`)가 이벤트 id 라 알림함 pk 가 재저장을 막는다.
**대상은 보내면서 읽는다**(미리 굳히지 않는다) — 보내는 중 가입한 사람이 뒤쪽 id 면 포함될 수 있다(「발송 시점 평가」). 예상 수는 보낼 때 한 번 센다.
**광고성 공지**(종류가 `skeleton.notification.advertising.types` 안): 광고 수신 동의가 없는 사람은 알림함 · 푸시 모두에서 **통째로 빠진다**(알림함 제목에도 `(광고)`). 정보성 공지는 동의와 무관하게 대상 전원 — 알림함 + 푸시(「푸시 끄기」 설정은 존중). 공지는 늘 `INFO` 라 기본 `minimum-severity: WARNING` 인 슬랙 전달로 새지 않는다.
**취소**: 더 보내지 않는다 — 다음 묶음이 없고 큐에서 기다리던 푸시(야간 보류 포함)도 처리기가 `halted` 를 물어 보내지 않는다(공지 상태를 5초 캐시 — 취소 뒤 그 안에 나간 것은 나갈 수 있다). 이미 알림함에 들어간 줄은 남는다.
**중간 실패**: 큐의 재시도(`step-max-attempts`)를 다 쓰면 `FAILED` + `failure_reason`(예외 종류 한 줄). 운영자가 이어 보내면 새 토큰으로 같은 커서부터 — 대상 전원이 정확히 한 번.
**테스트 발송**: 나에게만, 제목 앞 `[테스트] `, 통계에 안 센다(id `bct_…`), 운영자당 30초에 한 번(429 `BROADCAST.TEST_TOO_SOON` — 인스턴스별).
**통계**: 채널의 전달 고리(`NotificationDeliveryHook`)로 받는다 — 채널별 넣음 · 성공 · 실패 · 보내지 않음 · 클릭(`opened`), 알림함 저장 · 읽음은 `eventStats`. 알림함 읽음과 푸시 클릭은 따로 센다. 알림함 보관 정리를 켠 앱은 그 기간 뒤 열람 수가 줄어든다.

**운영자 경로**(`skeleton.notification.broadcast.admin.enabled=true` 일 때만 — 꺼져 있으면 경로가 없어 404; 익명 401 · 권한 없음 403). 권한은 `notification-broadcast.read`(보기) · `notification-broadcast.send`(만들기 · 보내기 · 취소 …) — 기본 역할 없음, 전권(`ADMIN`)만(역할 화면에서 나눠 준다).

| 메서드 · 경로 | 권한 | 하는 일 |
|---|---|---|
| `GET /options` | read | `{types:[{type,advertising,push}], channels:["push"…], audiences:[{kind,param NONE\|ROLE\|WORKSPACE\|TEXT}]}` — `push` = 그 종류가 푸시로 갈 수 있나(앱의 `push.types` · 광고 종류) |
| `GET /audience-count?kind&param` | read | `{count}` 미리보기 |
| `GET /?status&before&limit` | read | 최근 순 목록(`values` 배열, `before` = 마지막 줄 id, 기본 20 · 최대 100) |
| `POST /` · `PUT /{id}` | send | `{type,title,body?,link?,channels[],audience{kind,param?}}` → 공지 줄(DRAFT). 알림함은 늘 가므로 `channels` 는 **알림함 말고 고른 채널** |
| `GET /{id}` | read | 공지 줄 + `stats: {inbox: {stored, read} \| null, channels: {push: {queued, delivered, failed, dropped, opened}}}`. 줄: `id status type title body link channels audience{kind,param} scheduledAt estimate recipients failureReason createdBy createdAt sentBy sendRequestedAt startedAt finishedAt cancelledBy cancelledAt` |
| `DELETE /{id}` | send | DRAFT 만 → 204 |
| `POST /{id}/test` | send | 202 |
| `POST /{id}/send {scheduledAt?}` | send | 204 — 지금, 또는 지금 ~ `max-schedule-ahead`(90일) 안의 예약(밖이면 400 `BROADCAST.INVALID_SCHEDULE`) |
| `POST /{id}/unschedule` · `/cancel` · `/resume` | send | 204 — SCHEDULED → DRAFT · (SCHEDULED\|SENDING) → CANCELLED · FAILED → SENDING |

오류: 404 `BROADCAST.NOT_FOUND` · 409 `INVALID_STATE` · 400 `UNKNOWN_TYPE` `UNKNOWN_CHANNEL` `UNKNOWN_AUDIENCE` `INVALID_AUDIENCE`(`data.reason`) `INVALID_LINK` `INVALID_SCHEDULE` · 429 `TEST_TOO_SOON`. 제목(1~100자) · 본문(2000자)은 `COMMON.VALIDATION_FAILED`. 성공한 보내기 · 예약 · 취소 · 이어 보내기는 같은 트랜잭션에서 `OperatorAuditLog` 에 `notification-broadcast.notice.sent|scheduled|cancelled|resumed`(대상 = 공지 id)를 남기고, 공지 줄에도 누가 · 언제가 남는다.

**켜는 법**: `notification-broadcast-jdbc` 를 얹고(`job-queue-jdbc` 켜짐 · 두 마이그레이션 적용), `skeleton.notification.broadcast.enabled=true` + `types`. 대상은 `AudienceSource` 빈 — `account`(`all` · `role`) · `workspace`(`workspace`)가 낸다. 앱은 세그먼트(「지난달 결제한 사람」)를 빈 하나로 더한다. 채널은 `NotificationChannelDeclaration` 빈(푸시가 `push` 를 낸다). 메일 같은 채널을 붙이는 앱은 선언 하나 + `NotificationEvent.channels` 를 지키는 구독자 하나. 푸시로 보내려면 `notification-push` 를 켜고 `push.types` 에 그 종류를 넣는다(서비스 필수 공지는 `notification.preferences.types` 에 넣지 않아 끌 수 없게 둔다).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-broadcast"))` |
| 함께 오는 모듈 | `notification`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.notification.broadcast` — [docs/config/modules/notification-broadcast.yml](../config/modules/notification-broadcast.yml) |
| 기본 동작 | 꺼짐. `skeleton.notification.broadcast.enabled=true` 와 `types` 를 앱이 정하면 선다. 운영자 경로는 `admin.enabled=true` 도 있어야 열린다(꺼져 있으면 404). |
| 부팅에 필요한 것 | 켰을 때: 저장소 · 작업 포트 `BroadcastStore` · `BroadcastTasks`(`notification-broadcast-jdbc` 가 낸다), 공지 종류 `types`, `AudienceSource` 빈 하나 이상(`kind` 겹침 없음), `batch-size` 1~1000. 없으면 무엇을 얹을지 말하고 기동에 실패한다. 운영자 경로는 `OperatorAccess`(`platform`)가 판정한다. |
| 교체 지점 | `BroadcastStore`, `BroadcastTasks`, `Broadcasts`, `BroadcastStepRunner`, `BroadcastDeliveryHook`, `BroadcastAdminController`, `broadcastPermissionDeclaration` |
| 마이그레이션 | 없음 (스키마는 `notification-broadcast-jdbc`) |
| 프론트 짝 | `@skeleton/notifications` |
| 테스트 | `modules/notification-broadcast/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
