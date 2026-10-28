# reward

보상 지급의 허리 — 이벤트(`campaign`)가 「이 계정에 이 보상」을 **지급 건**으로 기록하면, 일꾼이 건마다 보상 종류의 지급기(`RewardIssuer`: 쿠폰 · 포인트)를 불러 주고, 끝나면 받은편지함에 「받았어요」를 보낸다.
이벤트는 쿠폰 · 포인트를 모르고, 쿠폰 · 포인트는 이벤트를 모른다 — 둘 사이의 계약(`RewardIssuer` · `RewardGrantView` · `IssuedReward` · `RewardLabel` · `RewardPermanentFailure`)은 `platform` 의 `common/reward` 에 있다.
저장과 작업 큐는 어댑터 `reward-jdbc` 가 한다 — 이 모듈에는 메모리 구현이 없다.

흐름: ① 이벤트가 **자기 트랜잭션 안에서** `RewardGrants.record(계정, 종류, params, sourceType, sourceKey)` — 지급 건 줄과 작업이 같은 커밋에 든다(밖에서 부르면 던진다). 같은 `(sourceType, sourceKey)` 는 한 건으로 접힌다(`null` 이 돌아온다).
② `RewardWorker` 가 한 트랜잭션에서 건을 잠그고 `PENDING` 이 아니면 끝, 지급기의 `issue` 를 부르고 `ISSUED` + `result_ref`. 지급기는 `(GRANT, 건 id)` 로 멱등해야 한다 — 던지면 트랜잭션이 되돌아가 큐가 백오프로 다시 부른다.
③ 커밋 뒤 `RewardNotifier`(기본: `notification` 이 있으면 받은편지함, 알림 id `rwd_<건 id>`)를 부른다. 실패는 로그만 — 보상은 이미 지급됐다.
지급기가 영구 실패(`RewardPermanentFailure`: 정의 보관됨 · 지급기 없음)를 알리면 재시도 없이 건이 `FAILED` 로 남는다. 지급기가 하나도 없어도 모듈은 뜨고 건은 `FAILED` 로 쌓인다.
상태: `PENDING → ISSUED`(끝) · `PENDING → FAILED`(작업이 DEAD 또는 영구 실패; 운영자 「다시 지급」으로 `PENDING`) · `PENDING → CANCELED`(운영자, 지급 전만).

**같은 DB 라는 전제**: 지급기가 쿠폰 · 포인트 표에 쓰는 일이 건을 `ISSUED` 로 적는 것과 한 트랜잭션이다(`issue` 는 호출자 트랜잭션 안). 지급기를 다른 저장소에 쓰는 구현으로 바꾸면 이 전제가 깨지니 그쪽이 `(GRANT, 건 id)` 멱등을 스스로 지켜야 한다.
알림 문구는 앱의 기본 언어(`Locale.getDefault()`)로 서버가 만든다(받는 쪽 번역 렌더가 알림 모듈에 아직 없다). `payload.link` 는 실어 두지만 클릭 이동은 아직 없다.

운영자 경로(`skeleton.reward.admin.enabled=true` 일 때만, 꺼져 있으면 404; 서블릿 웹 앱 + Spring Security 필요) — 모든 호출에 `reward.operate`(기본 역할 없음 — 전권 역할만, 익명 401 · 권한 없음 403):

| 메서드 · 경로 | 하는 일 |
|---|---|
| `GET /api/v1/admin/reward-grants?status&cursor&size` | 지급 건 목록, 새것부터 커서. `{id, accountId, type, label, status, sourceType, lastError, createdAt, issuedAt}` |
| `POST /api/v1/admin/reward-grants/{id}/retry` | `FAILED` 만 다시 지급(답은 갱신된 지급 건)(그 밖 409 `REWARD.NOT_RETRYABLE`). 운영 기록 `reward.retried` |
| `POST /api/v1/admin/reward-grants/{id}/cancel` | `PENDING` 만 취소(그 밖 409 `REWARD.NOT_CANCELABLE`) |

없는 건은 404 `REWARD.NOT_FOUND`. 계정이 지워지면(`AccountErasureListener`) 그 계정의 지급 건 줄을 지운다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:reward"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `notification` |
| 설정 접두사 | `skeleton.reward` — [docs/config/modules/reward.yml](../config/modules/reward.yml) |
| 기본 동작 | 켜짐. 받았어요 알림은 `notification` 이 있으면 보낸다. 운영자 경로는 꺼져 있다. |
| 부팅에 필요한 것 | 저장소 · 작업 포트(`RewardGrantStore` · `RewardTasks`) — `reward-jdbc` 가 낸다. 없으면 지급 건 서비스 빈이 켜지지 않는다. |
| 교체 지점 | `RewardNotifier`, `RewardGrantStore`, `RewardTasks`, `RewardGrants`, `RewardGrantService`, `RewardWorker`, `RewardAdminController`, `RewardIssuer`(보상 종류마다 하나, 여러 개) |
| 마이그레이션 | 없음 (스키마는 `reward-jdbc`) |
| 프론트 짝 | `@skeleton/rewards` |
| 테스트 | `modules/reward/src/test`, `modules/reward/src/noOptionalTest` (`notification` 이 클래스패스에 없을 때) |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
