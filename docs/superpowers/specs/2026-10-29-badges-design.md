# 배지 · 업적 — 설계 (형 결정 반영)

> 2026-10-29. 요청: 앱이 업적(조건)을 정의하면 사용자가 조건을 채웠을 때 배지를 준다. 사용자 화면(내 배지 · 프로필 배지)과 운영(정의 관리 · 수여/회수 내역).
> 형 원문: 「멀티모듈 구조로 조립식으로 다른곳에서 갖다 쓸 수 있게 … 캡슐화 느낌으로」 · 「스켈레톤 기본은 아무 옵션 없는게 맞고 내 취향(override) 을 따로」 · 「국룰 따라가자 항상」.
> 대상: kotlin-skeleton(새 `badge` · `badge-jdbc`, 기존 `campaign` · `campaign-jdbc` · `apps/sample`) + react-skeleton(`@skeleton/rewards` 새 하위 경로 `/badge` · 기존 `/admin` · `apps/sample`).
> 근거: 2026-10-28 이벤트 · 보상 설계의 비목표 두 줄 — 「배지 → `badge` 모듈이 `RewardIssuer` 를 구현하면 끝」 · 「달성은 앱이 부르는 신호로 들어온다」. 이번에 읽은 코드: `common/reward/RewardContracts.kt` · `reward/RewardModels.kt` · `campaign/CampaignKind.kt` · `CampaignConfigs.kt` · `point/*AutoConfiguration.kt` · `coupon` 의 `AccountLabels` 사용(브랜치 `coupon-minor` `ea58161b`).

## 0. 국룰 조사 → 권장안

| 물음 | 업계 | 권장 |
|---|---|---|
| 조건을 무엇으로 세나 | Steam: 업적에 **진행 수치(stat)** 를 걸면 수치가 기준에 닿을 때 자동 해제 + 게임이 직접 해제(`SetAchievement`) 둘 다. 수치는 「증가만」 속성을 둘 수 있다. GitLab: 운영자 · API 가 직접 수여만. Discourse: 시스템 · SQL 자동 + 운영자 수동 | **두 길**: ① 앱이 직접 수여 `Badges.award` (+ 운영자 수동 수여) ② 앱이 **현재 값을 보고**(`report(accountId, metric, value)`, 증가만) → 단계에 닿으면 자동. 규칙 엔진 · SQL 조건은 안 만든다 |
| 같은 배지 여러 번 | 대부분 계정당 한 번, 단계형(GitHub x2/x3/x4, Steam 단계별 업적)은 **정의를 여러 개**로. Discourse · GitLab 은 「여러 번」 옵션 | 1차는 계정당 한 번. 단계는 같은 수치에 기준이 다른 정의 여러 개 |
| 포인트 · 쿠폰을 같이 주나 | Xbox: 업적이 게이머스코어 · 아이템을 준다. Steam · GitHub · GitLab: 배지만 | **업적 이벤트의 보상 목록에 나란히** — `[{type: badge}, {type: point}]`. 배지 정의는 보상을 모른다(결정 2) |
| 회수 | Discourse: 운영자 회수 + 조건을 잃으면 자동 회수(옵션). GitLab: 회수해도 기록은 남는다 | 운영자 회수만(사유 필수, 내역에 남음). 같이 준 포인트 · 쿠폰은 되돌리지 않는다(국룰 — 회수는 배지만) |
| 숨은 업적 | Steam · Xbox: 받기 전엔 이름 · 설명을 가린다 | 정의에 `hidden` 하나 |
| 프로필 노출 | GitHub · GitLab: 기본 공개, 본인이 배지별로 숨김 | **비로그인 공개** + 본인이 배지별로 숨김(`visible`) — 결정 1 |
| 희귀도 | Steam: 전체 달성 비율, Discourse: 보유자 수 | 운영 목록에 보유자 수만 |

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| 배지 | 정의(코드 · 이름 · 설명 · 그림 · 숨김 · 순서 · 상태) · 계정당 한 번 수여 · 회수 · 내역 · 프로필 숨김 | 여러 번 받는 배지 · 등급(금은동) 묶음 · 대표 배지 고르기/순서 · 그림 올리기(`storage`) · 정의 문구 다국어 |
| 수여 길 | 앱 `Badges.award` · 운영자 수동(여러 계정) · 지급기(`RewardIssuer` 종류 `badge`) | 조건을 잃으면 자동 회수 |
| 조건 | `campaign` 새 종류 `achievement` + `CampaignSignals.report`(현재 값 · 증가만) · 단계마다 보상 목록 | 진행도 막대 화면 · 증가 신호(`increment` + 중복 키) · 스켈레톤 모듈 이벤트 자동 연결(글 수 · 결제 수) |
| 화면 | 내 배지(받은 것 · 아직인 것) · 프로필 배지 줄(비로그인도 본다) · 운영 정의 목록/편집/수동 수여 · 수여/회수 내역 · 업적 이벤트 편집 | 배지 대시보드 카드 |

