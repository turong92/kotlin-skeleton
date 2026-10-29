# 상품 · 가격 카탈로그(월간 · 연간 · 단건 · 행사가 · 통화별) — 설계

> 2026-10-29 확정. 출발점: 브레인스토밍 초안(2026-10-12). 초안 뒤로 들어간 것(구독 2차 플랜 올리기, 해지·환불, Paddle, 나라 라우팅, 프로젝트별 결제사 키, 이벤트·보상·쿠폰, 결제 면제)을 지금 코드와 맞춰 고쳤다(§11).
> 대상: kotlin-skeleton(새 `catalog` · `catalog-jdbc`, 기존 `payment` · `payment-jdbc` · `payment-paddle` · `platform` · `coupon` · `apps/sample`) + react-skeleton(`@skeleton/payment` · `@skeleton/payment/admin` · `@skeleton/marketing` · `apps/sample`).
> 근거 코드: `SubscriptionPlans` · `PaymentCatalog`(`PaymentAppContracts.kt`), `PaymentCountryRouting`, `PaymentProviderRouter.servesCurrency`, `SubscriptionPlanChanges.isUpgrade`, `SubscriptionLifecycle.onOrderPaid`, `SubscriptionRenewTaskHandler`, `SubscriptionCancellations.monthlyPrice`, `PaymentPushGateway.priceIdOf` · `PaddleProperties.prices`, `PaymentPushedEvents`, `DiscountTarget` · `CouponDiscountSource`.

## 형 결정

**2026-10-12 브레인스토밍(원문 요지)**

| # | 물음 | 결정 |
|---|---|---|
| 1 | 연간 | **A. 한 상품에 가격 여럿** |
| 2 | 관리 | **DB + 운영자 화면** |
| 3 | 단건 | **같은 표** |
| 4 | 할인 | 둘로 나눔: 이번엔 **판매 기간 붙은 행사가**. 쿠폰 · 첫 N회 · 재화 지급은 혜택 설계(→ 이미 들어감: `2026-10-28-events-rewards-design.md`) |
| 5 | 갈아타기 | 올리기는 바로(차액), **내리기(등급↓ · 연→월)는 다음 갱신부터 예약** |
| 6 | 연→월 바로 | **A'. 날짜 맞춘 기간 환산**: 남은 돈 → 무료 개월, 끝수는 다음 결제에서 한 번 차감, 결제일 유지, 적립금 없음 |
| 7 | 일할 | **일 단위**(→ `Proration` 으로 이미 들어감) |

**2026-10-29 (이 설계의 물음 1~10)**

- 통화 원문: 「상품은 디비에 따로 있잖아 그걸 보여주고 pg는 뒤에서 연결해야하는거아냐」 → 화면에 보이는 상품 · 가격의 원본은 우리 DB 이고 결제사는 가격 줄 뒤에 링크로 붙는다. **1차에 통화별 가격 행**(같은 상품 · 주기에 KRW 행 · USD 행), 방문 나라 라우팅이 고른 결제사 · 통화의 행을 보이고 판다(KR → KRW · 토스, 그 밖 → USD · Paddle). 랜딩 표시는 Paddle 미리보기가 아니라 DB 가격(§4.6).
- 나머지는 모두 추천안: 1차 · 2차로 나눔 / Paddle 가격 id 는 링크 / 쿠폰은 행사가 위에 / 행사가 가입자는 그 가격 유지 / A' 는 다음에 / 등급↑ + 주기↓ 는 다음 갱신 예약 / 초안의 제안 셋(가격 불변 · 무료 플랜은 표 밖 · 가격 인상 고지 이번엔 안 함) 그대로 / 응원하기는 코드 그대로 / 월→연 남은 몫은 실제로 낸 돈의 일할.

## 0. 목표 · 비목표

