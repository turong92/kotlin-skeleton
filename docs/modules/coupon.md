# coupon

쿠폰 — 마케팅이 **행 하나**로 만드는 조합형 정의(혜택 단계 목록), 계정의 쿠폰함, 공용 코드 등록, 그리고 두 가지 계약 구현: 보상 지급기 `coupon`(`RewardIssuer`, 이벤트가 지급 건으로 주는 쿠폰)과 결제 할인 수단 `coupon`(`DiscountSource`, 주문 금액을 깎는다).
이벤트(`campaign`)와 결제(`payment`)는 이 모듈을 모른다 — 둘 사이의 계약은 `platform` 의 `common/reward` · `common/discount` 에 있다. 저장은 어댑터 `coupon-jdbc`가 한다(메모리 구현 없음).

**정의**(`CouponDefinitions`): `stages` = 순서대로 이어지는 혜택 단계 `[{type: FREE|PERCENT|FIXED, value, maxDiscount?, periods}]`, 5개까지, `periods` 가 `null`(영원)인 단계는 마지막만. 정률은 1~100, 정액은 `currency` 필요, `minAmount` 도 `currency` 필요.
예: 1,000원 `[FIXED 1000 ×1]` · 30%(최대 5,000) `[PERCENT 30 max 5000 ×1]` · 무료 이용권 `[FREE ×1]` · 3달 무료 `[FREE ×3]` · 첫 결제 무료 + 이후 3달 50% `[FREE ×1, PERCENT 50 ×3]` · 평생 10% `[PERCENT 10 ×영원]`. 단건 결제는 첫 단계만 본다.
범위 `scope{purposes: ONE_TIME|SUBSCRIPTION, itemTypes, itemRefs, planCodes}`(빈 목록 = 전부) · `firstPurchaseOnly` · `validDays`/`validUntil`(이른 쪽이 만료) · `issueLimit` · `perAccountLimit`(기본 1). 정의는 만들면 내용을 고치지 않는다 — 상태(`ACTIVE`·`PAUSED`·`ARCHIVED`)만 바꾼다. 발급만 `ACTIVE` 에서 한다; 이미 발급된 쿠폰은 정의가 멈춰도 쓸 수 있다.
잘못된 정의는 400 `COUPON.DEFINITION_INVALID`.

**발급**(`CouponIssuance.issue(definitionId, accountId, source, sourceRef): Coupon?`): 정의 줄을 잠그고 발급 한도 · 계정당 한도를 센 뒤 한 장을 넣는다 — 같은 `(source, sourceRef)` 는 한 장으로 접히고(같은 장을 돌려준다), 한도에 막히면 `null`.
출처는 `GRANT`(지급 건 id) · `CODE`(`<코드>:<계정>`) · `ADMIN`. 열린 트랜잭션에 합류하므로 지급기가 일꾼의 트랜잭션 안에서 불러도 된다.
**공용 코드**(`CouponCodes`): 코드 하나를 계정당 한 번 등록해 쿠폰함으로 받는다 — 없는/끝난/멈춘 코드 400 `CODE_INVALID`, 이미 등록 409 `CODE_USED`, 한도 다 참 409 `CODE_EXHAUSTED`. 코드는 대문자로 맞춘다. `create(definitionId, code?, maxRedemptions?, expiresAt?)` 는 코드를 주면 그 글자(앱의 씨앗용), 아니면 읽기 쉬운 열 글자를 만든다. 코드를 발행 기간 밖에서 등록하면 `ISSUE_CLOSED` 로 거절되므로 그 코드가 있다는 사실이 드러난다(동작은 그대로다) - **운영자 지정 코드는 공개 직전에 만들 것**.

**지급기 `coupon`**: params `definitionId`. `validate` 는 정의가 있는지 본다(이벤트 설정 저장 때). `issue` 는 `(GRANT, 지급 건 id)` 로 멱등. 정의가 없거나 발급 중이 아니거나 한도에 막히면 `RewardPermanentFailure`(운영자가 풀고 「다시 지급」).

