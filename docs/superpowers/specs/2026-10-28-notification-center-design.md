# 알림센터 보강 — 설계

> 2026-10-28. 요청(형 요지): 다음 묶음은 「알림센터 보강」. 앞선 사용처가 자기 모듈에 먼저 만든 ① 같은 열쇠의 안 읽은 알림 묶기 ② 보관 기한 정리 ③ 주제 단위 지우기 ④ 안 읽은 수 전용 조회를 스켈레톤으로 되돌려 받고, 빈틈 중 범용인 것(클릭 이동 · 종류별 수신 끄기 · SSE 심장박동 등)도 넣는다. 원칙: 조립식 모듈 캡슐화, 기본값은 중립(앱이 켠다), 업계 관행.
> 대상: kotlin-skeleton(`notification` · `notification-jdbc` · `notification-sse` · `db-postgresql` · `db-mysql` · `reward` 한 줄 · `apps/sample`) + react-skeleton(`@skeleton/notifications` · `apps/sample`).
> 근거: 지금 코드(kotlin `a1da77ca` — `NotificationInbox.kt` · `InMemoryNotificationBroker.kt` · `JdbcNotificationInboxRepository.kt` · `NotificationSseService.kt`, react `891f7ac` — `packages/notifications`), 앞선 사용처의 구현(받은편지함 계약에 `saveCollapsing` · `deleteOlderThan` · `deleteByTopic` · `deleteByRecipientAndTopic` · `countUnread` + `GET …/unread-count`), 이벤트 · 보상 설계 §4.6(보상 알림이 기대는 빈틈 ⑤ ⑥ ⑦).
> 상태: 형 결정 2026-10-28(아래 표) — 확정.

## 형 결정 (2026-10-28)

| # | 물음 | 결정 (원문) | 반영 |
|---|---|---|---|
| 1 | 묶음 열쇠 | collapseKey + collapsedCount | §4.1 · §4.2 |
| 2 | 보관 정리 | 기본 꺼짐, 켜면 max-age 기본 90일. 「스켈레톤 기본은 중립, 앱이 override」 | §4.3 · §7 |
| 3 | 커밋 뒤 전달 | 구독자 전달만 커밋 뒤, 저장은 호출자 트랜잭션 안 | §4.4 |
| 4 | SSE 심장박동 | 기본 꺼짐, 견본 앱이 15초로 켠다 | §4.6 · §7 |
| 5 | link | 앱 안 상대 경로만, '/' 로 시작하고 '//' 는 금지 | §4.1 |
| 6 | 수신 끄기 | type 단위 + 패키지 NotificationPreferences 부품 + 견본 설정 화면 | §4.5 · §5 · §7 |
| 7 | 조각 | 비용을 줄이려고 SSE 심장박동은 끄기 조각에 합친다 — 조각 3개 | §11 |

## 1. 비목표 (먼저)

- **여러 서버 SSE 릴레이 · 연결 상한 · 연결 재검증 · 「힌트만 보내기」.** 릴레이는 저장소(Redis · DB) 선택이 걸린 큰 일, 재검증은 앱의 권한 모델 몫이다 — 다음에.
- **채널 선택(메일 · 슬랙 · 브라우저 푸시)과 채널별 끄기.** 이번 끄기는 받은편지함 + 실시간 전달 한 길만.
- **서버 번역 · 템플릿 엔진.** 서버는 지금처럼 `title` · `message`(선택)와 `type` + `payload` 를 저장하고, 웹이 그릴 자리만 연다.
- **받은편지함 저장 실패를 호출자에서 떼어 내기.** 저장은 호출자 트랜잭션 안에 남는다(§4.4).
- **웹 패키지 부품을 다른 디자인 시스템에 맞추기.** 날 `<button>` 은 패키지 안이라 lint 대상 밖이고, 다른 부품을 쓰는 앱은 로직(API · 훅 · 캐시)만 가져다 다시 짓는다.
- 게시판 알림의 묶기 · 링크(게시판은 앱 경로를 모른다 — 앱이 알림기를 바꿔 끼운다).