**목표**: 찍은 앱이 모듈 두 줄(`catalog` · `catalog-jdbc`)로 운영자 화면에서 상품 · 가격(월 · 연 · 단건 · 행사가 · 통화별)을 만들고, 랜딩 가격표 · 구독 화면 · 결제가 방문 나라에 맞는 통화의 그 표를 읽는다. 코드로 플랜을 정하는 지금 방식(`SubscriptionPlans` 빈)은 그대로 쓸 수 있다.

**비목표** — 기존 구독자 일괄 가격 전환(+ 30일 고지 · 동의) / Paddle 쪽 가격을 우리가 만들거나 끌어오기 / 같은 통화 안에서 나라마다 다른 금액 / 세금 표시(지금 있는 결제사 안내 문구 수준만) / 수량 2 이상 단건 / 가격 · 상품 삭제 / 랜딩 기능 목록의 DB 화 / Paddle 구독의 플랜 변경 / 무료 체험(쿠폰 FREE 단계) / 무료 플랜을 표에 넣기(랜딩 문구로만).

**반목표** — 브라우저가 보낸 금액 · 통화가 쓰인다 / 기존 구독자의 금액 · 주기가 운영자의 가격 수정으로 조용히 바뀐다 / `catalog` 를 안 붙인 앱의 결제 동작이 바뀐다.

## 1. 범위

| | 1차: 표 · 운영 · 보이기 · 사기 | 2차: 갈아타기 넓히기 | 다음에 |
|---|---|---|---|
| 표 | 상품 · 가격(통화별) · 이름(언어별) · 결제사 가격 링크 | | |
| 운영 | 상품 만들기/고치기, 가격 추가, 판매 중지, 행사가(정가 · 기간), 결제사 링크 | | 링크 저장 때 결제사 API 로 주기 · 통화 검증 |
| 사용자 | 랜딩 가격표(방문 나라의 통화, 월/연 토글 · 절약 % · 인기 · 행사 취소선) · 구독 화면(같은 통화, 상품별 묶음 + 주기 토글) · 단건 구매 | 플랜 변경 창: 내리기 예약 · 예약 취소 · 월→연 | A'(연→월 바로) |
| 결제 | 플랜 = 가격 코드, 「지금 살 수 있는 가격」만 구매, 쿠폰 범위가 상품 코드도 본다, Paddle 금액 불일치 경고 | 주기↑ 바로(남은 몫 빼고 새 주기) · 내리기 예약 | Paddle 구독 플랜 변경 |

## 2. 모듈 · 확장 지점

```
catalog-jdbc ──api──▶ catalog ──api──▶ payment ──▶ platform
                         └─ SubscriptionPlans · PaymentCatalog · ProviderPriceRefs 를 구현(빈, @ConditionalOnMissingBean)
payment-paddle ──▶ payment{ProviderPriceRefs}      coupon ──▶ platform{DiscountTarget.productCode}
```
`payment` 는 `catalog` 를 모른다. `catalog` 는 결제 계약의 **구현 하나**다(뼈대 레포라 확장 지점이 제품).

| 지점 | 자리 | 고른 이유 |
|---|---|---|
| `SubscriptionPlan` 에 칸 셋: `productCode`(기본 = `code`) · `purchasable`(기본 true) · `regularAmount: Long?`(정가) | `payment` | 플랜 = 가격 하나가 되면서 「같은 상품」 · 「지금 팔고 있나」 · 「정가」를 결제 쪽이 알아야 한다. 기본값이 있어 코드 플랜 앱은 그대로 |
| `PaymentCountryRouting.currencyOf(route)` + 설정 `routing.provider-currencies` · `routing.default-currency` | `payment` | 「이 요청의 통화」는 나라 → 결제사를 정하는 라우팅이 같이 정한다(§4.6). 결제 화면과 카탈로그가 같은 답을 쓴다 |
| `ProviderPriceRefs`(`priceIdOf(provider, itemRef)` · `all(provider)`, 기본 `NONE`) | `payment` | 결제사 가격 id 의 출처를 yml 밖으로. Paddle 이 먼저 묻고 없으면 지금 yml 지도(`prices`) |
| `DiscountTarget.productCode: String?` | `platform` | 쿠폰 범위를 가격(바뀐다)이 아니라 상품(안 바뀐다)으로 — Stripe 쿠폰 `applies_to.products` 와 같은 뜻 |
| `CatalogPaymentCatalog`(공개 클래스) | `catalog` | 앱이 코드 상품(응원하기)과 표 상품을 `itemType` 으로 잇는다 — `PaymentCatalog` 빈은 하나뿐이라 |
| `CatalogStore` | `catalog` ← `catalog-jdbc` | 다른 모듈과 같은 포트/저장소 나눔 |