**비목표**: 규칙 엔진 · SQL 조건, 배지가 권한 · 칭호를 주는 것(Discourse title), 공유 카드 이미지, 배지 전용 속도 제한(공개 읽기는 platform 공통 한도를 쓴다 — §5), 배지 정의에 보상 목록 달기(결정 2).
**반목표**: 같은 조건이 두 번 판정돼 두 번 수여 · 일꾼 재시도로 두 번 수여 / `badge` 가 `campaign` · `reward` · `coupon` · `point` 를 안다 / 배지를 안 붙인 앱의 동작이 바뀐다 / 공개 응답에 숨긴 배지 · 받지 않은 숨은 업적이 섞인다.

## 2. 모듈 경계 · 확장 지점

```
badge(-jdbc) ───────────▶ platform{RewardIssuer, AccountLabels, OperatorAccess, AccountErasureListener}
badge ··compileOnly··▶ notification          (reward · board 와 같은 선택 연동, noOptionalTest)
campaign(-jdbc) ──api──▶ reward              (기존 그대로 — 새 종류 achievement 만 더한다)
```

- `badge` 혼자 붙여도 돈다: 정의 · 앱 수여 · 운영 수여/회수 · 사용자 화면. 자동 업적과 보상 동반은 `campaign` + `reward` + `job-queue-jdbc` 를 더 붙였을 때만.
- **조립 원칙(결정 2)** — 형: 「각자 각각이라 조립 하는 식으로 가능 하잖아?」 배지 · 포인트 · 쿠폰은 서로를 모르는 지급기 셋이고, 「같이 주기」는 업적 단계의 보상 목록에 셋을 나란히 적는 조립으로만 한다. 그래서 `badge` 는 `reward` · `coupon` · `point` 에 의존하지 않는다(`compileOnly` 도 안 건다 — `new-project.sh` 가 compileOnly 상대 소스도 끌고 온다, 이벤트 · 보상 설계 §2 와 같은 이유). 앱 직접 수여 · 수동 수여에 포인트를 붙이고 싶은 앱은 자기 코드에서 `Badges.award` 와 포인트 지급을 나란히 부른다.

