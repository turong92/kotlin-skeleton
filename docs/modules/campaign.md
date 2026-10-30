# campaign

이벤트 — 조건을 판정해 「이 계정에 이 보상」을 **지급 건**으로 기록하고 거기서 끝난다. 지금 종류 셋: **출석**(매일 보상 + 누적 · 연속 달성 보상), **친구초대**(초대받은 사람 · 초대자 양쪽 보상), **업적**(앱이 보고한 수치가 단계 기준값에 닿으면 단계의 보상).
쿠폰 · 포인트를 모른다 — 보상은 설정에 `{type, params}` 로 적히고(`type` 은 지급기 이름: `point` · `coupon`), 실제로 주는 일은 `reward` 의 일꾼이 지급기(`RewardIssuer`)로 한다.
저장은 어댑터 `campaign-jdbc` 가 한다 — 이 모듈에는 메모리 구현이 없다. 지급기가 하나도 없어도 뜨고(건은 `FAILED` 로 남는다) 보상 종류는 저장 전에 지급기가 `validate` 한다(모르는 타입 · 받아들이지 않는 params 는 `400 CAMPAIGN.CONFIG_INVALID`).

**출석**: 「오늘」은 **서비스 시간대**의 날짜다(`skeleton.campaign.zone` → `skeleton.time.default-zone` → `Asia/Seoul`; 보는 사람의 시간대를 쓰면 시간대를 옮겨 두 번 찍을 수 있다). 하루 한 번은 `(이벤트, 계정, 날짜)` 키가 지키고, 같은 날 두 번째는 지급 없이 현재 상태를 돌려준다.
한 트랜잭션에서 출석 줄 → 누적 · 연속 계산 → 보상마다 `RewardGrants.record`. 지급 건 키는 매일 `<이벤트>:<계정>:<날>:daily:<순번>`, 누적 달성 `<이벤트>:<계정>:milestone:<일수>:<순번>`(한 번), 연속 달성 `<이벤트>:<계정>:streak:<일수>:<연속 시작일>:<순번>`(연속이 끊겼다 다시 쌓여 닿으면 다시). 지난 날을 채워 찍는 길은 없다. 키에 보상 **순번**이 들어 있으므로 진행 중인 이벤트의 보상 목록 중간에 끼우거나 빼면 밀린 보상이 새 키로 다시 나갈 수 있다 - 더할 때는 목록 끝에만 더한다.
설정: `{daily: [{type, params}], milestones: [{days, streak, rewards}]}`.

**업적**(`achievement`): 설정 `{metric, milestones: [{value, rewards: [...]}]}` — `metric` 은 `[a-z0-9._-]{1,64}`, 단계는 1~20개 · 기준값 오름차순 · 중복 없음 · 단계마다 보상 1개 이상(출석 `milestones` 와 같은 꼴, 보상은 어떤 종류든 나란히 — `[{type: badge}, {type: point}]`).
앱이 서버 API `CampaignSignals.report(accountId, metric, value)` 로 **현재 값**을 보고한다(예: 노트 저장 뒤 자기 개수를 세어 보고; 브라우저가 수치를 보고하면 안 되므로 HTTP 경로는 없다). 한 트랜잭션: `campaign_progress` 를 `max(기존, 새 값)` 으로 올리고(증가만 — 낮은 값은 무시) → 진행 중인 `achievement` 이벤트 중 같은 `metric` 의 **값 이하인 모든 단계**의 보상마다 `RewardGrants.record`.
지급 건 키 `<이벤트>:<계정>:achv:<기준값>:<보상 해시>#<순번>`(해시는 보상의 종류 · params 로 정한다 - 숫자 표기 `100`/`100.0`/`"100"` 은 같고, `#` 뒤는 한 단계에 같은 보상이 둘일 때의 순번이라 둘 다 한 번씩 나간다)가 같은 단계를 한 번으로 접어 같은 값을 다시 보고해도 두 번 주지 않고, 이벤트를 나중에 만들어도 다음 보고에서 이미 넘은 단계를 준다. 수치는 이벤트와 따로 계정에 붙는다(꺼진 · 기간 밖 · 아직 없는 이벤트가 있어도 값은 쌓인다). `GET /api/v1/campaigns` 의 응답 모양은 그대로이고 업적은 현황(`attendance`)이 `null` 이다.
**주의**: 키가 보상 자체(종류 · params)로 정해지므로 단계 안에서 보상을 끼우거나 순서를 바꾸는 것, 이름 · 기간 수정은 안전하다(이미 준 보상은 다시 나가지 않고 새 보상만 나간다). 위험한 것은 **이미 준 보상의 params 를 고치는 것**이다(예: point `amount` 10→20) - 새 보상으로 쳐서 그 단계를 넘은 모든 계정에 다시 나간다. 기준값을 바꾸거나 이미 준 보상을 고쳐야 하면 새 이벤트를 만든다.