## 3. 데이터 (`catalog-jdbc`, 두 방언, 프로젝트 모드면 프로젝트 스키마마다)

- `catalog_products`: `id` · `code`(유니크, 64) · `kind`(`SUBSCRIPTION`|`ONE_TIME`, 못 바꿈) · `level`(구독만 필수, 유니크) · `highlighted` · `selling` · `sort_order` · 시각 둘.
- `catalog_product_texts`: (`product_id`, `locale`) → `name` · `description`. 언어는 앱 것(견본 ko/en). 결제창 이름은 `skeleton.catalog.default-locale`.
- `catalog_prices`: `id` · `code`(유니크, 64 — **구독 줄의 `plan_code`, 단건의 `itemRef`**, 비우면 만들어 준다) · `product_id` · `period`(구독만, ISO-8601) · `amount`(통화 최소 단위 — USD 는 센트) · `currency` · `compare_at_amount`(정가, `> amount`) · `sale_starts_at` · `sale_ends_at`(둘 다 없으면 상시) · `selling` · `stopped_at` · 시각.
- `catalog_price_provider_refs`: (`price_id`, `provider`) → `external_id`, (`provider`, `external_id`) 유니크.

## 4. 규칙

### 4.1 가격은 고치지 않는다
금액 · 통화 · 주기 · 정가는 만든 뒤 못 바꾼다(Stripe price 와 같다). 바꾸려면 새 가격 + 옛 가격 판매 중지. 고칠 수 있는 것은 `sale_ends_at`(행사 연장 · 조기 종료)과 결제사 링크뿐. 구독 줄은 이미 금액 · 주기를 박으므로 기존 구독자는 옛 가격 그대로(지금 동작). 판매 중지는 되돌리지 않는다.

### 4.2 지금 살 수 있는 가격
열쇠 = (상품, 주기, 통화). **현재 가격** = 판매 중이고 지금이 판매 기간 안인 것 중 기간이 있는 것(행사) 우선, 없으면 상시. 같은 열쇠에 상시는 하나, 행사는 기간이 겹치지 않게(만들 때 상품 줄을 잠그고 검사 → `CATALOG.PRICE_OVERLAP`). **현재 가격만 산다**(행사 중에 정가로 사는 길을 막는다). `SubscriptionPlans.list()` = 판매 중 구독 상품의 현재 가격들(모든 통화), `find(code)` = 판매 중지된 가격까지(기존 구독자의 이름 · 등급 · 주기를 찾는다) + `purchasable`. 구독 시작 · 플랜 변경은 `purchasable` 이 아니면 `ITEM_NOT_PURCHASABLE`.

### 4.3 행사가
행사 = 정가(`compare_at_amount`)와 기간을 단 **새 가격 줄**(통화마다 따로). 화면은 정가 취소선 + 끝나는 날. 행사가로 가입한 구독은 그 금액이 박혀 갱신도 그 금액이다. 「첫 N회만 싸게」는 쿠폰이 맡는다. **쿠폰은 행사가 위에 얹힌다**(Shopify 기본 · 국내 관행) — 최소 금액 · 정률은 행사가 기준. 해지 정책의 「같은 등급 월 가격」(연간 쓴 몫)은 `regularAmount ?: amount`(정가)로 센다 — 행사 중에 연간을 해지해도 환불이 부풀지 않는다.