| 확장 지점 | 자리 | 고른 이유 |
|---|---|---|
| `RewardIssuer` 구현 `badge`(params `code`) | `badge` | 이벤트 · 보상 설계가 미리 정한 허리. 이벤트는 배지를 모른다 |
| `Badges`(앱이 부르는 서버 API: `award` · `revoke` · `holdingsOf(accountIds)`) | `badge` | 앱 고유 조건(첫 글 · 첫 결제)은 앱 코드가 가장 잘 안다. `holdingsOf` 는 앱이 자기 프로필 응답에 배지를 끼울 때(일괄, `AuthorDirectory` 관례) |
| `BadgeStore` | `badge`, 구현 `badge-jdbc` | 다른 보상 모듈과 같은 포트/어댑터 짝 |
| `CampaignKind` 구현 `achievement` + `CampaignSignals` | `campaign` | 지금 종류 둘 + 셋째. 조건 판정은 ①(이벤트) 몫이라는 이벤트 · 보상 설계의 결정 1 그대로(이 문서 결정 3) |
| `BadgeNotifier`(기본: `notification` 이 있으면 받은편지함) | `badge` | 앱 수여 · 운영 수여만 알린다. 지급기 경로는 `reward` 가 이미 알린다(중복 방지) |
| `PublicEndpointContributor` `badgePublicEndpointContributor`(프로필 경로 GET 하나) | `badge` | 결정 1. 공개 읽기 관례(`catalog` · `board`) 그대로 — 앱은 같은 빈 이름으로 덮어쓰거나 설정으로 끈다 |

## 3. 데이터 (두 방언, 시각 PG `timestamptz` · MySQL `datetime(6)` UTC)

**`badge-jdbc`**
- `badge_definitions(id pk 'bdg_', code varchar(64) unique, name varchar(100), description varchar(500), image_url varchar(500) null, hidden boolean, sort_order int, status ACTIVE|ARCHIVED, created_at, updated_at)` — `code` 는 앱 코드와 보상 설정이 부르는 안정된 이름(`[a-z0-9._-]`, 바꿀 수 없다).
- `badge_awards(id pk 'bda_', badge_id, account_id, status ACTIVE|REVOKED, visible boolean default true, awarded_at, revoked_at null, unique(badge_id, account_id), index(account_id, status))` — 지금 보유 상태 한 줄. 회수 뒤 다시 받으면 같은 줄이 `ACTIVE` 로.
- `badge_award_events(id pk, award_id, badge_id, account_id, action AWARDED|REVOKED, source_type APP|ADMIN|GRANT, source_ref varchar(200) null, actor_id null, reason varchar(200) null, created_at, unique(action, source_type, source_ref), index(badge_id, created_at), index(account_id, created_at))` — 운영 내역의 정본(덧붙이기만). `(AWARDED, GRANT, 지급 건 id)` 유니크가 일꾼 재시도를 한 번으로 접는다.

**`campaign-jdbc`** (새 V 파일)
- `campaign_progress(account_id, metric varchar(64), value bigint, updated_at, pk(account_id, metric))` — 수치는 캠페인과 따로 계정에 붙는다(Steam stat 처럼). 이벤트를 나중에 만들어도 값은 그대로.

## 4. 흐름

**앱 수여** `Badges.award(accountId, code, sourceRef)` — 호출자 트랜잭션에 합류. 이미 `ACTIVE` 면 그 줄을 돌려준다(멱등). 정의가 `ARCHIVED` 면 409 `BADGE.ARCHIVED`. 커밋 뒤 `BadgeNotifier`.

**업적(자동)** 앱이 `CampaignSignals.report(accountId, "notes.created", 12)` — 예: 노트 저장 서비스가 저장 뒤 자기 개수를 센다.
1. 한 트랜잭션: `campaign_progress` 를 `max(기존, 새 값)` 으로(증가만 — 낮은 값은 무시).
2. 진행 중인 `achievement` 이벤트 중 `config.metric` 이 같은 것마다, **값 이하인 모든 단계**의 보상마다 `RewardGrants.record(sourceKey = <campaign>:<account>:achv:<기준값>:<i>)`. 지급 건 유니크가 같은 단계를 한 번으로 접는다 → 나중에 만든 이벤트도 다음 보고 때 이미 넘은 단계를 준다(뒤늦은 소급은 「현재 값 한 번 보고」로 끝).
3. 일꾼 → `BadgeRewardIssuer.issue` → 배지 수여(`GRANT`, 지급 건 id) · 같은 단계의 포인트 · 쿠폰도 각자 지급기로 → `reward` 의 「받았어요」 알림.
- 설정 모양: `{metric: "notes.created", milestones: [{value: 1, rewards: [{type: "badge", params: {code: "first-note"}}]}, {value: 10, rewards: [{type: "badge", params: {code: "note-10"}}, {type: "point", params: {amount: "100"}}]}]}` — 출석 `milestones` 와 같은 꼴, 단계 20개까지.