**친구초대**: 코드는 계정당 하나(헷갈리는 글자를 뺀 8자, 처음 볼 때 만든다). `claim` 은 한 트랜잭션에서 판정한다 — 진행 중인 초대 이벤트가 있어야 하고(없으면 409 `CAMPAIGN.NOT_ACTIVE`), 아래 이유로 거절하면 `400 CAMPAIGN.REFERRAL_INVALID`(`detail` 과 `data.reason` 에 이유):
`UNKNOWN_CODE` · `OWN_CODE`(자기 코드) · `ALREADY_INVITED`(초대받은 사람은 평생 한 번) · `MUTUAL`(내가 초대한 사람의 코드) · `NOT_NEW_ACCOUNT`(가입 후 `claimWithinDays`, 기본 7일이 지난 계정; `AccountFacts` 빈이 없으면 이 판정만 건너뛴다).
통과하면 관계 줄 + 초대받은 사람 보상. 초대자 보상 시점은 설정 `qualifyOn` 이고 **기본값이 없다**(설정 필수): `CLAIM` 이면 같은 트랜잭션, `SIGNAL` 이면 앱이 `Referrals.qualify(inviteeId)` 를 부를 때(예: 첫 결제 — 몇 번 불러도 한 번).
`inviterCap` 을 넘은 초대는 관계만 남고(`CAPPED`) 보상이 없다 — `CLAIM` 에서는 양쪽 모두, `SIGNAL` 에서는 초대자 쪽만(초대받은 쪽 보상은 claim 때 이미 나갔다). 상한 판정과 맞초대(A↔B 동시 claim) 판정은 두 계정의 초대 코드 줄을 정렬된 순서로 `for update` 잡아 직렬화한 뒤 잠금 읽기로 센다 - 같은 코드로 동시에 몰려도 상한을 넘지 않고, 정렬된 순서라 교착하지 않는다.
설정: `{invitee: [..], inviter: [..], qualifyOn: CLAIM|SIGNAL, claimWithinDays: 7, inviterCap: null, qualifyEvent: null, holdDays: 0, reviewSameNetwork: false}` - 뒤의 셋을 모두 비우면 예전 동작 그대로다(스켈레톤은 기본값을 바꾸지 않는다, 쓰는 쪽이 설정으로 고른다).
- `qualifyEvent`(`[a-z0-9._-]{1,64}`, `qualifyOn: SIGNAL` 일 때만): 이 이름의 신호만 초대자 보상을 정한다. 앱은 `Referrals.qualify(inviteeId, "first-payment")` 로 이름을 붙여 부르고, 이름이 다르거나 없으면 무시한다(비워 두면 아무 신호나, 몇 번 불러도 한 번).
- `holdDays`(0~90): 초대자 보상을 신호 뒤 이만큼 보류한다(`HELD`, 청약철회 · 환불 기간). 기한이 지나면 주기 작업이 `QUALIFIED` 로 풀어 보상을 준다 - `scheduler` 가 있으면 10분마다 `campaign.referral.release`(한 번에 500건), 없으면 앱이 `Referrals.releaseDue()` 를 부른다. 겹쳐 돌아도 조건부 갱신이라 지급 건은 한 벌이다.
- `reviewSameNetwork`: claim 때 호출자의 네트워크(`ClientIps.limitKey`)가 초대자의 마지막 `GET /referrals/me` 네트워크 또는 같은 초대자의 다른 초대받은 사람의 claim 네트워크와 같으면(30일 안) 관계에 `SAME_NETWORK` 표시가 붙는다. 켜 두면 표시가 붙은 초대는 `REVIEW` 로 멈춰 운영자가 승인할 때까지 지급하지 않는다. **표시는 거절이 아니다** - 초대받은 사람 보상은 claim 때 그대로 나간다. 네트워크 값은 원문 키(IP 또는 IPv6 /64)라 30일 뒤 해제 작업이 지우고 판정 결과는 `flags` 로만 남는다. 클라이언트 IP 를 믿을 수 있게 `skeleton.web.client-ip.mode` 를 정하지 않으면 헤더로 네트워크를 속일 수 있어 `DeployGuard` 가 경고한다([client-ip](../client-ip.md)).
- **초대자 판정**(초대자 코드 줄을 잠그고, 위에서부터 처음 맞는 것): 상한(`QUALIFIED + HELD + REVIEW` 수가 `inviterCap` 이상 → `CAPPED`, 보류 · 확인 중도 자리를 차지한다) → 같은 네트워크 확인 → `REVIEW` → 보류 → `HELD` → 그 밖 `QUALIFIED` + 초대자 보상.
- 상태: `CLAIMED`(신호 대기) · `REVIEW` · `HELD` · `QUALIFIED` · `CAPPED` · `CANCELED`. 끝 상태(`QUALIFIED` · `CAPPED` · `CANCELED`)는 바뀌지 않는다. 운영자 승인은 `REVIEW | HELD → QUALIFIED`(남은 보류를 건너뛴다), 취소는 `CLAIMED | REVIEW | HELD → CANCELED`(사유 1~200자 필수, 앱은 `Referrals.cancel(inviteeId, reason)` - 환불 · 사기 신호). 탈퇴한 초대자의 `HELD` 는 해제 때 `CANCELED`(`inviter_left`)로 닫히고 승인은 409.
- **한계 (결정됨)**: ① 탈퇴 후 재가입은 1차에서 막지 않는다 - 지운 계정으로 같은 사람이 다시 가입해 초대받을 수 있다(다음 판). ② 회수는 **보류 중 취소까지**다. 보상이 이미 나간 뒤(`QUALIFIED`)에는 이 모듈이 되돌리지 않는다 - 지급 건이 아직 `PENDING` 이면 `reward` 의 지급 건 취소로, 지급이 끝났으면 각 모듈의 회수 도구로 한다. 그래서 환불 기간만큼 `holdDays` 를 두는 것이 이 모듈이 주는 안전장치다.

