# campaign

이벤트 — 조건을 판정해 「이 계정에 이 보상」을 **지급 건**으로 기록하고 거기서 끝난다. 지금 종류 셋: **출석**(매일 보상 + 누적 · 연속 달성 보상), **친구초대**(초대받은 사람 · 초대자 양쪽 보상), **업적**(앱이 보고한 수치가 단계 기준값에 닿으면 단계의 보상).
쿠폰 · 포인트를 모른다 — 보상은 설정에 `{type, params}` 로 적히고(`type` 은 지급기 이름: `point` · `coupon`), 실제로 주는 일은 `reward` 의 일꾼이 지급기(`RewardIssuer`)로 한다.
저장은 어댑터 `campaign-jdbc` 가 한다 — 이 모듈에는 메모리 구현이 없다. 지급기가 하나도 없어도 뜨고(건은 `FAILED` 로 남는다) 보상 종류는 저장 전에 지급기가 `validate` 한다(모르는 타입 · 받아들이지 않는 params 는 `400 CAMPAIGN.CONFIG_INVALID`).

**출석**: 「오늘」은 **서비스 시간대**의 날짜다(`skeleton.campaign.zone` → `skeleton.time.default-zone` → `Asia/Seoul`; 보는 사람의 시간대를 쓰면 시간대를 옮겨 두 번 찍을 수 있다). 하루 한 번은 `(이벤트, 계정, 날짜)` 키가 지키고, 같은 날 두 번째는 지급 없이 현재 상태를 돌려준다.
한 트랜잭션에서 출석 줄 → 누적 · 연속 계산 → 보상마다 `RewardGrants.record`. 지급 건 키는 매일 `<이벤트>:<계정>:<날>:daily:<순번>`, 누적 달성 `<이벤트>:<계정>:milestone:<일수>:<순번>`(한 번), 연속 달성 `<이벤트>:<계정>:streak:<일수>:<연속 시작일>:<순번>`(연속이 끊겼다 다시 쌓여 닿으면 다시). 지난 날을 채워 찍는 길은 없다.
설정: `{daily: [{type, params}], milestones: [{days, streak, rewards}]}`.

**업적**(`achievement`): 설정 `{metric, milestones: [{value, rewards: [...]}]}` — `metric` 은 `[a-z0-9._-]{1,64}`, 단계는 1~20개 · 기준값 오름차순 · 중복 없음 · 단계마다 보상 1개 이상(출석 `milestones` 와 같은 꼴, 보상은 어떤 종류든 나란히 — `[{type: badge}, {type: point}]`).
앱이 서버 API `CampaignSignals.report(accountId, metric, value)` 로 **현재 값**을 보고한다(예: 노트 저장 뒤 자기 개수를 세어 보고; 브라우저가 수치를 보고하면 안 되므로 HTTP 경로는 없다). 한 트랜잭션: `campaign_progress` 를 `max(기존, 새 값)` 으로 올리고(증가만 — 낮은 값은 무시) → 진행 중인 `achievement` 이벤트 중 같은 `metric` 의 **값 이하인 모든 단계**의 보상마다 `RewardGrants.record`.
지급 건 키 `<이벤트>:<계정>:achv:<기준값>:<순번>` 이 같은 단계를 한 번으로 접어 같은 값을 다시 보고해도 두 번 주지 않고, 이벤트를 나중에 만들어도 다음 보고에서 이미 넘은 단계를 준다. 수치는 이벤트와 따로 계정에 붙는다(꺼진 · 기간 밖 · 아직 없는 이벤트가 있어도 값은 쌓인다). `GET /api/v1/campaigns` 의 응답 모양은 그대로이고 업적은 현황(`attendance`)이 `null` 이다.
**주의**: 지급 건 키에 단계의 **기준값과 보상 순서**가 들어 있어, 진행 중인 이벤트의 단계를 고쳐 기준값을 바꾸거나 보상의 순서를 바꾸면 같은 계정이 새 키로 한 번 더 받는다(이미 받은 단계 보상이 다시 나갈 수 있다). 진행 중에는 단계를 추가하고, 이미 준 단계는 바꾸지 말고 새 이벤트를 만든다.

