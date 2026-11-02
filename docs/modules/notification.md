# notification

제공자 중립 알림 계약(`NotificationBroker`, `NotificationRecipientResolver`, `NotificationInboxRepository`)과 기본 인메모리 브로커다.
전달 수단(SSE · WebSocket · Slack · 메일)과 영속(`notification-jdbc`)은 어댑터 모듈이 붙인다.

호출자의 받은편지함 HTTP 엔드포인트도 이 모듈이 연다 (서블릿 웹 앱 + Spring Security 가 있을 때, 인증 필요 · 기본 켜짐):
`GET /api/v1/notifications?page&size&unreadOnly&topic`(페이지 envelope — 줄마다 `link` · `collapsedCount`) · `GET /api/v1/notifications/unread-count`(`{ value: { count } }`) · `PATCH /api/v1/notifications/{eventId}/read` · `PATCH /api/v1/notifications/read-all` · `GET /api/v1/notifications/preferences` · `PUT /api/v1/notifications/preferences/{type}`(본문 `{ "muted": true }`, 목록 밖 종류는 404).
호출자는 `Authentication.name`(auth 모듈에서는 계정 id)이고 남의 알림은 404 다. 앱이 자기 컨트롤러를 두거나 끄려면 `skeleton.notification.inbox.enabled=false`.
알림을 보내는 쪽(`NotificationPublisher.publish`)은 앱 코드가 부른다 — 받는 사람은 `NotificationEvent.recipientIds`.

**클릭 이동 · 묶기**(둘 다 기본 null — 발행하는 쪽이 정하고, 모듈은 기본으로 묶지도 링크를 만들지도 않는다):
- `NotificationEvent.link` — 클릭하면 갈 **앱 안** 경로. `/` 로 시작하고 `//` 로 시작하지 않으며 1000자 이하(역슬래시 · 제어 문자도 거절, `.` · `..` 를 풀어 경로가 `//` 로 시작하게 되는 `/a/..//x` 꼴과 파싱이 안 되는 링크도 거절 — 열린 리다이렉트 막이).
- `NotificationEvent.collapseKey` — 받는 사람의 **안 읽은** 같은 열쇠 줄에 접는다: 내용은 새 것, `collapsedCount` +1, `eventId`(읽음 주소)는 처음 것 그대로. 읽은 줄은 건드리지 않고 새 줄. 같은 이벤트 id 재발행은 수가 오르지 않는다.
- 접힌 줄의 `eventId` 는 처음 이벤트 id 라, SSE 로 오는 사건의 `id` 와 목록 줄의 `eventId` 가 다를 수 있다 — 읽음 처리는 줄의 `eventId` 로 한다. 접힌 id 의 재발행(재시도)은 수도 줄도 바꾸지 않는다(DB 는 마지막 접힌 id 를 `last_event_id` 에 기억한다).
- `NotificationInboxRepository` 의 새 메서드: `countUnread`(기본 구현 있음) · `deleteOlderThan(cutoff)` · `deleteByTopic` · `deleteByRecipientAndTopic`(지우기 셋은 추상 — 저장소를 직접 구현했다면 구현해야 한다). 서버 API 만, HTTP 로는 열지 않는다.

**채널 좁히기 · 전달 고리**(대량 공지 같은 쓰임을 위한 자리, 모두 기본은 지금과 같다):
- `NotificationEvent.channels: Set<String>? = null` — null 이면 모든 채널, 집합이면 그 안의 채널만(**좁히기만**: 채널 자신의 종류 규칙은 그대로). 알림함 저장과 SSE 는 채널이 아니라 늘 간다. 지키는 채널은 푸시(`push`) — 다른 채널 모듈은 자기 구독자에서 지킨다.
- `NotificationChannelDeclaration(key)` 빈 — 붙은 채널 목록(채널 모듈이 올린다. 푸시가 `push`).
- `NotificationDeliveryHook`(빈 여럿, 모든 메서드 기본 no-op): `halted(eventId)`(보내기 직전 「멈췄나」) · `onQueued(eventId, channel, count)` · `onOutcome(eventId, channel, DELIVERED|FAILED|DROPPED)` · `onOpened(eventId, channel)`(받는 사람이 눌러 연 첫 한 번). 채널은 `NotificationDeliveryHooks` 빈으로 부르고 고리가 던진 예외는 삼킨다. 받는 사람 id 는 싣지 않는다(통계만).
- `NotificationInboxRepository.eventStats(eventId): InboxEventStats(stored, read)?` — 기본 null(셀 수 없음). `notification-jdbc` 가 구현한다.

