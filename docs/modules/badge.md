# badge

배지 — 앱이 정의하는 업적 배지, 계정의 보유(내 배지 · 프로필 배지), 운영자의 정의 관리 · 수동 수여 · 회수 · 내역, 그리고 계약 구현 하나: 보상 지급기 `badge`(`RewardIssuer`, 이벤트가 지급 건으로 주는 배지).
**포인트 · 쿠폰 · 이벤트를 모른다** — `reward` · `coupon` · `point` · `campaign` 에 의존하지 않는다(`compileOnly` 도 아니다). 「배지와 포인트를 같이 주기」는 업적 단계의 보상 목록에 `[{type: badge}, {type: point}]` 를 나란히 적는 조립으로만 한다.
저장은 어댑터 `badge-jdbc` 가 한다(메모리 구현 없음).

**정의**(`BadgeDefinitions`): `code`(`[a-z0-9._-]{1,64}`, 앱 코드와 보상 설정이 부르는 이름이라 **만든 뒤 바꾸지 않는다**) · `name` · `description` · `imageUrl`(**그림 주소 문자열 하나** — `http(s)://` 또는 `/` 로 시작하는 앱 안 경로, 올리기는 없다) · `hidden`(받기 전에는 이름 · 설명 · 그림을 가린다) · `sortOrder` · 상태 `ACTIVE`/`ARCHIVED`.
보관된 정의는 더 줄 수 없고(409 `BADGE.ARCHIVED`) 받은 사람에게는 그대로 보인다. 코드 형식이 틀리면 400 `BADGE.CONFIG_INVALID`, 이미 있으면 409 `BADGE.CODE_TAKEN`.

**수여**: 계정당 한 번(여러 번 받는 배지는 없다 — 단계는 정의를 여러 개로). 길은 셋 — ① 앱이 `Badges.award(accountId, code, sourceRef)`(서버 API, 브라우저에 열려 있지 않다) ② 운영자 수동 수여(계정 100개까지) ③ 보상 지급기 `badge`(params `code`, `(GRANT, 지급 건 id)` 로 멱등).
`Badges.award` 는 호출자 트랜잭션에 합류한다. 이미 가졌으면 그 줄을 돌려준다(`newlyAwarded=false`). **같은 `sourceRef` 로 이미 처리한 호출은 회수된 뒤에도 다시 주지 않는다** — `sourceRef` 에 원인(`note:123`)을 적는다(60자까지; 계정 · 코드와 묶어 저장하므로 다른 계정 · 다른 배지와 겹쳐도 된다). 회수한 뒤 다른 원인으로 다시 받으면 같은 보유 줄이 `ACTIVE` 로 돌아온다.
알림은 앱 수여 · 운영 수여만 커밋 뒤에 보낸다(`BadgeNotifier`, 기본: `notification` 이 있으면 받은편지함, 알림 id `bdg_<내역 id>`). 지급기 경로는 `reward` 가 이미 「받았어요」를 알리므로 중복으로 보내지 않는다.
**회수**: 운영자(`badge.manage`) 또는 앱(`Badges.revoke`)만, **사유 필수**(빈 값 400). 보유 줄 `REVOKED` + 내역에 사유가 남는다. 같이 받은 포인트 · 쿠폰은 되돌리지 않는다(국룰 — 회수는 배지만).
`Badges.holdingsOf(accountIds)` 는 앱이 자기 프로필 응답에 배지를 끼울 때 쓴다 — 지금 **프로필에 보이는**(보유 ∧ 본인이 숨기지 않은) 배지만 `sort_order` 순으로 준다(계정 일괄, 배지 없는 계정은 빈 목록).

**공개 프로필(앱이 열면 비로그인)** — `GET /api/v1/badges/accounts/{accountId}`:
- 앱이 `skeleton.badge.http.allow-anonymous-read=true` 로 열면 **로그인 없이 읽는다**(**모듈 기본은 `false` - 로그인 필요, 401**; `board` 의 같은 이름 설정과 같다. 스켈레톤 기본은 옵션 없음이고 열기는 앱의 선택이다 - 견본 앱이 켠다). 열면 `badgePublicEndpointContributor` 가 이 경로 하나만 공개로 넣는다(`GET {base}/accounts/*` 꼴, 내 배지 `me` 는 아니다). 앱은 같은 빈 이름으로 덮어쓸 수 있다.
- 응답은 `{badges: [{code, name, description, imageUrl}]}` — **이 넷뿐**이다(보유 줄 id · 정의 id · 받은 시각 · 출처 · 수여자 · 사유 · 회수 상태 · 개수를 담지 않는다). 숨긴(`visible=false`) 배지와 회수된 배지, 받지 않은 숨은 업적은 **어떤 값으로도 나가지 않는다** — 걸러 내는 것이 아니라 저장소 질의가 `ACTIVE ∧ visible` 인 줄만 읽는다.
- 없는 계정 · 탈퇴한 계정 · 배지 없는 계정 · 전부 숨긴 계정은 모두 같은 `200 {badges: []}` — 계정이 있는지 알려 주지 않는다.
- 속도 제한은 배지 전용 한도를 만들지 않는다. 다른 공개 읽기(`catalog` 상품 · `board` 비회원 읽기)처럼 platform `skeleton.web.rate-limit`(IP 기준, 기본 꺼짐)을 따른다 — **공개로 여는 앱은 이것을 켜라**(`path-pattern: /api/v1/badges/accounts/**` 로 이 경로에만 걸 수 있다; 견본 앱이 그렇게 한다).
- **경고**: 이 경로는 주소에 계정 id 를 담는다. `author-profile`(필명 프로필) 화면에 프로필 배지를 붙이면 필명 여러 개가 같은 계정 id · 같은 배지 묶음으로 이어져 한 사람임이 드러난다 — **필명 화면에는 붙이지 말 것**.

