# notification-scheduled

사람마다 따로 예약하는 푸시 — 앱이 **한 사람에게 T 시각에 보낼 푸시**를 예약하고, 같은 키로 다시 예약하면 바꾸고, 키로 취소한다. 모두에게 한 번 보내는 공지는 [notification-broadcast](notification-broadcast.md)의 몫이다. 저장은 어댑터 `notification-scheduled-jdbc`.
**계기는 앱이 정한다.** 이 모듈은 「읽다 만 사람」 「가입 다음 날」 「잠금이 풀리는 때」 같은 사건을 모른다 — 앱 코드가 사건마다 `ScheduledPushes.schedule(…)` · `cancel(key)` 를 부른다. 받는 사람마다 기존 `NotificationPublisher.publish` 를 부르므로 알림함 · 푸시 끄기 · 기기 작업 · 재시도가 그대로 따라온다.

**쓰는 법**
```kotlin
scheduledPushes.schedule(ScheduledPush(key = "resume:$userId:$seriesId", recipientId = userId, runAt = now + 6.hours, title = "다음 화가 기다려요", body = "…", link = "/series/$seriesId"))
scheduledPushes.cancel("resume:$userId:$seriesId")   // 그 사이에 읽었으면 취소
```
- **중복 키**: 같은 키로 다시 예약하면 새로 만들지 않고 **아직 안 나간 예약을 바꾼다**(`REPLACED`). 이미 나갔거나 건너뛴 키는 그대로 둔다(`IGNORED`) — 같은 계기가 여러 번 일어나도 한 통만 간다. 같은 사건을 또 보내고 싶으면 키에 구분 값(회차 · 날짜)을 넣는다. 끝난 줄은 `keep-finished`(30일) 동안 이 표시로 남고 그 뒤 지워진다.
- **취소**: 키로 지운다 — 아직 안 나간 예약이 있었으면 `true`. 이미 훑기가 집어 나가는 중이면 막지 못한다.
- **통계**: `ScheduledPushes.stats()` → 예약 중 · 보낸 · 건너뛴 수. 화면은 앱이 만든다.

**나가는 길** 1분마다 훑는다(`scheduler` 모듈의 `notification.scheduled.sweep`, 요청 구동 모드의 인스턴스 0대 배포는 깨우기가 `OnRequestTask` `notification-scheduled-sweep` 을 부른다 — [Cloud Run 0대 운영](../cloud-run-scale-to-zero.md)). 훑기는 시각이 된 줄을 **조건부 갱신으로 집는다**: 여러 인스턴스가 동시에 훑어도 한 줄은 하나만 받는다. 집은 줄은 `lease`(5분) 동안 남이 못 집고, 집은 쪽이 죽으면 그 뒤 다시 나간다. 알림 이벤트 id 가 키에서 정해지므로(`sp_<해시>`) 보낸 뒤 닫기 전에 죽어 다시 나가도 알림함은 한 줄로 합친다(단말 푸시는 드물게 두 번 갈 수 있다).
**광고성** 예약(`advertising = true`)은 기존 광고 게이트를 그대로 탄다([notification-legal](notification-legal.md) · [notification-push-legal](notification-push-legal.md)): 광고 수신 동의가 없으면 **보내지 않고 건너뛴다**(`NO_CONSENT`), 야간(21~08시)이고 야간 동의가 없으면 **다음 08시로 미룬다**(받는 사람 시간대), 제목에 `(광고)` 를 붙인다. 광고는 `advertising-type` 으로, 정보성은 `type` 으로 나간다 — 정보성은 동의와 무관하다(앱이 고른다).
**하루 상한** `daily-limit` — 한 사람이 지난 24시간에 받은 수가 이 값에 닿으면 그 예약은 건너뛴다(`DAILY_LIMIT`, 미루지 않는다). 기본은 상한 없음이고 값은 앱 yml 이 정한다. 동시에 훑는 두 인스턴스가 같은 사람 몫을 한 통 넘길 수 있다(엄격한 한도가 아니다).
**탈퇴** 계정이 지워지면 `AccountErasureListener`(`notification-scheduled`)가 그 사람의 예약을 모두 지운다(멱등).

**켜는 법**: `notification-scheduled-jdbc` 를 얹고 `skeleton.notification.scheduled.enabled=true` + `type`. 푸시로 닿으려면 `notification-push` 를 켜고 `push.types` 에 `type`(과 `advertising-type`)을 넣는다 — 안 넣으면 알림함에만 쌓인다. 광고를 보내려면 `advertising-type` 을 `skeleton.notification.advertising.types` 에도 넣고 `notification-legal` 을 얹는다. 구성이 틀리면(저장소 없음 · `type` 이 광고 종류 · `advertising-type` 이 광고 종류가 아님 · `daily-limit` 0 이하) 시작이 실패하고 무엇을 할지 말한다. 내용(제목 · 본문)은 로그에 싣지 않는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:notification-scheduled"))` |
| 함께 오는 모듈 | `notification`, `platform` |
| 컴파일 전용 | `scheduler` |
| 설정 접두사 | `skeleton.notification.scheduled` — [docs/config/modules/notification-scheduled.yml](../config/modules/notification-scheduled.yml) |
| 기본 동작 | 꺼짐. `skeleton.notification.scheduled.enabled=true` 와 `type` 을 앱이 정하면 선다. |
| 부팅에 필요한 것 | 켰을 때: 저장소 포트 `ScheduledPushStore`(`notification-scheduled-jdbc` 가 낸다), 알림 종류 `type`. 없으면 무엇을 얹을지 말하고 기동에 실패한다. |
| 교체 지점 | `ScheduledPushStore`, `ScheduledPushes`, `ScheduledPushSweep`, `scheduledPushSweepTask`, `scheduledPushErasureListener` |
| 마이그레이션 | 없음 (스키마는 `notification-scheduled-jdbc`) |
| 프론트 짝 | 없음 |
| 테스트 | `modules/notification-scheduled/src/test` |

자세히: [notification](notification.md) · [notification-broadcast](notification-broadcast.md) · [notification-push](notification-push.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