**할인 수단 `coupon`**(`kind = "coupon"`, `order = 100`): 쿠폰 id 가 `ref`. 쓸 수 있으면 `quote`/`reserve` 가 정의의 단계를 제안으로 돌려준다. 상태 전이는 모두 조건부 갱신:
- `reserve` `AVAILABLE → RESERVED(holder=주문)` — 동시에 둘이 와도 하나만. 남의 쿠폰 · 이미 쥔/쓴/만료/회수된 쿠폰은 `UNAVAILABLE`, 범위 밖 · 최소 금액 미달 · 통화 다름 · `firstPurchaseOnly` 인데 첫 결제가 아님은 `NOT_APPLICABLE`.
- `confirm` `RESERVED(holder) → USED` `CONFIRMED`. 이미 `USED(holder)` 면 `ALREADY`. 주문이 풀린 뒤(`release`) 늦게 결제가 되면 아직 `AVAILABLE`(또는 기한이 지나 `EXPIRED`)이고 그 주문이 마지막 주인이면 다시 잡아 `USED`(`CONFIRMED`). 그사이 다른 주문이 쥐었으면(주인이 바뀌었다) `CONFLICT`.
- `release`/`restore` 는 holder 가 맞을 때만 `AVAILABLE`로(`RESERVED` 에서 / `USED` 에서), 기한이 지났으면 `EXPIRED`. holder 가 다른 호출은 무시된다. 쿠폰은 풀려도 `holder_ref` 를 지우지 않는다 — 늦은 `confirm` 이 주인을 찾는 단서다.
만료(`EXPIRED`)는 저장 전에도 읽을 때 판정한다(`CouponExpiry`).

**발행 기간 · 사용 시작**: 정의에 `issueStartsAt` · `issueEndsAt`(발행 기간 `[시작, 끝)` - 시작은 포함, 끝은 제외)과 `validFrom`(사용 시작)을 줄 수 있다(모두 선택, `issueStartsAt < issueEndsAt` · `validFrom < validUntil` 아니면 400 `DEFINITION_INVALID`).
발행 기간 밖이면 운영자 발급 · 공용 코드 등록 · 보상 지급(`RewardIssuer`) 모두 409 `COUPON.ISSUE_CLOSED`(같은 지급 건이 이미 만든 장은 기간이 닫혀도 같은 장을 돌려준다). 사용 시작 전의 쿠폰은 쿠폰함에는 `AVAILABLE` 로 보이되 응답의 `usableFrom` 이 그 시각이고,
결제 할인의 견적 · 예약은 그 시각 전까지 `UNAVAILABLE` 이다(클라이언트는 `usableFrom` 이 지난 것만 결제 선택지에 보인다). `validUntil` / `validDays` 의 만료와는 별개다.

**현황 · 발급 내역**(운영): 한 정의의 쿠폰 수를 지금 시각으로 읽은 상태별로(`issued` · `available` · `reserved` · `used` · `expired` · `revoked`; 기한이 지난 `AVAILABLE` 은 `expired`)와 `discountTotals`(확정된 쿠폰이 실제로 깎은 금액, 통화별).
**금액의 출처**: 쿠폰 표에는 할인액이 없다 — 금액은 결제 장부에만 있고, `platform` 의 읽기 전용 조회 포트 `DiscountTotals.confirmedTotals(kind, sourceRefs)`(`common/discount`)로 묻는다. `payment-jdbc` 가 장부의 확정(`CONFIRMED`) 할인 줄로 답하는 빈을 낸다(전액 환불로 복원된 줄은 빠진다). 쓰인(`USED`) 쿠폰 id 를 1000 개씩 끊어 묻고 통화별로 합친다. 구독 쿠폰은 첫 주문의 할인 줄에 더해 **갱신 주문의 할인액**(결제된 갱신의 `discount_amount`)도 합쳐, 「첫 달 무료 + 3달 50%」면 실제로 깎아 준 전부가 나온다(전액 환불된 갱신은 뺀다). 그 빈이 없는 조립(`payment-jdbc` 미사용)은 빈 목록이다 - 「0원」이 아니라 「모른다」. 모듈끼리 표 이름에 기대지 않는다.

**대시보드 기여**(`coupon-jdbc`, 기본 꺼짐 `skeleton.coupon.dashboard.enabled=true` + `dashboard` 모듈이 있을 때만): 타일 `coupon.used`(기간 안 쿠폰 사용 수, 일별 그래프 — 전액 환불로 되돌려진 것은 세지 않는다)와 순위 `coupon.top-used`(「앱 통계」 그룹, 많이 쓰인 쿠폰). 모듈은 메커니즘만 내고 켜는 것은 앱이다.

**탈퇴**: `AccountErasureListener` 가 그 계정의 쿠폰 줄을 **지우지 않고** `account_id` 만 비식별값(`ErasureRequest.tombstone`)으로 바꾼다 - 결제 장부의 `TOMBSTONE` 과 같은 방식이라 과거의 발급 · 사용 현황과 합계 · 대시보드 수가 줄지 않는다. 쓰지 않은(`AVAILABLE`, 기한 안) 쿠폰은 `REVOKED` 로 둬서 현황의 「쓸 수 있는」 수에 죽은 쿠폰이 남지 않게 한다(기한이 지난 것은 그대로 `EXPIRED` 로 읽힌다). 쥔 `RESERVED` 는 그 주문이 풀거나 확정하고 - 풀릴 때 주인이 비식별값이면(`deleted:` 로 시작) `AVAILABLE` 대신 `REVOKED` 로 둔다 - 쓴 `USED` 는 그대로다. 따라서 현황의 `revoked` 에는 운영자가 회수한 것과 탈퇴로 무효가 된 쿠폰이 함께 들어간다. 공용 코드로 받은 줄의 출처 키(`<코드>:<계정>`)는 지우고, 코드 등록 줄은 삭제한다. 결제 장부의 할인 내역은 장부라 남는다.