## 2. 빈틈 11개 — 넣음 / 안 넣음

| # | 빈틈 | 결정 | 이유 · 모양 |
|---|---|---|---|
| ① | 같은 열쇠 안 읽은 알림 묶기 | **넣음** | 이벤트에 `collapseKey`(FCM `collapse_key` · APNs `collapse-id` 관행). 안 읽은 같은 열쇠 줄에 접고 `collapsedCount` +1. DB 유니크 색인으로 원자적(§4.2) |
| ② | 보관 기한 정리 | **넣음** | `deleteOlderThan(cutoff)` + 선택 정리 작업(기본 꺼짐 — `job-queue-jdbc` retention 선례) |
| ③ | 주제 단위 지우기 | **넣음** | `deleteByTopic` · `deleteByRecipientAndTopic` — 서버 API 만(HTTP 없음) |
| ④ | 안 읽은 수 전용 조회 | **넣음** | `countUnread` + `GET /api/v1/notifications/unread-count`. 웹 안 읽은 수가 이것을 쓴다 |
| ⑤ | 받는 쪽 번역 렌더 | **넣음(웹만)** | `NotificationList` · `NotificationBell` 에 `describe(item)` 자리. 없으면 지금처럼 `title ?? type` |
| ⑥ | 클릭 이동(링크) | **넣음** | 이벤트에 `link`(앱 안 상대 경로). 줄을 누르면 읽음 + 앱이 준 `onOpen` 으로 이동 |
| ⑦ | 커밋 뒤 전달 · 저장 실패 전파 | **일부** | 구독자 전달(SSE · WebSocket · Slack)은 **트랜잭션이 있으면 커밋 뒤**(롤백된 알림이 새지 않게). 저장 실패 격리는 안 넣음(§4.4) |
| ⑧ | SSE 심장박동 · 상한 · 재검증 · 릴레이 · 힌트만 | **일부** | 심장박동만(설정, 기본 꺼짐 — 짝 클라이언트 `idleTimeoutMs` 도 기본 꺼짐). 나머지는 비목표 |
| ⑨ | `notification-jdbc` 의 `json` 의존 | **안 넣음** | `json` 은 스켈레톤 표준 모듈이다. 앞선 사용처만의 사정 |
| ⑩ | 웹 패키지의 날 `<button>` · `<dialog>` | **안 넣음** | lint 는 `apps/**` 만 막는다(`<dialog>` 는 이미 `@skeleton/ui` `Dialog`). 다른 디자인 시스템 앱은 부품을 다시 짓는다 |
| ⑪ | 수신 설정(종류별 끄기) | **넣음** | 사람 × `type` 끄기. **끌 수 있는 종류는 앱이 설정으로 정한다**(기본 빈 목록 = 끌 수 있는 것 없음). 받은편지함 + 실시간만 |

앞선 사용처 모양과 다른 점: 묶음 열쇠를 `(topic, type)` 고정 + payload 의 `countKey` 합산 대신 **이벤트가 정하는 `collapseKey` + 수 칸**으로 일반화했다(게시판 댓글처럼 같은 `(topic, type)` 이 글마다 달라야 하는 경우를 덮는다). 앞선 사용처의 열쇠는 `collapseKey = "$topic:$type"` 로 옮겨진다.

## 3. 흐름

```
앱 코드 → NotificationPublisher.publish(event{…, link?, collapseKey?})
  broker: 받는 사람 = resolver(event) − 끈 사람(type 이 끌 수 있는 종류일 때)   (남은 사람이 없으면 끝)
          inbox.save(event, 받는 사람)          ← 호출자 트랜잭션 안. collapseKey 면 안 읽은 같은 열쇠 줄에 접는다
          구독자 전달                            ← 트랜잭션이 있으면 afterCommit, 없으면 바로
웹 종 → GET …/unread-count → 배지 / 실시간 이벤트 → ingest(collapseKey 면 수를 다시 읽는다)
목록 줄 클릭 → PATCH …/{eventId}/read + onOpen(item) → 앱 라우터가 item.link 로
설정 화면 → GET/PUT …/preferences
정리 작업(켰을 때) → interval 마다 deleteOlderThan(now − max-age)
```