**동작 메모**: ① 이미 가진 배지를 지급 건(보상 지급기)으로 다시 주면 지급 건은 `ISSUED` 로 끝나되 알림은 나가지 않는다(지급기가 `IssuedReward(notify=false)` 로 알려 `reward` 일꾼이 건너뛴다). ② 수동 수여는 `platform` 의 `AccountFacts`(`account` 가 내놓는다)로 계정이 있는지 확인해, 없는 id 는 수여하지 않고 응답의 `unknownAccountIds` 에 따로 센다. `AccountFacts` 구현이 없으면 확인 없이 준다.
③ `Badges.award` 가 돌려주는 `BadgeHolding` 은 `newlyAwarded`(이번 호출이 새로 줬나)와 `active`(지금 보유 중인가)가 따로다 - 회수 뒤 같은 `sourceRef` 로 다시 부르면 `newlyAwarded=false`, `active=false`.

**내 배지** `GET /api/v1/badges/me`(로그인): `{earned: [{awardId, code, name, description, imageUrl, hidden, archived, visible, awardedAt}], locked: [...]}`. `locked` 는 `ACTIVE` 정의 중 못 받은 것 — **숨은 정의는 `{hidden: true}` 뿐이고 코드 · 이름 · 설명 · 그림이 모두 `null`** 이다. `PATCH /api/v1/badges/me/{awardId} {visible}` 로 프로필에 보일지 바꾼다(본인 줄만 — 남의 줄 · 회수된 줄은 404 `BADGE.NOT_FOUND`).

**탈퇴**: `AccountErasureListener` 가 그 계정의 보유 줄과 수여 · 회수 내역을 지운다(정의는 계정과 무관해 그대로).

운영자 경로(`skeleton.badge.admin.enabled=true` 일 때만, 꺼져 있으면 404) — 모든 호출에 `badge.manage`(기본 역할 없음 — 전권 역할만, 익명 401 · 권한 없음 403):

| 메서드 · 경로 | 하는 일 |
|---|---|
| `GET /api/v1/admin/badges` | 정의 목록(`sortOrder` 순) + 보유자 수 `holders` |
| `POST /api/v1/admin/badges` | 정의 만들기 → 201. 운영 기록 `badge.defined` |
| `PUT /api/v1/admin/badges/{id}` | 이름 · 설명 · 그림 · 숨김 · 순서 고치기(`code` 는 읽지 않는다). `badge.updated` |
| `PATCH /api/v1/admin/badges/{id}/status {status}` | 보관/되살리기. `badge.updated` |
| `POST /api/v1/admin/badges/{id}/awards {accountIds, reason?}` | 수동 수여 → `{awarded[], alreadyHeld[], unknownAccountIds[]}` (계정 100개까지). `badge.awarded` |
| `POST /api/v1/admin/badge-awards/{awardId}/revoke {reason}` | 회수(사유 필수; 없거나 이미 회수된 줄은 404). `badge.revoked` |
| `GET /api/v1/admin/badge-awards?badgeId&accountId&cursor&size` | 수여 · 회수 내역, 최신순 커서(`size` 기본 50, 최대 200). `accountLabel`(이메일, 없으면 아이디)은 운영자가 `account.read` 도 가질 때만 채운다 |

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:badge"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `notification` (받았어요 알림 — 클래스패스에 있을 때만 켜진다), Spring Security (경로를 등록할 때만 필요하다) |
| 설정 접두사 | `skeleton.badge` — [docs/config/modules/badge.yml](../config/modules/badge.yml) |
| 기본 동작 | 켜짐. 사용자 경로 켜짐(프로필 배지의 비로그인 읽기는 꺼짐 - 앱이 `allow-anonymous-read` 로 연다), 운영자 경로 꺼짐. 보상 지급기는 저장소가 있으면 빈으로 선다. |
| 부팅에 필요한 것 | 저장소 포트 `BadgeStore` — `badge-jdbc` 가 낸다. 없으면 서비스 빈이 켜지지 않는다. |
| 교체 지점 | `BadgeStore`, `BadgeDefinitions`, `BadgeAwards`, `Badges`, `BadgeCatalog`, `BadgeNotifier`, `BadgeController`, `BadgeAdminController`, 지급기 `badgeRewardIssuer`, 공개 경로 `badgePublicEndpointContributor` |
| 마이그레이션 | 없음 (스키마는 `badge-jdbc`) |
| 프론트 짝 | `@skeleton/rewards` |
| 테스트 | `modules/badge/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