**마이그레이션 주의**: `notification_inbox` 에 칼럼 · 유니크 · 색인 셋을 더한다. 줄이 아주 많은 표에서는 색인 만들기가 표 크기에 비례해 오래 걸리고 그동안 쓰기가 느려질 수 있다 — 한가한 시간에 배포한다.

**보관 정리**: `skeleton.notification.retention.enabled=true` 면 `interval` 마다 `max-age`(기본 90일)보다 오래된 줄을 지운다. 기본 꺼짐. `scheduler` 모듈의 `skeletonTaskScheduler` 가 없으면 경고 한 줄만 남기고 돌지 않으며, 앱이 `NotificationInboxRetention.run(now)` 을 직접 부를 수 있다. 프로젝트 모드(`skeleton.projects`)면 서비스 중인 프로젝트마다 돈다.

**종류별 수신 끄기**: 끌 수 있는 종류는 제품이 `skeleton.notification.preferences.types` 로 정한다(기본 빈 목록 = 끌 수 있는 것 없음 — 보안 · 결제 알림처럼 목록에 없는 종류는 끄기 줄이 있어도 무시된다). 사람이 종류를 끄면 브로커가 받는 사람에서 그 사람을 빼고(받은편지함 + 실시간 전달 한 길만),
받는 사람이 정해진 알림을 **모두** 끄면 저장도 전달도 하지 않는다(빈 `recipientIds` 는 전체 공지라 그대로 넘기면 모두에게 간다). 받는 사람이 없는 전체 공지는 끌 수 없다. 끄기 저장소는 `NotificationMuteRepository`(기본 인메모리, `notification-jdbc` 는 표 `notification_mutes`) - 목록이 비면 조회조차 하지 않는다.

**커밋 뒤 전달**: 받은편지함 저장은 호출자 트랜잭션 안에서 바로 하고(롤백되면 알림도 없다), 구독자 전달(SSE · WebSocket · Slack)은 트랜잭션 동기화가 켜져 있으면(`spring-tx` 클래스가 없는 앱이면 바로 전달) **커밋 뒤**에 한다 — 롤백된 알림이 실시간으로 새지 않는다. 미뤄진 발행의 `deliveredSubscribers` 는 0 이고, 트랜잭션 밖 발행은 지금처럼 바로 전달한다. 다른 `afterCommit` 콜백 안에서 발행해도 그 트랜잭션이 끝나는 `afterCompletion` 에서 전달된다(롤백이면 안 한다). 알림 실패가 업무를 실패시키면 안 되면 커밋 뒤에 부르고 예외를 잡는다.

| 항목 | 내용 |
|---|---|
**광고성 정보 게이트**(`skeleton.notification.advertising.*`, 기본 꺼짐 — `types` 가 비면 아무것도 하지 않는다): 채널과 무관한 부품 `AdvertisingGate`(패키지 `notification.advertising`)가 네 가지를 판정한다 — 광고 수신 동의가 지금 유효한가 · 야간(21~08시) 별도 동의 · 2년 재확인 만료(동의 정책의 몫) · 야간 보류 시각(받는 사람 시간대의 다음 08시). `decide(accountId)` = `SendNow` / `Skip` / `Hold(runAt)` (조회가 실패하면 `Skip` — 실패 시 닫힘, 던지지 않는다), `brand(title, body, maxBodyBytes)` = 「(광고)」 표기. 동의를 읽는 포트는 `AdvertisingConsentPolicy`(구현은 [notification-legal](notification-legal.md)), 시간대는 앱의 `AdvertisingRecipientZones`. 지금 광고 게이트는 푸시 채널에만 걸린다 — 광고성 메일 · 슬랙 등을 보내는 앱은 보내기 전에 이 부품을 불러야 한다(정보통신망법 §50).

| 의존성 한 줄 | `implementation(project(":modules:notification"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.notification.inbox` · `skeleton.notification.retention` · `skeleton.notification.preferences` · `skeleton.notification.advertising` — [docs/config/modules/notification.yml](../config/modules/notification.yml) |
| 기본 동작 | 켜짐. 기본은 인메모리 브로커다 (프로세스 안에서만 전달). 서블릿 앱이면 받은편지함 엔드포인트도 켜진다. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `NotificationDeliveryHook`, `NotificationChannelDeclaration`, `NotificationBroker`, `NotificationRecipientResolver`, `NotificationInboxRepository`, `NotificationMuteRepository`, `NotificationInboxRetention`, `NotificationInboxController` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/notifications` |
| 테스트 | `modules/notification/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