**회수** 운영자만 · 사유 필수 → 줄 `REVOKED` + 내역 `REVOKED`. 같이 받은 포인트 · 쿠폰 · 진행 수치는 그대로.

**탈퇴** `AccountErasureListener`: `badge`(보유 · 내역), `campaign`(진행 수치 — 기존 고리에 더한다).

## 5. API (사용자 경로는 붙이면 켜짐 `skeleton.badge.http.enabled`, 운영은 `skeleton.badge.admin.enabled` 기본 꺼짐 → 404)

| 경로 | 하는 일 | 권한 |
|---|---|---|
| `GET /api/v1/badges/me` | `{earned: [배지 + awardedAt + visible], locked: [정의]}` — 숨은 정의는 이름 · 설명 · 그림을 가린다, `ARCHIVED` 는 받은 것에만 | 로그인 |
| `PATCH /api/v1/badges/me/{awardId}` | `{visible}` 프로필에 보일지 | 로그인 · 본인 |
| `GET /api/v1/badges/accounts/{accountId}` | 그 계정의 `ACTIVE` · `visible` 배지(순서 `sort_order`). 응답 `{badges: [{code, name, description, imageUrl}]}` — 이것뿐 | **비로그인 공개**(결정 1) |
| `GET/POST /api/v1/admin/badges` · `PUT /{id}` · `PATCH /{id}/status` | 정의 목록(보유자 수 포함) · 만들기 · 고치기(`code` 제외) · 보관/되살리기 | `badge.manage` |
| `POST /api/v1/admin/badges/{id}/awards` | `{accountIds(≤100), reason}` 수동 수여, 결과 `{awarded, alreadyHeld}` | `badge.manage` |
| `POST /api/v1/admin/badge-awards/{awardId}/revoke` | `{reason}` | `badge.manage` |
| `GET /api/v1/admin/badge-awards?badgeId&accountId&cursor` | 수여 · 회수 내역(최신순 커서). 계정 이름은 `AccountLabels` — `account.read` 도 있는 운영자에게만(쿠폰 선례) | `badge.manage` |

- **공개 프로필 경로(결정 1)**
  - 열기: `skeleton.badge.http.allow-anonymous-read`(**모듈 기본 `false`** — 스켈레톤 기본은 옵션 없음, `board` 의 같은 이름 설정과 같다. 견본 앱이 `true` 로 켠다 — 리뷰 반영). `true` 이고 `http.enabled` 면 `badgePublicEndpointContributor` 가 `GET {base}/accounts/*` 하나만 공개 경로로 넣는다. 설정이 없으면 로그인 필요(401).
  - 내보내지 않는 것: 보유 줄 id · 정의 id · `awardedAt` · 출처 · 수여자 · 사유 · 회수 상태 · 개수 합계. **숨긴(`visible=false`) 배지와 받지 않은 숨은 업적은 어떤 값으로도 나가지 않는다** — 질의 단계에서 `ACTIVE ∧ visible` 만 읽는다(걸러 내기가 아니라 안 읽기).
  - 없는 계정 · 탈퇴한 계정 · 배지 없는 계정 · 전부 숨긴 계정은 모두 같은 `200 {badges: []}` — 계정이 있는지 알려 주지 않는다.
  - 속도 제한: 배지 전용 한도는 만들지 않는다. 다른 공개 읽기(`catalog` 상품 · `board` 비회원 읽기)처럼 platform `skeleton.web.rate-limit`(IP 기준, 기본 꺼짐)을 따른다 — 공개로 여는 앱은 이것을 켜라고 모듈 문서에 적는다.
  - **경고(모듈 문서 · 견본 주석)**: 이 경로는 주소에 계정 id 를 담는다. `author-profile`(필명 프로필) 화면에 `ProfileBadges` 를 붙이면 필명 여러 개가 같은 계정 id · 같은 배지 묶음으로 이어져 한 사람임이 드러난다 — 필명 화면에는 붙이지 말 것.