사용자 경로(`skeleton.campaign.http.enabled=false` 로 끈다; 서블릿 웹 앱 + Spring Security, 익명 401): `GET /api/v1/campaigns`(진행 중 + 내 출석 현황) · `POST /api/v1/campaigns/{id}/attendance` · `GET /api/v1/campaigns/{id}/attendance?month=YYYY-MM` · `GET /api/v1/referrals/me`(`{code, invited, rewarded, canClaim, claimUntil}` - `canClaim`: 진행 중인 초대 이벤트가 있고 아직 초대받지 않았고 가입 후 기간 안, `claimUntil`: 코드를 쓸 수 있는 마지막 때 - `AccountFacts` 가 없으면 null; 부를 때 이 호출자의 네트워크를 적는다) · `GET /api/v1/referrals/me/invitees?cursor&size`(내가 초대한 사람들, 새것부터 커서 `{label, status, claimedAt, rewardAt}`; `label` 은 가린 이름 - 앞 두 글자 + `***`, `@` 뒤는 버리고 `AccountLabels` 가 없으면 null; `status` 는 사용자용 넷 `WAITING`(대기) · `PENDING`(`REVIEW` · `HELD`, `rewardAt` 은 보류 중일 때만) · `REWARDED` · `NOT_REWARDED`(`CAPPED` · `CANCELED`) - 확인 대기 · 표시 · 사유 · 이메일 원문은 내보내지 않는다) · `POST /api/v1/referrals/claim {code}`(204; 호출자 네트워크를 `ClientIps.limitKey` 로 넘긴다).
운영자 경로(`skeleton.campaign.admin.enabled=true` 일 때만, 꺼져 있으면 404) — `campaign.manage`(기본 역할 없음 — 전권만): `GET/POST /api/v1/admin/campaigns` · `PUT /{id}`(종류는 못 바꾼다) · `PATCH /{id}/enabled {enabled}`. 운영 기록 `campaign.created` · `campaign.updated`(수정 · 켜고 끄기, 대상은 id 만).
초대 운영(같은 스위치, 같은 권한 - 새 권한은 없다): `GET /api/v1/admin/referrals?status&inviterId&inviteeId&campaignId&flag&cursor&size`(초대 내역 `{inviteeId, inviterId, inviteeLabel, inviterLabel, campaignId, status, flags, qualifyEvent, claimedAt, qualifiedAt, releaseAt, settledAt, settledBy, cancelReason}`, 이름은 `account.read` 도 있는 운영자에게만) · `POST /{inviteeId}/approve`(204; `REVIEW | HELD` 만, 그 밖 409 `REFERRAL_NOT_PENDING`) · `POST /{inviteeId}/cancel {reason}`(204; 사유 비면 400 `REFERRAL_REASON_INVALID`, 끝 상태 409 `REFERRAL_NOT_CANCELABLE`). 운영 기록 `campaign.referral.approved` · `campaign.referral.canceled`(대상은 초대받은 사람 id 만).
계정이 지워지면(`AccountErasureListener`) 그 계정의 출석 · 업적 수치 · 코드 · 초대받은 쪽 관계를 지운다. 초대자 쪽 관계는 지우지 않고 초대자 id 만 익명화한다(초대받은 사람의 「평생 한 번」이 풀리지 않게 - 지워진 초대자에게는 `SIGNAL` 보상도 나가지 않는다). 오류 코드: `CAMPAIGN.NOT_FOUND` 404 · `NOT_ACTIVE` 409 · `REFERRAL_INVALID` · `CONFIG_INVALID` · `REFERRAL_REASON_INVALID` 400 · `REFERRAL_NOT_FOUND` 404 · `REFERRAL_NOT_PENDING` · `REFERRAL_NOT_CANCELABLE` 409.
프로젝트 모드(`skeleton.projects`)에서 시간대를 프로젝트마다 정하는 길은 아직 없다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:campaign"))` |
| 함께 오는 모듈 | `reward`, `platform` |
| 컴파일 전용 | `scheduler` (보류 해제 작업의 표시. Spring Security 도 컴파일 전용 — 없으면 HTTP 를 등록하지 않는다) |
| 설정 접두사 | `skeleton.campaign` — [docs/config/modules/campaign.yml](../config/modules/campaign.yml) |
| 기본 동작 | 켜짐. 사용자 경로는 열리고 운영자 경로는 꺼져 있다. 이벤트는 운영자가 만든다 — 아무것도 심지 않으면 비어 있다. |
| 부팅에 필요한 것 | 저장소 포트(`CampaignStore`) — `campaign-jdbc` 가 낸다 — 와 지급 기록(`RewardGrants`) — `reward` + `reward-jdbc` 가 낸다. 없으면 서비스 빈이 켜지지 않는다. |
| 교체 지점 | `CampaignKind`(종류마다 하나, 여러 개), `CampaignStore`, `Referrals`, `CampaignSignals`, `Campaigns`, `AttendanceService`, `ReferralService`, `CampaignZone`, `CampaignController`, `ReferralController`, `CampaignAdminController`, `ReferralAdminController` |
| 마이그레이션 | 없음 (스키마는 `campaign-jdbc`) |
| 프론트 짝 | `@skeleton/rewards` |
| 테스트 | `modules/campaign/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