### 4.4 결제사 가격 id — 링크
- 토스 · 카카오 · 네이버: 가격 id 가 없다 — 우리 줄의 금액을 그대로 보낸다.
- Paddle: 운영자가 Paddle 대시보드에서 만든 `pri_…` 를 **그 통화의 가격 줄**(견본: USD 줄)에 붙인다(`catalog_price_provider_refs`). Paddle 모듈은 `ProviderPriceRefs` → yml 지도 순으로 찾는다(지금 yml 은 그대로 동작). 링크 없는 가격을 Paddle 로 사면 지금은 500(`error(...)`) → `503 UNAVAILABLE`. `pricePreviews` 의 `prices` 도 링크를 합친다(코드 상품 화면이 계속 쓴다). 링크를 결제창이 열린 동안 바꾸면 그 결제는 품목 대조에서 「확인 필요」로 간다(지금 yml 을 바꿔도 같다 — 문서에 적는다).
- 관행 근거: Paddle 은 상품 · 가격을 대시보드에서 만들라고 권하고([문서](https://developer.paddle.com/build/products/create-products-prices)) DB 동기화 공식 안내는 없다. Stripe 진영의 「우리 카탈로그가 원본 + 결제사 id 는 적어 두는 값」과 같은 꼴.

### 4.5 단건
`CatalogPaymentCatalog`: `itemType` = `skeleton.catalog.item-type`(기본 `product`), `itemRef` = 가격 코드, 수량 1, 금액 = 현재 가격, 결제창 이름 = 기본 언어 상품 이름. 결제 면제의 `original_amount` 도 이 금액(행사가).

### 4.6 통화 — 보이는 것은 DB, 결제사는 뒤에서
- **이 요청의 통화**(`PaymentCountryRouting.currencyOf(route)`): 라우팅이 고른 결제사의 통화 — `skeleton.payment.routing.provider-currencies.<결제사>` 가 있으면 그것, 없으면 그 결제사가 받는 통화(실효 `providers.<id>.currencies` → 결제사 기본)가 하나일 때 그것. 결제사를 못 정했거나 통화가 여럿인데 설정이 없으면 `routing.default-currency`, 그것도 없으면 null. 견본: KR → 토스 → KRW, 그 밖 → Paddle → `provider-currencies.paddle: USD`. `GET /payments/config` 에 `currency` 를 더한다.
- **보이기**: 공개 카탈로그 · `GET /subscriptions/plans` 는 그 통화의 가격만 낸다. 그 통화 가격이 하나도 없으면 거르지 않는다(통화 하나짜리 코드 플랜 앱 · 통화를 못 정한 요청은 지금처럼). 통화를 모르면 카탈로그는 상품마다 `default-currency` → 사전순 첫 통화. 랜딩 · 구독 화면은 **DB 금액**을 보인다(구독 화면의 Paddle 미리보기 금액은 쓰지 않는다 — 응원하기 같은 코드 상품 화면만 지금처럼 미리보기).
- **사기**: 고른 가격의 통화를 결제사가 받아야 한다(지금 라우팅 검사 `servesCurrency` 그대로 — 아니면 `PAYMENT.ROUTING_FAILED`). Paddle 로 가는 가격은 링크가 있어야 한다(§4.4).
- **세금**: 화면 금액은 DB 금액 그대로. 세금이 따로 붙는 나라는 지금 있는 결제사 안내(「결제는 Paddle 이 처리해요」 등)만 둔다.
- **금액이 다를 때(한 줄 규칙)**: 결제 금액은 결제사가 확정한 값을 기록하고(지금 동작), DB 금액과 다르면 경고한다 — 단건 · 구독 첫 결제의 웹훅에서 같은 통화인데 `total − tax` ≠ 우리 가격이면 `WARN payment.price-drift`(주문 id · 가격 코드 · 두 금액). 통화가 다르면(Paddle 이 구매자 통화로 환산) 기록만 하고 경고하지 않는다. 지급은 막지 않는다.

## 5. 갈아타기 — 초안 표 · 지금 코드 · 바꿀 것 (2차)

| 지금 → 바꿀 것 | 초안 | 지금 코드 | 2차 |
|---|---|---|---|
| 등급↑, 주기 같음 | 바로, 남은 일수 차액 | **있음** — `isUpgrade`(같은 주기 · 같은 통화 · level↑), 과금 방식은 앱 설정 `PRORATED_DIFFERENCE`(초안) 또는 `FULL_RESTART` | 그대로 |
| 주기↑ 월→연(등급 같거나↑) | 바로, 새 가격 − 남은 몫, 오늘부터 새 주기 | 거절(`PLAN_CHANGE_NOT_ALLOWED`) | 「남은 몫 빼고 새 주기」(Stripe 의 주기 변경 기본: 기준일 재설정 + 미사용분 일할 공제). 설정과 상관없이 이 방식(차액만은 주기가 달라 성립하지 않고 전액은 손님 손해). 남은 몫 = 이번 기간 **실제로 낸 돈**(쿠폰 · 환불 반영)의 남은 일수 몫 — 해지 계산과 같은 입력 |
| 등급↓ (주기 무관) · 같은 등급 연→월 | 다음 갱신부터 예약, 취소 가능 | 거절 | 예약. 갱신 작업이 기간 끝에 플랜 · 금액 · 주기를 바꾼 뒤 새 금액으로 과금(주기가 바뀌면 기준 시각 = 옛 기간 끝). 예약 중 올리기 · 해지 예약은 예약을 지운다. 쿠폰 남은 회차는 새 금액에 이어서(지금 규칙) |
| 등급↑ + 주기↓ (연 basic → 월 pro) | 없음 | 거절 | 다음 갱신 예약 |
| 연→월 바로(A') | 무료 개월 환산 | 없음 | 다음에(아래) |
| Paddle 구독 | — | 변경 거절(`isProviderManaged`) | 올리기만 연다(내리기 거절) - [2026-10-29-paddle-plan-change-design.md](2026-10-29-paddle-plan-change-design.md) |
| 통화가 다른 플랜 | — | 거절 | 그대로(통화는 바꾸지 않는다) |

- 판정은 지금처럼 **한 곳**: `isUpgrade` 를 `PlanChangeRules.classify(current, target, subscription)` → `IMMEDIATE_SAME_PERIOD` · `IMMEDIATE_NEW_PERIOD` · `SCHEDULED` · `NOT_ALLOWED` 로 넓힌다. 주기 비교는 `SubscriptionPeriod` 길이(같은 기준 시각에서 `after(1)`).
- 저장: `payment_subscriptions` 새 V 파일 한 장 — `pending_period`(주기를 바꾸는 변경 결제가 도는 동안), `scheduled_plan_code` · `scheduled_amount` · `scheduled_period`. 잠긴 파일은 고치지 않는다.
- HTTP(더하기만): 미리보기 `+ timing: NOW|NEXT_RENEWAL, effectiveAt`, 실행은 같은 경로(예약이면 200 + `scheduledChange`), `DELETE /subscriptions/{id}/scheduled-change`, 내 구독 `+ scheduledChange`.

**A'(다음에) — 지금 모델에 들어간다, 크기는 kotlin 대 1조각 + react 소.** 기간 맞추기는 기존 식(기준 시각 + n × 주기)으로 표현된다: 기준 시각 = 원래 결제일에 맞춘 무료 끝 날, 기간 번호 0. 새로 필요한 것: ① 끝수 차감 칸(`carry_credit`)과 갱신 금액 계산(할인 단계 뒤 · 결제사 최소 금액) ② **무료 개월 중 중도 해지 · 환불** — 지금 정책은 「이번 기간에 낸 주문」만 보므로 무료 기간에는 환불 0(한국 정책에서 손님 손해) → 연 주문이 무료 끝까지 덮는다고 보는 규칙 ③ 운영자가 그 연 주문을 전액 환불할 때 구독 끝내기 판정(`boughtCurrentPeriod`) 넓히기. 한국 정책이 켜진 나라는 그동안 「중도 해지(남은 돈 환불) + 월 구독」으로 같은 결과를 얻는다.

## 6. HTTP

- 공개 읽기(새, `skeleton.catalog.http.enabled` 기본 꺼짐 → 404, 로그인 없이): `GET /api/v1/catalog/products?kind=` → `{ currency|null, products: [{ code, kind, level, highlighted, texts: { <locale>: { name, description } }, prices: [{ code, period, amount, currency, compareAtAmount, saleEndsAt }] }] }` — 판매 중 상품 + 이 요청 통화(§4.6)의 현재 가격만. 통화가 나라에 따라 달라지므로 `Cache-Control: private, max-age=60` + `Vary: CF-IPCountry`(로컬 나라 스위치가 켜져 있으면 `X-Dev-Country` 도).
- 운영(새, `skeleton.catalog.admin.enabled` 기본 꺼짐 → 404, 권한 `catalog.manage` 기본 역할 없음, `OperatorAuditLog` 기록): `GET /api/v1/admin/catalog/products`(모든 통화 · 중지된 것 · 지난 가격까지) · `POST …/products` · `PUT …/products/{code}` · `POST …/products/{code}/prices` · `POST …/prices/{code}/stop` · `PATCH …/prices/{code}`(`saleEndsAt` 만) · `PUT|DELETE …/prices/{code}/provider-refs/{provider}` · `GET …/providers`(가격 id 를 쓰는 켜진 결제사). 오류 `CATALOG.CODE_TAKEN` · `LEVEL_TAKEN` · `PRICE_OVERLAP` · `PRICE_INVALID` · `NOT_FOUND`.
- 결제(더하기만): `GET /config` `+ currency`. `GET /subscriptions/plans` 는 이 요청 통화로 거르고(§4.6) 각 줄 `+ productCode, regularAmount`. 내 구독의 플랜 이름은 `find`(지난 가격도).

## 7. 화면 (react)

- `@skeleton/marketing` `PricingTable`: 주기별 금액이 없을 수 있다(`price.monthly|yearly: number | null`) — 토글은 연 가격이 하나라도 있을 때만, 절약 %는 둘 다 있는 상품에서만, `compareAt` · `saleEndsAt` 이 있으면 취소선 + 「○월 ○일까지」. 통화는 prop 그대로. 카탈로그 → `PricingPlan` 변환(최소 단위 → 기본 단위)은 앱.
- `@skeleton/payment`: 카탈로그 타입 · `createCatalogApi` · `useCatalogProducts` · 금액 최소 단위 → 표시 도우미. `SubscriptionPlanList` 를 상품별 묶음 + 주기 토글로, 금액은 서버가 준 플랜 금액(Paddle 경로도). `@skeleton/payment/admin`: 상품 목록 · 상품 편집 · 가격 목록(통화별 묶음) · 가격 추가(통화 · 주기 · 금액, 행사가 = 정가 · 기간 칸) · 판매 중지 확인 · 결제사 링크 칸(켜진 결제사가 가격 id 를 쓰는 경우만).
- 2차: `ChangePlanDialog` 가 같은 통화의 다른 플랜을 「바로 / 다음 결제일부터」로 나눠 보이고, `MySubscriptionCard` 가 예약 + 「예약 취소」.

## 8. 견본 앱

코드 플랜 빈(`samplePlans`)을 빼고 `catalog` · `catalog-jdbc` 를 붙인다. 씨앗(없는 코드만 만든다): basic · standard · pro(level 1~3, pro 가 인기).
- KRW: 월 3,900 / 6,900 / 9,900원 · 연 39,000 / 69,000 / 99,000원(10개월치). **월 가격 코드는 `basic` · `standard` · `pro`** — 기존 구독 줄 · 쿠폰 범위가 그대로 맞는다. 연은 `basic-yearly` ….
- USD: 월 $3 / $5 / $8 · 연 $30 / $50 / $80, 코드 `basic-usd` · `basic-yearly-usd` …. Paddle 가격 id 는 yml 지도 키를 이 USD 코드로 옮긴다(`PADDLE_PRICE_BASIC` → `basic-usd`, 연 가격용 환경변수 셋 추가) — 운영 화면의 링크가 있으면 그쪽이 이긴다.
- 라우팅: `country-providers: KR: toss` 그대로 + `provider-currencies: { paddle: USD }`. 로컬은 `X-Dev-Country` 스위치로 두 통화를 다 본다.
- 주기는 설정값(`skeleton.sample.subscription.period` P1M / 로컬 PT3M, 새 `yearly-period` P1Y / 로컬 PT36M). 응원하기는 코드 그대로, `PaymentCatalog` 는 `itemType` 으로 둘을 잇는다. 랜딩은 카탈로그 + 사전의 무료 카드 · 기능 목록(지금 언어로 정하던 랜딩 통화는 서버 통화로 바뀐다). 운영 메뉴 「상품 · 가격」.

## 9. 검증 (깨지면 사고 나는 것만)

현재 가격 고르기(행사 시작 · 끝 경계 1초 전후, 겹치는 행사 거절, 상시 둘 거절, 통화가 다르면 따로) / 판매 중지 · 기간 지난 가격 · 지난 가격 코드로 구독 시작 → 422, 그 가격의 기존 구독은 갱신 · 이름 · 해지 그대로 / 금액을 고치는 API 가 없다 / 나라 KR → KRW 가격만 · 그 밖 → USD 가격만, USD 가격을 토스로 사려 하면 `ROUTING_FAILED` / 링크 없는 가격의 Paddle 결제 → 503, 링크 있으면 품목 대조 통과 / Paddle 웹훅 금액이 같은 통화로 다르면 경고 한 줄 · 지급은 그대로, 통화가 다르면 경고 없음 / 쿠폰 범위 `basic` 이 basic 연 가격에도 맞는다 / 행사 중 연간 중도 해지의 쓴 몫이 정가 기준 / 두 DB 저장소 시험. 2차: 주기↑ 결제가 실패하면 플랜 · 주기 그대로, 예약 내리기가 갱신과 같은 트랜잭션에서 적용되고 실패한 갱신의 재시도는 새 금액, 예약 중 올리기가 예약을 지운다.

## 10. 작업 조각 · 크기

| # | 레포 | 조각 | 선행 | 크기 |
|---|---|---|---|---|
| K1 | kotlin | `payment` · `platform` · `coupon` · `payment-paddle` 접점(§2 표의 칸 · 포트, 요청 통화 · 플랜 거르기 · `/config` 칸, `purchasable` 검사, 정가로 쓴 몫, Paddle 링크 우선 · 503 · 금액 불일치 경고) | — | 중~대 |
| K2 | kotlin | `catalog` · `catalog-jdbc`(표 · 저장소 · 규칙 · 결제 계약 구현 · 통화별 공개 읽기 · 운영 API · 문서 · 카탈로그 항목) | K1 계약 커밋 | 대 |
| K3 | kotlin | 견본 앱 조립 · 두 통화 씨앗 · 라우팅 · yml · 통합 시험 · CHANGELOG | K1 · K2 | 소~중 |
| R1 | react | `@skeleton/payment` 카탈로그 API · 훅 · 구독 화면 묶음/토글(DB 금액) + `@skeleton/marketing` 가격표 + 견본 랜딩 · 구독 화면 | §6 계약 | 중 |
| R2 | react | `@skeleton/payment/admin` 카탈로그 화면(통화별) + 견본 라우트 · 메뉴 + e2e(두 나라) | §6 계약 · R1 첫 커밋 · (e2e) K3 | 중~대 |
| K4 | kotlin | 2차 갈아타기(§5) | 1차 | 대 |
| R3 | react | 2차 변경 창 · 예약 표시 | K4 계약 | 중 |

1차 줄기: **K1 ∥ R1 ∥ R2**(K2 는 K1 의 계약 커밋에서 바로) → **K3** → 리뷰(kotlin 1 · react 1) → 확인 1 → e2e. developer 5번, 약 5~6시간. 2차: **K4 ∥ R3** → 리뷰 1 → 확인 1, developer 2번, 약 3시간. 실행 계획: `docs/superpowers/plans/2026-10-29-catalog-pricing.md`.

## 11. 초안에서 고친 것

- 「해외 결제사(MoR) 가격 동기화 안 함」 → Paddle 이 들어와 가격 id 연결이 필요하다(지금 yml 지도) — §4.4. 통화별 가격 행과 나라별 통화가 1차로 들어온다 — §4.6.
- 「갈아타기 등급↑ = 2차에서 만든 것」 → 같은 주기만 된다. 주기↑ · 내리기는 새로 만든다 — §5.
- 「`SubscriptionPlans` 는 이 표를 읽는 구현 하나」 → 맞지만 플랜 한 줄 = 가격 하나라 계약에 칸 셋이 필요하다(§2), 플랜 코드 = 가격 코드.
- 해지 · 환불의 「같은 등급의 월 가격」이 행사가를 집지 않게 정가로 — §4.3.
- 쿠폰 범위(`planCodes`)가 가격 코드만 보면 가격을 바꿀 때마다 쿠폰이 깨진다 → 상품 코드도 맞춘다.
- 「응원하기 = 금액을 사용자가 정하는 결제」 → 실제는 고정 세 금액의 코드 목록. 코드로 두는 이유는 같다(§8).
- 일할 일 단위는 들어갔다(`Proration`). 쿠폰 · n달 무료 · 결제 면제는 들어갔다 — 행사가와의 겹침 규칙만 새로(§4.3).
- 프로젝트 모드: 표와 결제사 링크가 프로젝트 스키마마다라 프로젝트별 Paddle 키와 짝이 맞는다.

## 정한 것 (내 판단 · 관행 — 바꾸려면 말해 달라)

모듈 이름 `catalog`(+ `-jdbc`), `catalog → payment` 한 방향 / 가격 코드가 구독 줄의 `plan_code` · 단건 `itemRef` · 결제사 링크의 열쇠, 비우면 만들어 줌 / 이름은 언어별 줄(언어는 앱 것) / 현재 가격 = 행사 우선, 현재 가격만 구매 / 판매 중지는 되돌리지 않음, 고칠 수 있는 것은 행사 끝 시각 · 결제사 링크 / 쿠폰 범위는 가격 코드 또는 상품 코드 / 요청 통화는 라우팅이 정하고(결제사별 통화 설정 → 결제사의 유일한 통화 → 기본 통화), 그 통화 가격이 없으면 거르지 않음 / 금액 불일치 경고는 같은 통화의 세전 금액만, 로그 경고(알림 · 「확인 필요」 아님) / 공개 읽기는 로그인 없이 · 나라마다 갈리는 60초 개인 캐시 / 운영 권한 `catalog.manage` 하나 / 단건 수량 1 / 2차 주기↑는 설정과 상관없이 「남은 몫 빼고 새 주기」.

## 정해야 할 것

없음 — 형 결정(2026-10-12 · 2026-10-29)으로 닫혔다.