- 권한은 `PermissionDeclaration("badge", [badge.manage])`, 기본 역할 없음(전권만). 운영 기록 `OperatorAuditLog`: `badge.defined` · `badge.updated` · `badge.awarded` · `badge.revoked`(대상은 id 만).
- `campaign` 쪽 새 HTTP 없음 — 업적 이벤트는 기존 `/api/v1/admin/campaigns` 로 만든다(`kind: "achievement"`). `CampaignSignals` 는 서버 API 만(포인트 사용과 같은 이유 — 브라우저가 수치를 보고하면 안 된다).

## 6. 화면 (react `@skeleton/rewards`)

- 새 하위 경로 `@skeleton/rewards/badge`: `BadgeShelf`(내 배지 — 받은 것 격자 + 아직인 것 흐리게, 숨은 것은 「?」, 배지마다 「프로필에 보이기」 스위치), `ProfileBadges`(`accountId` 를 받는 작은 줄, 로그인 없이도 그린다, 없으면 아무것도 안 그림 — 공개 응답 모양만 안다), `createBadgeApi` · 훅. `@skeleton/ui` 의 상태 표시 `Badge` 와 이름이 겹치지 않게 부품은 `BadgeTile` 로. 그림(결정 4): `image_url` 이 있으면 그림, 없으면 이름 첫 글자 원 — 앱은 `renderImage` prop 으로 바꾼다. 운영 폼은 주소 입력 칸 하나(올리기 없음).
- `@skeleton/rewards/admin`: `AdminBadges`(목록 · 보유자 수 · 만들기/고치기 폼 · 보관 · 「수동 수여」 대화상자 — 기존 `AccountSearch` 재사용), `AdminBadgeAwards`(내역 표 · 필터 · 회수 대화상자 사유 필수). `RewardListEditor` 에 종류 `badge`(정의 고르기), `AdminCampaigns` 에 종류 `achievement`(수치 이름 + 단계 목록 — 출석 단계 편집 꼴 재사용). 단추는 `can(key)` 로 숨긴다, `REWARDS_PERMISSIONS.badge = 'badge.manage'`.
- 문구 ko/en 라벨, 부품마다 스토리 + `play`, 화면은 Patterns(목록 · 폼)에서 복사. 눌리는 것은 눌리게.

## 7. 견본 앱 (kotlin `apps/sample` + react `apps/sample`)

조립 `badge` · `badge-jdbc`. 씨앗(로컬만): 정의 「첫 노트」 · 「노트 10개」 · 「얼리 버드」(숨김 아님) · 「비밀 손님」(숨김), 업적 이벤트 `notes.created` 단계 1 → 첫 노트, 10 → 노트 10개 + 100포인트. 노트 서비스가 만들기 뒤 `report`. 「얼리 버드」는 운영자 수동 수여 시연.
눌러 볼 흐름: ① user 노트 하나 → 몇 초 안에 알림 「배지 첫 노트를 받았어요」 → `/rewards/badges` 에 보임 ② 로그아웃 상태로 그 계정 프로필 배지 줄에 보임 → 숨기기 → 빠짐 ③ admin 이 「얼리 버드」 수동 수여 → 알림 · 내역 ④ admin 회수(사유) → 사용자 화면에서 사라지고 내역에 회수 줄.

## 8. 검증 (깨지면 사고 나는 것만)