**친구초대**: 코드는 계정당 하나(헷갈리는 글자를 뺀 8자, 처음 볼 때 만든다). `claim` 은 한 트랜잭션에서 판정한다 — 진행 중인 초대 이벤트가 있어야 하고(없으면 409 `CAMPAIGN.NOT_ACTIVE`), 아래 이유로 거절하면 `400 CAMPAIGN.REFERRAL_INVALID`(`detail` 과 `data.reason` 에 이유):
`UNKNOWN_CODE` · `OWN_CODE`(자기 코드) · `ALREADY_INVITED`(초대받은 사람은 평생 한 번) · `MUTUAL`(내가 초대한 사람의 코드) · `NOT_NEW_ACCOUNT`(가입 후 `claimWithinDays`, 기본 7일이 지난 계정; `AccountFacts` 빈이 없으면 이 판정만 건너뛴다).
통과하면 관계 줄 + 초대받은 사람 보상. 초대자 보상 시점은 설정 `qualifyOn` 이고 **기본값이 없다**(설정 필수): `CLAIM` 이면 같은 트랜잭션, `SIGNAL` 이면 앱이 `Referrals.qualify(inviteeId)` 를 부를 때(예: 첫 결제 — 몇 번 불러도 한 번).
`inviterCap` 을 넘은 초대는 관계만 남고(`CAPPED`) 보상이 없다 — `CLAIM` 에서는 양쪽 모두, `SIGNAL` 에서는 초대자 쪽만(초대받은 쪽 보상은 claim 때 이미 나갔다). 상한 판정과 맞초대(A↔B 동시 claim) 판정은 두 계정의 초대 코드 줄을 정렬된 순서로 `for update` 잡아 직렬화한 뒤 잠금 읽기로 센다 - 같은 코드로 동시에 몰려도 상한을 넘지 않고, 정렬된 순서라 교착하지 않는다.
`qualify` 는 환불로 되돌리지 않는다 - 환불 기간이 지난 뒤에 부르거나 앱이 판단한다(환불된 첫 결제로 초대자 보상이 이미 나갔어도 회수하지 않는다).
설정: `{invitee: [..], inviter: [..], qualifyOn: CLAIM|SIGNAL, claimWithinDays: 7, inviterCap: null}`.

사용자 경로(`skeleton.campaign.http.enabled=false` 로 끈다; 서블릿 웹 앱 + Spring Security, 익명 401): `GET /api/v1/campaigns`(진행 중 + 내 출석 현황) · `POST /api/v1/campaigns/{id}/attendance` · `GET /api/v1/campaigns/{id}/attendance?month=YYYY-MM` · `GET /api/v1/referrals/me` · `POST /api/v1/referrals/claim {code}`(204).
운영자 경로(`skeleton.campaign.admin.enabled=true` 일 때만, 꺼져 있으면 404) — `campaign.manage`(기본 역할 없음 — 전권만): `GET/POST /api/v1/admin/campaigns` · `PUT /{id}`(종류는 못 바꾼다) · `PATCH /{id}/enabled {enabled}`. 운영 기록 `campaign.created` · `campaign.updated`(수정 · 켜고 끄기, 대상은 id 만).
계정이 지워지면(`AccountErasureListener`) 그 계정의 출석 · 업적 수치 · 코드 · 초대받은 쪽 관계를 지운다. 초대자 쪽 관계는 지우지 않고 초대자 id 만 익명화한다(초대받은 사람의 「평생 한 번」이 풀리지 않게 - 지워진 초대자에게는 `SIGNAL` 보상도 나가지 않는다). 오류 코드: `CAMPAIGN.NOT_FOUND` 404 · `NOT_ACTIVE` 409 · `REFERRAL_INVALID` · `CONFIG_INVALID` 400.
프로젝트 모드(`skeleton.projects`)에서 시간대를 프로젝트마다 정하는 길은 아직 없다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:campaign"))` |
| 함께 오는 모듈 | `reward`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.campaign` — [docs/config/modules/campaign.yml](../config/modules/campaign.yml) |
| 기본 동작 | 켜짐. 사용자 경로는 열리고 운영자 경로는 꺼져 있다. 이벤트는 운영자가 만든다 — 아무것도 심지 않으면 비어 있다. |
| 부팅에 필요한 것 | 저장소 포트(`CampaignStore`) — `campaign-jdbc` 가 낸다 — 와 지급 기록(`RewardGrants`) — `reward` + `reward-jdbc` 가 낸다. 없으면 서비스 빈이 켜지지 않는다. |
| 교체 지점 | `CampaignKind`(종류마다 하나, 여러 개), `CampaignStore`, `Referrals`, `CampaignSignals`, `Campaigns`, `AttendanceService`, `ReferralService`, `CampaignZone`, `CampaignController`, `ReferralController`, `CampaignAdminController` |
| 마이그레이션 | 없음 (스키마는 `campaign-jdbc`) |
| 프론트 짝 | `@skeleton/rewards` |
| 테스트 | `modules/campaign/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