## 4. 서버 설계

### 4.1 이벤트 (`notification`)
`NotificationEvent` 에 두 칸(기본 null — 지금 발행 코드는 그대로 돈다):
- `link: String?` — `/` 로 시작하고 `//` 로 시작하지 않는 앱 안 경로, 1000자 이하(`init` 에서 거절). 열린 리다이렉트 막이.
- `collapseKey: String?` — 비어 있지 않은 글자, 255자 이하.

`NotificationInboxRecord` 에 `collapsedCount: Int = 1`.

### 4.2 받은편지함 저장소 계약 (`NotificationInboxRepository`)
- `save(event, recipientIds)` — 시그니처 그대로. `collapseKey` 가 있으면 받는 사람마다 **안 읽은 `(recipient, collapseKey)` 줄**에 접는다: 내용(`type` · `severity` · `title` · `message` · `payload` · `link`)은 새 것으로, `event_created_at` 은 새 시각, `collapsedCount` +1, **`eventId` 는 처음 것 그대로**(읽음 처리 주소가 바뀌지 않게). 읽은 줄은 건드리지 않고 새 줄. 같은 이벤트 id 재발행은 지금처럼 한 번으로 친다(수가 오르지 않는다).
- `countUnread(recipientId): Long` — 기본 구현 있음(`findByRecipient(unreadOnly, size=1).totalElements`)이라 앱이 만든 저장소는 깨지지 않는다.
- `deleteOlderThan(cutoff): Int` — `event_created_at < cutoff`(읽음 무관), 내부에서 1000줄씩 나눠 지운다.
- `deleteByTopic(topic): Int` · `deleteByRecipientAndTopic(recipientId, topic): Int`.
- 지우기 셋은 추상 — 직접 구현한 저장소는 깨진다(CHANGELOG Breaking 한 줄). 인메모리 · Noop · JDBC 셋 다 구현한다.

**JDBC 묶음의 원자성** — 앞선 사용처는 「같은 열쇠 쓰기를 줄 세우는 것은 부르는 쪽 몫」이었다. 스켈레톤은 DB 가 막는다:
- 칸 `unread_collapse_key`(안 읽은 묶음 줄만 값, 읽으면 null) + 유니크 색인 `(recipient_id, unread_collapse_key)` — PG · MySQL 모두 null 은 겹쳐도 된다. 부분 색인 · 생성 칸 없이 두 방언이 같다.
- 저장 순서: ① `update … set …, collapsed_count = collapsed_count + 1 where recipient_id = ? and unread_collapse_key = ? and event_id <> ?` 가 한 줄이면 끝 ② 아니면 넣기(**어느 유니크 충돌이든 무시**) ③ `(recipient_id, event_id)` 줄이 있으면 끝, 없으면(같은 열쇠를 동시에 넣은 쪽에 졌다) ①을 한 번 더. 갱신 수(affected rows)에 기대지 않는다 — MySQL 의 found-rows 차이 때문.
- ②를 위해 `SqlDialect.insertIgnore` 가 `conflictColumns` 빈 목록을 「아무 유니크 충돌」로 받는다(PG `on conflict do nothing` — 대상 없는 꼴, MySQL 은 지금 꼴 그대로). PG 는 트랜잭션 안의 유니크 위반이 트랜잭션을 죽이므로 예외를 잡는 길은 쓰지 않는다.
- `markRead` · `markAllRead` 는 `unread_collapse_key = null` 도 같이 쓴다.

### 4.3 보관 정리 (`notification`)
`NotificationInboxRetention`(저장소 계약만 쓴다 — 인메모리에도 돈다), 설정 `skeleton.notification.retention`:
`enabled=false`(기본 — 지우는 것은 보관 정책이라 앱이 정한다) · `max-age=90d` · `interval=1h`. 켜면 `skeletonTaskScheduler`(scheduler 모듈)가 있을 때 `interval` 마다 돌고, 없으면 경고 로그 한 줄(board 공개 첨부 선례). 프로젝트 모드면 서비스 중인 프로젝트마다 `ProjectContext.runIn`(job-queue 선례). 여러 인스턴스가 같이 돌아도 같은 결과(멱등). 앱이 직접 부르려면 `run(now)`.