**kotlin**
- 멱등: 같은 지급 건을 일꾼이 두 번 → 수여 한 번 · 내역 한 줄. `Badges.award` 같은 `sourceRef` 두 번 · 동시 두 번 → 한 줄. 이미 가진 배지 수동 수여 → `alreadyHeld`.
- 업적: 값 9 → 10 보고에 단계 10 한 번, 같은 10 다시 보고 → 추가 없음, 5 보고 → 값 그대로, 이벤트를 나중에 만든 뒤 다음 보고에서 이미 넘은 단계 지급, 꺼진 · 기간 밖 이벤트는 지급 없음, 모르는 배지 코드 · 보관된 배지를 보상으로 저장 → 400 `CAMPAIGN.CONFIG_INVALID`.
- 숨김: `locked` 의 숨은 정의가 이름 · 설명 · 그림을 내지 않는다(응답 JSON 에 문자열이 없음). 남의 `awardId` PATCH → 404.
- 공개 프로필: 인증 없이 200. 회수된 · `visible=false` 배지 · 받지 않은 숨은 정의의 코드 · 이름이 응답 JSON 문자열에 없다. 배지 객체의 키가 정확히 `code · name · description · imageUrl` 넷. 없는 계정 = 전부 숨긴 계정 = 빈 계정(응답 바이트 같음). 설정이 없으면(기본) 비로그인 401, `allow-anonymous-read=true` 면 200.
- 권한: 운영 경로 꺼짐 404 · 권한 없음 403 · `account.read` 없으면 계정 이름 없음. 회수 사유 빈 값 400.
- 조립: `badge` 만(지급기 없이 · `notification` 없이 `noOptionalTest`), `campaign` + `reward` 에 `badge` 없음(종류 `badge` 저장 거절). 두 방언 `dbTest`, `MigrationLockTest` · `CapabilitiesCatalogTest` · `ModuleDocumentationTest`.

**react**: `BadgeShelf` 숨은 배지 가림 · 스위치 · 빈 상태 스토리 `play`, 관리 폼 검증(코드 형식 · 회수 사유 필수), api 경로 기록 시험, `capabilities` 검사.
**e2e**(react sample + kotlin sample): §7 ① · ② · ③ · ④ 한 여정(② 는 로그아웃 상태 프로필 줄 확인만).

## 결정됨 (형, 2026-10-29)

1. **프로필 배지 공개 범위 — 비로그인 공개까지.** 형: 「비로그인 공개까지」 (구현: 모듈 기본은 꺼짐, 형의 앱(견본)이 `allow-anonymous-read: true` 로 켠다 — 「스켈레톤 기본은 아무 옵션 없는게 맞고 내 취향(override) 을 따로」)
   - 로그인하지 않은 사람도 `GET /api/v1/badges/accounts/{accountId}` 로 본다. 본인이 배지마다 숨기는 `visible` 은 그대로.
   - 숨긴 배지 · 받지 않은 숨은 업적은 공개 응답에 절대 나가지 않는다 — 노출 필드는 `code · name · description · imageUrl` 넷뿐, 계정 유무도 드러내지 않는다(§5).
   - 속도 제한은 레포 공개 읽기 관례(platform `skeleton.web.rate-limit`)를 따르고 배지 전용은 만들지 않는다.
   - `author-profile` 과 같이 쓰면 계정이 이어질 수 있다는 경고를 모듈 문서 · 견본에 남긴다.
2. **같이 주는 자리 — 업적 단계의 보상 목록에 나란히.** 형: 「각자 각각이라 조립 하는 식으로 가능 하잖아?」
   - 배지 · 포인트 · 쿠폰은 서로를 모르고, 조립해서 같이 준다. `badge` 는 `reward` · `coupon` · `point` 에 의존하지 않는다(§2 조립 원칙).
3. **조건을 세는 자리 — `campaign` 새 종류 `achievement` + 앱이 현재 값을 보고.** 형: 「캠페인의 업적 종류 (Recommended)」
4. **배지 그림 — 그림 주소(`image_url`) 문자열 하나.** 형: 「그림 주소만 (Recommended)」 올리기(`storage`)는 다음 판.