사용자 경로(서블릿 웹 앱 + Spring Security 필요, `skeleton.coupon.http.enabled` 기본 켜짐, 호출자 = `Authentication.name`):

| 메서드 · 경로 | 하는 일 |
|---|---|
| `GET /api/v1/coupons?status=AVAILABLE\|PAST` | 쿠폰함, 새것부터. `AVAILABLE` 은 쓸 수 있는 것(주문이 쥔 `RESERVED` 포함), `PAST` 는 쓴 · 만료 · 회수. `{id, name, status, expiresAt, stages[{type, value, maxDiscount, periods}], scope{purposes, itemTypes, planCodes}, currency, minAmount, usableFrom}` |
| `POST /api/v1/coupons/redeem {code}` | 공용 코드 등록 → 201 쿠폰. 400 `COUPON.CODE_INVALID` · 409 `CODE_USED` · 409 `CODE_EXHAUSTED` · 발행 기간 밖 409 `ISSUE_CLOSED` |

운영자 경로(`skeleton.coupon.admin.enabled=true` 일 때만, 꺼져 있으면 404) — 모든 호출에 `coupon.manage`(기본 역할 없음 — 전권 역할만, 익명 401 · 권한 없음 403):

| 메서드 · 경로 | 하는 일 |
|---|---|
| `GET /api/v1/admin/coupon-definitions` | 정의 목록(새것부터) + `issuedCount` |
| `POST /api/v1/admin/coupon-definitions` | 정의 만들기 → 201 정의 (`value.id`). 본문 `issueStartsAt` · `issueEndsAt` · `validFrom` 선택 |
| `PATCH /api/v1/admin/coupon-definitions/{id}/status {status}` | 상태 바꾸기 |
| `GET /api/v1/admin/coupon-definitions/{id}/stats` | 현황 `{issued, available, reserved, used, expired, revoked, discountTotals: [{currency, amount}]}`. 없는 정의 404 `DEFINITION_NOT_FOUND` |
| `GET /api/v1/admin/coupon-definitions/{id}/coupons?status&cursor&size` | 이 정의로 발급된 쿠폰, 새것부터 커서(`size` 기본 50, 최대 200). `{id, accountId, status, issuedAt, expiresAt, usableFrom, usedAt, orderRef}` - `orderRef` 는 쥐고 있거나(`RESERVED`) 쓴(`USED`) 주문. `status` 는 지금 시각으로 읽은 상태(`EXPIRED` 는 기한 지난 `AVAILABLE` 포함) |
| `POST /api/v1/admin/coupon-definitions/{id}/issue {accountIds}` | 계정마다 한 장 → `{issued[], skipped[]}`(한도에 막힌 계정은 건너뜀, 발급 중이 아니면 409 `DEFINITION_NOT_ACTIVE`, 발행 기간 밖이면 409 `ISSUE_CLOSED`). 운영 기록 `coupon.issued` |
| `POST /api/v1/admin/coupon-definitions/{id}/codes {code?, maxRedemptions?, expiresAt?}` | 공용 코드 → 201 `{code}` (본문 생략 가능) |
| `GET /api/v1/admin/coupons?accountId=` | 한 계정의 쿠폰 전부(사용자 쿠폰 꼴 + `accountId` · `definitionId`) |
| `POST /api/v1/admin/coupons/{id}/revoke` | `AVAILABLE` 만 회수(그 밖 409 `NOT_REVOCABLE`) |

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:coupon"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 (Spring Security 는 경로를 등록할 때만 필요하다) |
| 설정 접두사 | `skeleton.coupon` — [docs/config/modules/coupon.yml](../config/modules/coupon.yml) |
| 기본 동작 | 켜짐. 사용자 경로 켜짐, 운영자 경로 꺼짐. 지급기 · 할인 수단은 저장소가 있으면 빈으로 선다. |
| 부팅에 필요한 것 | 저장소 포트 `CouponStore` — `coupon-jdbc` 가 낸다. 없으면 서비스 빈이 켜지지 않는다. |
| 교체 지점 | `CouponStore`, `CouponDefinitions`, `CouponIssuance`, `CouponCodes`, `CouponWallet`, `CouponController`, `CouponAdminController`, 지급기 `couponRewardIssuer`, 할인 수단 `couponDiscountSource` |
| 마이그레이션 | 없음 (스키마는 `coupon-jdbc`) |
| 프론트 짝 | `@skeleton/rewards` |
| 테스트 | `modules/coupon/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