### 4.4 커밋 뒤 전달 (`InMemoryNotificationBroker`)
- 받은편지함 저장은 **호출자 트랜잭션 안에서 바로**(롤백되면 알림도 없다 — 지금과 같다, 실패는 호출자에게 던져진다).
- 구독자 전달은 `TransactionSynchronizationManager.isSynchronizationActive()` 면 `afterCommit` 에, 아니면 바로. `spring-tx` 는 `compileOnly` + 클래스 있음 확인(없으면 바로 전달). 커밋 뒤로 미뤄진 발행의 `deliveredSubscribers` 는 0(KDoc 에 적는다).
- 알림 실패가 업무를 실패시키면 안 되는 호출자는 커밋 뒤에 부르고 잡는다(보상 일꾼 선례) — 모듈 문서에 한 줄.

### 4.5 수신 끄기 (`notification` + `notification-jdbc`)
- 계약 `NotificationMuteRepository { mutedTypes(recipientId): Set<String>; mutedAmong(type, recipientIds): Set<String>; setMuted(recipientId, type, muted) }`. 기본 인메모리(프로젝트 키 포함), JDBC 는 표 `notification_mutes`(끈 것만 줄). 교체 지점.
- 설정 `skeleton.notification.preferences.types: []` — **끌 수 있는 종류 목록**. 비면 broker 는 끄기를 조회조차 하지 않고, 목록에서 빠진 종류의 끄기 줄은 무시된다(보안 · 결제 알림을 앱이 끌 수 없게 둔다).
- broker: 받는 사람에서 끈 사람을 뺀다. 이벤트의 `recipientIds` 가 비어 있지 않으면 전달하는 이벤트도 남은 사람으로 줄이고, **남은 사람이 없으면 저장도 전달도 하지 않는다**(빈 `recipientIds` 는 전체 공지라 그대로 넘기면 모두에게 간다). `recipientIds` 가 빈 전체 공지는 끌 수 없다.
- 계정 지우기(`NotificationInboxErasureListener`)가 `notification_mutes` 의 그 계정 줄도 지운다.

### 4.6 SSE 심장박동 (`notification-sse`)
`skeleton.notification.sse.heartbeat`(Duration, 기본 비움 = 꺼짐). 켜면 서비스가 데몬 스레드 하나로 열린 연결마다 `interval` 마다 SSE 주석(`:` 한 줄 — `EventSource` 와 `@skeleton/realtime` 파서가 사건으로 세지 않는다)을 보낸다. 보내기 실패는 그 연결만 닫는다. `stop()` 에서 스레드도 멈춘다. 관행 값 15초(Cloudflare 유휴 100초 · 흔한 프록시 60초보다 짧게) — 견본 앱이 켠다.

## 5. API 계약 (Kotlin ↔ React)

| 메서드 · 경로 (`/api/v1/notifications`) | 응답 | 상태 |
|---|---|---|
| `GET ?page&size&unreadOnly&topic` | 페이지 envelope, 줄 = `NotificationInboxItemResponse` + **`link: string \| null` · `collapsedCount: number`** | 칸 추가 |
| `GET /unread-count` | `{ value: { count: number } }` | **새** |
| `PATCH /{eventId}/read` · `PATCH /read-all` | 그대로 | — |
| `GET /preferences` | `{ value: { items: [{ type: string, muted: boolean }] } }` — 설정 목록 순서 그대로 | **새** |
| `PUT /preferences/{type}` 본문 `{ muted: boolean }` | `{ value: { type, muted } }`. 목록에 없는 종류 404 | **새** |
| SSE `GET /sse` 사건 `data` | `NotificationEvent` + **`link` · `collapseKey`**(null 가능). 심장박동은 주석 줄 | 칸 추가 |

모두 인증 필요 · 호출자 = `Authentication.name`(지금 받은편지함과 같다), `skeleton.notification.inbox.enabled=false` 면 새 경로도 함께 꺼진다.

`@skeleton/notifications`(1.1.0 → 1.2.0) 공개 표면에 더하는 것:
- 타입: `NotificationItem.link` · `.collapsedCount`, `NotificationEvent.link` · `.collapseKey`, `NotificationPreference = { type: string; muted: boolean }`.
- `NotificationsApi`: `unreadCount(): Promise<{ count: number }>` · `preferences(): Promise<{ items: NotificationPreference[] }>` · `setPreference(type, muted): Promise<NotificationPreference>`.
- `unreadCountQuery` 가 `unreadCount()` 를 쓴다(목록 `size=1` 우회 삭제). `notificationKeys.preferences()` · `preferencesQuery(api)` · `usePreferences(api)` · `useSetPreference(api)`(성공하면 캐시 고침).
- `ingest`: 이벤트에 `collapseKey` 가 있으면 안 읽은 수를 +1 하지 않고 다시 읽게 한다(접힌 알림은 수가 안 늘 수 있다), 목록은 지금처럼 무효화.
- `NotificationList` · `NotificationBell` props: `describe?: (item) => { title?: ReactNode; message?: ReactNode }`(⑤), `onOpen?: (item) => void`(⑥ — 있고 `item.link` 가 있으면 줄 제목이 `<a href={link}>`, 누르면 기본 이동을 막고 읽음 + `onOpen`; 종은 대화상자도 닫는다), `collapsedLabel?: (n) => string`(`collapsedCount > 1` 이면 `Badge` 로 수, 낭독 문구 기본 영어).
- 새 부품 `<NotificationPreferences api labels? title? />` — 서버 목록을 `SwitchRow` 로. `labels[type]` 없으면 `type` 그대로.

## 6. 마이그레이션 (`notification-jdbc`, 두 방언 짝 · 새 V 파일 · `migrations.lock`)

1. `V<UTC>__notification_inbox_link_collapse.sql` — `notification_inbox` 에 `link varchar(1000)` · `collapse_key varchar(255)` · `unread_collapse_key varchar(255)` · `collapsed_count int not null default 1`, 유니크 `uk_notification_inbox_unread_collapse (recipient_id, unread_collapse_key)`, 색인 `(event_created_at)`(보관 정리) · `(topic)`(주제 지우기). MySQL 색인 줄은 지금 파일처럼 jOOQ ignore 관례를 따른다.
2. `V<UTC>__notification_mutes.sql` — `notification_mutes(recipient_id varchar(128), type varchar(128), created_at, primary key (recipient_id, type))`.

옛 줄은 `collapsed_count = 1`, 나머지 null — 데이터 옮기기 없음. 견본 앱의 업그레이드 시험(`MigrationUpgradeIntegrationTest` · `MySqlMigrationUpgradeTest`)이 두 DB 에서 넘긴다.

## 7. 설정 (기본값 = 지금 동작)

```yaml
skeleton:
  notification:
    retention: { enabled: false, max-age: 90d, interval: 1h }
    preferences: { types: [] }        # 끌 수 있는 종류
    sse: { heartbeat: }               # 비움 = 꺼짐
```
견본 앱이 켜는 값: retention 켬(90d), heartbeat `15s`(웹 `idleTimeoutMs: 45_000`), preferences `[NOTE_UPDATED, comment.created, comment.replied, reward.granted]`, 노트 알림에 `link = /notes/{id}`(삭제 알림은 없음) · `NOTE_UPDATED` 에 `collapseKey = note-updated:{id}`. `reward` 의 기본 알림기는 `label.link` 를 `link` 칸에도 싣는다(payload 는 그대로).

## 8. 확장 지점 (여기 적은 것만 만든다)

- `NotificationEvent.collapseKey` · `.link` — 묶기 · 이동은 발행하는 쪽이 정한다. 모듈은 기본으로 묶지도 링크를 만들지도 않는다.
- `NotificationInboxRepository` 의 새 메서드 넷 — 앱의 지우기 연동(페이지 · 구성원 빠짐)과 다른 저장소 구현의 자리.
- `NotificationMuteRepository` 교체 · `skeleton.notification.preferences.types` — 끌 수 있는 종류는 제품이 정한다.
- `NotificationInboxRetention` 빈(교체 가능) · `run(now)`.
- 웹 `describe` · `onOpen` · `labels` — 문구 · 라우터는 앱 것.
- 만들지 않는 것: 채널 선택, 끄기 묶음(카테고리), 서버 템플릿, SSE 릴레이 · 상한, 알림 지우기 HTTP.

## 9. 검증

- 단위 · 웹 시험(조각마다 바뀐 범위만): 인메모리 묶기(읽은 뒤 새 줄 · 같은 id 재발행 · 프로젝트 분리), 끄기(목록 밖 종류 무시 · 모두 끈 경우 전달 없음 · 전체 공지 그대로), 커밋 뒤 전달(롤백 → 구독자 0, 커밋 → 1, 트랜잭션 밖 → 바로), unread-count · preferences 경로(401 · 404 · 순서), SSE 심장박동(꺼짐이면 주석 없음, 켜면 주석 · 실패한 연결 닫힘 · `stop` 뒤 스레드 없음).
- DB 시험(`postgresTest` · `mysqlTest`): 묶기 동시성(스레드 둘이 같은 열쇠 → 한 줄 · `collapsedCount` 2), 읽음 뒤 새 줄, `deleteOlderThan` 경계(같은 시각은 남김) · 1000줄 넘게, 주제 지우기 둘, 끄기 저장 · 계정 지우기.
- 웹: `api.unreadCount` 경로, `ingest` 의 묶음 분기, 링크 줄 마크업 · `describe`, `NotificationPreferences` 마크업, 스토리(링크 · 묶음 수 · 설정).
- e2e(지휘 · qa, 견본 앱): 로그인 → 노트 두 번 수정 → 종 배지 1 · 줄에 수 2 → 줄 누르면 `/notes/{id}` 이동 + 읽음 → 설정에서 「노트 수정」 끄기 → 다시 수정 → 배지 그대로 → 켜기. `curl -N` 으로 SSE 를 열어 15초 안에 `:` 줄. `GET …/unread-count` 가 배지와 같다.

## 10. 다음에 넓힐 것

SSE 여러 서버 릴레이 · 연결 상한 · 재검증 · 힌트만 보내기, 채널별 끄기(메일 · 푸시), 끄기 묶음(카테고리), 알림 한 줄 지우기 HTTP, 게시판 댓글 묶기(앱 선택), 패키지 부품의 날 요소 정리.

## 11. 조각 · 시간 (계획 `docs/superpowers/plans/2026-10-28-notification-center.md`)

| 키 | 레포 | 내용 | 앞선 조각 | 크기 |
|---|---|---|---|---|
| N1 | kotlin | 이벤트 칸 · 저장소 계약 넷 · 묶기(인메모리 · JDBC · 방언) · unread-count · 보관 정리 · 커밋 뒤 전달 · 마이그레이션 1 | — | 대(3~4시간) |
| N2 | kotlin | 수신 끄기(계약 · 인메모리 · JDBC · HTTP · 지우기) · 마이그레이션 2 · SSE 심장박동 · 보상 link · 견본 앱 · 문서 · 카탈로그 · CHANGELOG | N1 | 중대(3.5~4시간) |
| R1 | react | 패키지 전부 + 견본 앱(종 이동 · 설정 화면 · 유휴 감시) · 스토리 · README · 카탈로그 · CHANGELOG | — (§5 계약으로 가짜 API) | 중대(3~4시간) |

개발 조각 3 + 리뷰 2(kotlin · react) + qa 1 = 에이전트 6. 벽시계 약 7~8시간(N1→N2 가 긴 줄, R1 은 나란히).
