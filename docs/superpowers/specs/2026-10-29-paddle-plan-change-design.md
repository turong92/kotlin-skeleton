# Paddle 구독의 플랜 바꾸기 — 설계

> 2026-10-29 초안. 요청: Paddle 로 구독한 사용자도 「플랜 바꾸기」를 쓸 수 있게.
> 대상: kotlin-skeleton(`payment` · `payment-paddle`, main `ceff1d68`) + react-skeleton(`@skeleton/payment` · `apps/sample` · e2e 가짜 Paddle, main `ea2bcf2`).
> 앞 설계: `2026-10-29-catalog-pricing-design.md` §5(토스 갈아타기 — 이 문서는 그 표의 「Paddle 구독: 변경 거절」 줄을 연다) · `2026-10-13-paddle-design.md` · `2026-10-20-paddle-refund-and-easypay-subscriptions-design.md`.

## 결정: 내리기는 1차에서 막는다 (정할 것 1 = C)

Paddle 구독은 **올리기만** 바꾼다(등급↑ · 월 → 연, `prorated_immediately`, 차액 청구). `PlanChangeRules.classify` 가 `SCHEDULED` 로 판정하는 변경(등급↓ · 연 → 월 · 등급↑ + 주기↓)은 Paddle 구독이면 `409 PLAN_CHANGE_DOWNGRADE_UNSUPPORTED` 로 거절하고 Paddle 을 부르지 않는다. 이유: Paddle 에는 예약이 없어 내리기가 바로 바뀌고 남은 몫이 고객 크레딧 잔액으로 남는데, 그 길(`prorated_next_billing_period` · 크레딧 공제)을 우리 장부가 아직 다루지 않는다. 그래서 아래 본문 중 **내리기에 관한 것은 만들지 않는다**: §3 표의 `SCHEDULED` 두 줄 · `prorated_next_billing_period` · `nextCharge`(§4 ③ · §6 응답 칸과 「다음 결제 금액」 줄) · 내리기 시험 · 끝 확인 ③ ④. 웹훅 장부 고침(§5.3)은 대시보드 같은 바깥 변경도 장부가 따라가야 하므로 그대로 한다. 토스 경로는 그대로(내리기 = 다음 결제일 예약).

## 0. 목표 · 비목표 · 반목표

**목표** — Paddle 구독자가 토스 구독자와 **같은 창**(`ChangePlanDialog`)에서 플랜을 고르면, Paddle 이 계산한 금액을 미리 보고, 확인하면 Paddle 이 구독을 바꾸고(필요하면 즉시 결제) 우리 장부 · 「내 구독」 · 결제 내역이 따라온다. 판정(올리기 · 주기 늘리기 · 내리기 · 거절)은 지금 `PlanChangeRules.classify` 한 곳 그대로이고, **어떻게 청구하는지는 Paddle 의 방식**을 따른다.

**비목표** — Paddle 대시보드 · 고객 포털에서 바꾼 품목을 금액까지 맞추기(플랜 코드만 맞춘다, §5.3) / 품목 둘 이상인 Paddle 구독(애드온) / Paddle 크레딧 adjustment(`action=credit`)를 장부에 넣기 / 고객 크레딧 잔액을 보여 주거나 환불하기 / 결제 수단 바꾸기 / 일시정지 · 체험 / 통화를 바꾸는 변경 / 토스 경로의 동작 변경.

**반목표** — 브라우저가 보낸 금액 · 가격 id 가 쓰인다 / Paddle 이 결제하지 못했는데 우리 장부의 플랜이 바뀐다 / 우리 장부가 Paddle 이 받은 돈과 다른 금액을 「낸 돈」으로 적는다 / 이 모듈을 안 붙인 앱이나 토스 구독의 동작이 바뀐다.

## 1. 지금 막힌 곳 · 빈 곳 (코드로 확인)

| 자리 | 지금 |
|---|---|
| `SubscriptionPlanChanges.kt:65`(미리보기) · `:74`(실행) | `isProviderManaged`(= `providerSubscriptionId != null`, `PaymentSubscriptionModel.kt:120`)면 `PlanChangeNotAllowed` 409. 실행 쪽 주석 「결제사가 갱신하는 구독의 플랜 변경은 2차」 |
| 결제사 계약(`PaymentPush.kt`) | 결제사가 들고 있는 구독에 대해 할 수 있는 일은 `PaymentHostedSubscriptionGateway.cancel` 뿐 — 품목 바꾸기 · 미리보기 능력이 없다. `payment-paddle` 에 `PATCH /subscriptions/{id}` 호출이 없다 |
| 웹훅 결제(`PaddlePaymentProvider.payment`) | 거래의 `origin` 을 읽지 않는다 → 구독 변경 거래(`subscription_update`)가 **갱신**으로 기록된다(`PaymentPushedEvents.kt:182` `SUBSCRIPTION_RENEWAL`), 그리고 `:167` 이 구독 금액을 그 **일할 결제액으로 덮는다**. `details.totals.total`(크레딧 빼기 전)만 읽어, 크레딧이 붙은 거래는 실제로 낸 돈(`grand_total`)보다 크게 적힌다 |
| 웹훅 구독(`PaddlePaymentProvider.subscription`, `PaymentPushedEvents.kt:338~`) | 품목(가격 id)을 읽지 않는다 — Paddle 쪽 플랜이 바뀌어도 우리 `plan_code` 는 그대로다. 주기 · 기간만 따라온다 |
| 통화(`PlanChangeRules.kt:26`) | 대상 플랜 통화 ≠ **구독 금액의 통화**면 거절. Paddle 은 구매자 통화로 환산해 결제할 수 있어(카탈로그 설계 §4.6) 구독 금액이 EUR 이고 플랜 표가 USD 일 수 있다 → 그대로 열면 모든 변경이 거절된다 |
| react `apps/sample/src/routes/SubscriptionPage.tsx:97~99` | `!subscription.providerManaged` 일 때만 「플랜 바꾸기」. `e2e/paddle.e2e.ts:113` 이 단추가 **없음**을 단언한다. `subscriptionTypes.ts:72` 주석도 「플랜 변경 · 중도 해지는 열리지 않는다」 |

## 2. Paddle API — 문서에서 확인한 것 (2026-10-29 읽음)

- **바꾸기**: [`PATCH /subscriptions/{id}`](https://developer.paddle.com/api-reference/subscriptions/update-subscription) `{items, proration_billing_mode, on_payment_failure}` — 권한 `subscription.write`(해지에 이미 필요). `items` 는 **바뀐 뒤의 전체 목록**(빠진 품목은 지워진다). 우리 구독은 품목 하나라 `[{price_id: <대상>, quantity: 1}]`. 즉시 결제 방식이면 결제를 시도한 뒤에 응답한다. `on_payment_failure` 기본 `prevent_change` = 결제가 실패하면 **바뀌지 않는다**.
- **미리보기**: [`PATCH /subscriptions/{id}/preview`](https://developer.paddle.com/api-reference/subscriptions/preview-subscription-update) — 같은 본문, 적용하지 않는다. 응답 `immediate_transaction`(즉시 결제 방식일 때만, `billing_period` · `details.totals.{total, credit, grand_total, credit_to_balance}`) · `next_transaction`(다음 갱신 거래, 다음 결제 방식의 일할 포함) · `recurring_transaction_details.totals`(일할 없는 정기 금액) · `update_summary.{credit, charge, result{action: charge|credit, amount}}`. `grand_total` = 「크레딧을 뺀, 결제할 금액」.
- **청구 방식**([Proration](https://developer.paddle.com/concepts/subscriptions/proration)): `prorated_immediately` · `prorated_next_billing_period` · `full_immediately` · `full_next_billing_period` · `do_not_bill`. 일할은 **분 단위**. **주기를 바꾸는 변경은 `prorated_immediately` · `full_immediately` · `do_not_bill` 만**([Upgrade or downgrade](https://developer.paddle.com/build/subscriptions/replace-products-prices-upgrade-downgrade)).
- **Paddle 자신의 기본값**([고객 포털의 업/다운그레이드](https://developer.paddle.com/build/subscriptions/customer-portal-upgrades-downgrades)): 「Upgrades are applied immediately, and they're prorated immediately」(`prorated_immediately`), 「**Downgrades are applied immediately, and they're prorated on the next billing period**」(`prorated_next_billing_period` — 크레딧은 다음 결제에서 빠진다).
- **예약이 없다**: `scheduled_change` 는 `cancel` · `pause` · `resume` 뿐이고 PATCH 로는 `null`(지우기)만 된다. 품목 변경을 다음 결제일로 예약하는 API 는 없다.
- **크레딧**([Credit balances](https://developer.paddle.com/build/customers/get-customer-credit-balances)): 일할 크레딧이 변경 비용보다 크면 남은 것은 고객의 통화별 크레딧 잔액이 되고 이후 거래에 자동으로 쓰인다(직접 다룰 수 없다). 일할 크레딧은 이전 거래에 대한 adjustment 로 만들어진다(`update_summary.credit` 설명).
- **웹훅**: [`subscription.updated`](https://developer.paddle.com/webhooks/subscriptions/subscription-updated)(품목 · 가격 변경 포함, 구독 전체 본문) · 즉시 결제면 `transaction.completed`, 그 거래의 [`origin`](https://developer.paddle.com/api-reference/transactions/get-transaction) = `subscription_update`(갱신은 `subscription_recurring`).
- **막히는 때**(오류 코드, [목록](https://developer.paddle.com/llms/errors.txt)): `subscription_update_when_past_due` · `subscription_locked_renewal`(갱신 30분 전부터) · `subscription_locked_processing` · `subscription_locked_pending_changes` · `subscription_outstanding_transaction` · `subscription_outstanding_pending_refund` · `subscription_immediate_charge_hour_limit_exceeded` / `_24_hour_` · `subscription_invalid_billing_mode_for_scheduled_change` · `subscription_payment_declined` · `subscription_update_transaction_balance_less_than_charge_limit`(「Paddle 최소 금액보다 크거나 **아예 0** 이어야 한다」).
- **멱등 키 헤더 없음**(기존 설계와 같다).
- **문서에 없는 것** → §7.1 실측: 주기를 바꾸면 기간이 오늘부터 다시 시작하는지 / 최소 금액 미만을 미리보기도 거절하는지 / 같은 품목으로 다시 PATCH 하면 아무 일도 없는지 / 변경 뒤 웹훅의 순서 · 크레딧 adjustment 가 오는지.

## 3. 우리 규칙 ↔ Paddle

판정은 `PlanChangeRules.classify` 그대로다(통화 비교만 §4 ④). 청구는 `payment-paddle` 이 판정에서 고른다.

| 판정(`PlanChangeKind`) | 토스(지금) | Paddle | 미리보기 응답 |
|---|---|---|---|
| `IMMEDIATE_SAME_PERIOD` 같은 주기 윗등급 | 바로, 앱 설정 `charge`(차액 · 전액) · `min-charge` | `prorated_immediately` | `timing NOW`, `charge` = `immediate_transaction` 의 `grand_total`, `credit` = 일할 크레딧 |
| `IMMEDIATE_NEW_PERIOD` 월 → 연 | 바로, 새 가격 − 낸 돈의 남은 몫, 오늘부터 새 주기 | `prorated_immediately` | 위와 같고 `restartsPeriod` = Paddle 의 새 기간이 오늘 시작하는가 |
| `SCHEDULED` 등급 내리기(같은 주기) | **다음 갱신에 예약**(거둘 수 있다) | 정할 것 1 — 추천: `prorated_next_billing_period`(Paddle 기본: **바로 바뀌고** 남은 몫은 다음 결제에서 공제) | `timing NOW`, `charge` 0, `credit`, **`nextCharge`** = `next_transaction` 의 `grand_total` |
| `SCHEDULED` 주기 줄이기(연 → 월, 등급↑ + 주기↓) | 다음 갱신에 예약 | 정할 것 1 — 추천: `prorated_immediately`(주기 변경은 다음 결제 방식이 없다) — 남은 몫이 새 가격보다 크면 0원 결제 + 나머지는 Paddle 크레딧 잔액 | `timing NOW`, `charge`(대개 0), `credit`, `restartsPeriod`, `nextCharge` |
| `NOT_ALLOWED` | 409 | 409(Paddle 을 부르지 않는다) | — |

**우리 규칙과 다른 점**(문서 · 계약에 적는다)
1. **금액은 Paddle 이 계산한다** — 분 단위 일할, 세금 포함, 구독 통화(구매자 통화). 앱의 `plan-change.charge` · `min-charge` 는 우리가 청구하는 결제사(토스) 몫이다. Paddle 최소 금액 미만이면 Paddle 이 「0 이어야 한다」고 하므로 `do_not_bill`(과금 없이 바꾸기)로 다시 묻는다 — 우리 `min-charge` 의 뜻(미만이면 무료)과 같다.
2. **내리기에 예약 · 거두기가 없다**(추천안 기준) — Paddle 구독자는 내리면 바로 낮은 플랜이 되고 남은 몫은 다음 결제에서 빠진다. 「다음 결제일부터」 묶음 · `scheduledChange` · 「예약 취소」는 Paddle 구독에 나타나지 않는다.
3. **202(결제 확인 중)가 없다** — Paddle 은 결제까지 끝낸 뒤 응답한다. 응답을 잃으면(시간 초과 · 5xx) 실패로 답하고, 실제로 바뀌었으면 `subscription.updated` 웹훅이 플랜을 맞춘다(§5.3).
4. 남은 몫이 새 가격보다 크면 차액은 **Paddle 의 고객 크레딧 잔액**으로 간다(이후 결제에 자동 사용). 우리는 그 잔액을 보이지도 환불하지도 않는다.

## 4. 확장 지점 (developer 는 이것만 만든다)

| # | 지점 | 자리 | 고른 이유 |
|---|---|---|---|
| ① | `PaymentHostedPlanChangeGateway : PaymentGateway` — `previewPlanChange(HostedPlanChange): HostedPlanChangeQuote` · `applyPlanChange(HostedPlanChange): HostedPlanChangeQuote`. `HostedPlanChange(providerSubscriptionId, targetItemRef, kind: PlanChangeKind)`, `HostedPlanChangeQuote(charge, credit?, recurring, nextCharge?, periodStart?, periodEnd?, restartsPeriod)` | `payment` (`PaymentPush.kt`) | 능력은 인터페이스로 선언하고 라우터가 `is` 로 본다(기존 관례). 판정은 우리, 청구 방식 · 가격 id 는 결제사 모듈 — 다른 판매 대행 결제사도 같은 자리에 붙는다 |
| ② | `PushedPayment.credit: Long? = null`(크레딧으로 낸 몫) · `PushedPayment.subscriptionChange: Boolean = false` · `PushedSubscription.items: List<PushedItem> = emptyList()` · `PaymentPushGateway.itemRefOf(priceId): String? = null` | `payment` | 결제사 중립 칸 셋 + 역방향 가격 찾기. 기본값이 있어 다른 결제사 · 옛 시험은 그대로 |
| ③ | `PlanChangeQuote.nextCharge: PaymentAmount? = null` → `PlanChangePreviewResponse.nextCharge: AmountResponse? = null` | `payment` · `web` | 결제사별 차이를 미리보기 응답 칸 하나로 흡수한다(더하기만) |
| ④ | `PlanChangeRules.classify(current, target, subscription, currency = subscription.amount.currency)` — 결제사 관리 구독은 `current.amount.currency`(플랜 표 통화)를 넘긴다 | `payment` | 환산 결제된 Paddle 구독도 같은 표의 플랜끼리 비교한다. 토스는 기본값 그대로 |

장부 칸 · 마이그레이션은 **없다**(기존 `original_amount` · `discount_amount` · `pending_*` 을 쓴다).

## 5. 흐름

### 5.1 미리보기 `GET /subscriptions/{id}/change-preview`
`SubscriptionPlanChanges.preview` 가 결제사 관리 구독이면: 지금 검사(설정 켜짐 · `ACTIVE` · 살 수 있는 플랜 · 판정 ④ · 과금 중 주문 없음)를 그대로 하고 → 결제사가 ① 을 구현하지 않았으면 409 → `previewPlanChange` → 위 표대로 `PlanChangeQuote`(`effectiveAt` = 지금, `amount` = `recurring`). 대상 플랜에 Paddle 가격 id 가 없으면 503(`UNAVAILABLE`, 카탈로그 §4.4 와 같다). 결제사 호출은 락 · 트랜잭션 밖.

### 5.2 실행 `POST /subscriptions/{id}/change`
1. 줄을 읽어 소유 · 상태를 보고, **이미 그 플랜이면 그 줄을 200 으로 돌려준다**(같은 요청의 재전송 — Paddle 에 멱등 키가 없다).
2. `previewPlanChange` 를 다시 불러 `charge > expectedCharge` 면 409 `PLAN_CHANGE_CHARGE_CHANGED`(Paddle 을 바꾸지 않는다).
3. `applyPlanChange`(락 밖). 결과: 성공 → ④; `subscription_payment_declined` → 402 `DECLINED`(플랜 그대로); 막히는 때(§2 목록) → 409 `PLAN_CHANGE_NOT_ALLOWED`; 최소 금액 미만 → `do_not_bill` 로 한 번 더(미리보기도 같은 규칙이라 그때 `charge` 0 을 봤다); 시간 초과 · 5xx → 던진다(모름, 웹훅이 맞춘다).
4. 구독 줄을 잠그고 `lifecycle.switched(줄, 대상 코드, recurring 금액, 대상 주기)` + Paddle 이 준 기간(새 기간이면 기간 번호 규칙은 웹훅 반영과 같게) 저장 → 200. 동시에 다른 플랜으로 바꾸는 두 요청은 막지 않는다(사용자 한 명 · 요청 수 제한 있음, 마지막 Paddle 상태를 웹훅이 맞춘다).

### 5.3 웹훅 — 장부가 Paddle 을 따라간다
- `transaction.completed` + `origin=subscription_update` → `subscriptionChange=true`: 주문 `SUBSCRIPTION_CHANGE` 한 줄(`itemRef` = `itemRefOf(가격)` ?: 줄의 플랜, 중복 키 그대로 `provider:txn`) — **구독 금액 · 주기 · 기간 · 상태는 건드리지 않는다**. 지급 작업은 지금처럼(`onOrderPaid` 는 결제사 관리 구독이면 이미 아무것도 안 한다).
- 모든 Paddle 결제: `credit` 이 있으면 주문 `amount` = `paid_amount` = `total − credit`(실제로 낸 돈, 0 도 된다), `original_amount` = `total`, `discount_amount` = `credit`(토스의 주기 늘리기 공제와 같은 표현). 갱신이 구독 금액에 쓰는 값은 `total`(정기 가격, `> 0` 제약).
- `subscription.updated`: 품목이 하나고 `itemRefOf` 로 찾은 코드가 줄의 플랜과 다르고 플랜 표가 아는 코드면 `switched` 로 플랜 코드 · 주기를 맞춘다(금액은 다음 결제가 맞춘다). 5.2 ④ 와 순서가 바뀌어도 같은 결과다.

## 6. HTTP · 화면 (더하기만)

- 미리보기 응답 `+ nextCharge`(없으면 `null`) — 「다음 결제 한 번의 금액(공제 반영)」. 계약 문서(`docs/payment-http-contract.md` 533~534행)에 Paddle 줄: 금액은 결제사 계산 · 구매자 통화, 내리기도 `NOW`, 202 없음, 응답 유실은 오류 + 웹훅이 맞춤.
- `ChangePlanDialog`: `preview.nextCharge` 가 있고 `amount` 와 다르면 사실 한 줄 「다음 결제 금액」, `credit` 이 있고 `restartsPeriod` 가 아니면 안내 한 줄 「남은 이용분 {credit}은 다음 결제에서 빠져요」(ko/en). 그 밖은 그대로 — 내리기여도 `timing` 이 `NOW` 라 확인은 「이 플랜으로 바꾸기」다.
- 견본 `SubscriptionPage`: 「플랜 바꾸기」를 Paddle 구독에도 연다. 결제사 관리 구독에는 창에 `current` 를 넘기지 않는다(「다음 결제일부터」 묶음이 Paddle 에서는 틀린 말이라 — 적용 시점은 미리보기가 말한다). 바꿀 플랜을 거르는 통화는 지금 플랜의 표 통화(`plans` 의 같은 코드) → 없으면 구독 통화. `subscriptionTypes.ts` 의 `providerManaged` 주석을 「중도 해지는 열리지 않는다」로.

## 7. 검증

### 7.1 0단계 — 샌드박스 실측(개발 전, 형 `.env.local` 의 샌드박스 키, 샌드박스만)
활성 샌드박스 구독 하나(없으면 견본 앱 · 미국 · 테스트 카드로 만든다)에 `curl` 로 미리보기 위주로 잰다. 결과 표를 이 문서 끝에 붙이고, 답에 따라 K1 의 매핑을 고친다.

| # | 잴 것 | 답이 다르면 |
|---|---|---|
| M1 | 같은 주기 올리기 미리보기의 `immediate_transaction.details.totals` 의 `total` · `credit` · `grand_total` 과 `update_summary` | `charge` · `credit` 을 읽는 칸을 고친다 |
| M2 | 월 → 연 `prorated_immediately` 의 `immediate_transaction.billing_period` · 응답 `current_billing_period` 가 지금 시작하는가 | 시작하지 않으면 `restartsPeriod` false 로, 기간은 Paddle 값 그대로 |
| M3 | 같은 주기 내리기 `prorated_next_billing_period` — `immediate_transaction` null, `next_transaction` 의 `credit` · `grand_total` | `nextCharge` 칸 |
| M4 | 연 → 월 `prorated_immediately` — `grand_total` 0, `credit_to_balance` | 0원 변경의 장부 기록(주문을 남기나: `transaction.completed` 가 0원으로 오는가는 M7) |
| M5 | 같은 품목으로 미리보기 · PATCH — 결제 · 거래가 없는가 | 있으면 5.2 ① 을 「Paddle 의 지금 품목을 먼저 읽는다」로 |
| M6 | 최소 금액 미만을 미리보기가 거절하는가(재현이 어려우면 건너뛰고 가짜 Paddle 로만) | 미리보기가 받으면 실행에서만 `do_not_bill` 재시도 |
| M7 | 실제로 한 번 올리기 PATCH 후 웹훅: `transaction.completed`(`origin`) · `subscription.updated`(`items`) · `adjustment.created`(`action=credit`)의 순서와 내용 | §5.3 매핑 |

### 7.2 자동 시험
- kotlin `payment-paddle` 단위: 청구 방식 고르기 · 응답 읽기 · 최소 금액 재시도 · 오류 분류 · 웹훅의 `origin` · `credit` · `items`. `payment` 장부: 결제사 관리 구독의 미리보기 · 실행 · 거절 · 재전송 · 응답 유실 후 웹훅이 맞춤 · 변경 결제가 구독 금액을 안 덮음 · 크레딧 결제의 낸 돈 · 0원 결제 · 환산 통화 구독. 두 DB 저장소 시험은 바뀌는 쿼리가 없어 기존 그대로.
- react 단위 · 스토리 + 가짜 Paddle(`PATCH /subscriptions/{id}[/preview]`, 변경 거래 · `subscription.updated` 웹훅) e2e: 올리기(바로 결제 → 주문 `SUBSCRIPTION_CHANGE` · 새 플랜), 내리기(결제 없음 → 새 플랜 · 다음 결제 금액 표시).

### 7.3 끝 확인 — 진짜 Paddle 샌드박스로 눌러 보기(qa, Aside)
견본 앱을 형 `.env.local`(Paddle 샌드박스) + 나라 미국으로 띄우고, 지난 Paddle 샌드박스 확인 때의 웹훅 공개 주소를 그대로 쓴다. 테스트 카드로 basic 월 구독 → ① standard 월(올리기: 창의 「지금 결제할 금액」 = Paddle 거래의 `grand_total`, 주문 `SUBSCRIPTION_CHANGE` · 낸 돈 같음, 내 구독 standard) → ② standard 연(주기 늘리기: 공제 문구, 기간이 Paddle 과 같음) → ③ basic 연(내리기: 결제 없음, 다음 결제 금액 = Paddle `next_billed` 거래 미리보기, 내 구독 basic) → (가능하면) ④ basic 월(주기 줄이기: 0원). 각 단계 뒤 `GET /subscriptions/{id}`(샌드박스 API)의 품목 · 기간과 우리 「내 구독」이 같은지. 화면 캡처는 단계당 한 장.

## 8. 다음에 넓힐 것
Paddle 크레딧 adjustment 를 장부에 넣기(변경 뒤 이전 주문을 운영자가 전액 환불하면 Paddle 이 남은 금액 초과로 거절할 수 있다 — 지금은 기존 「모름」 처리로 사람이 본다) / 대시보드 변경의 금액까지 맞추기 / 고객 크레딧 잔액 표시 / 품목 둘 이상 / (정할 것 1 이 B 면) 예약 거두기의 Paddle 쪽.

## 정한 것 (관행 · 내 판단 — 바꾸려면 말해 달라)
청구 방식은 Paddle 고객 포털과 같게(올리기 · 주기 변경 `prorated_immediately`, 같은 주기 내리기 `prorated_next_billing_period`) / `on_payment_failure` 는 보내지 않는다(기본 `prevent_change` — 실패면 플랜 그대로, 토스와 같은 결과) / 앱의 `charge: FULL_RESTART` 는 Paddle 에 쓰지 않는다(Paddle 의 `full_immediately` 는 기간을 다시 시작한다는 문서가 없어 손님이 한 기간 값을 두 번 낼 수 있다) / Paddle 최소 금액 미만은 `do_not_bill` = 무료 변경 / 통화 비교는 플랜 표 통화끼리, 금액은 Paddle 미리보기 통화 / 금액은 세금 포함 `grand_total` / 재전송은 「이미 그 플랜」으로 알아본다 / 응답 유실은 오류로 답하고 웹훅이 맞춘다(별도 맞추기 작업 없음) / 장부 스키마 변경 없음.

## 정해야 할 것

1. **Paddle 에서 「내리기」(등급↓ · 주기↓)를 어떻게 하나** — Paddle 은 품목 변경 예약이 없다(§2).
   - **A (추천)** Paddle 기본 그대로: 바로 바뀌고 남은 몫은 다음 결제에서 빠진다(주기↓는 크레딧 잔액). 예약 · 거두기 없음. — Paddle 이 자기 고객 포털에서 쓰는 방식이라 결제사 상태와 장부가 갈라지지 않고, 손님은 남은 몫을 돌려받는다. 대신 KR(토스) 구독자는 「다음 결제일부터」, Paddle 구독자는 「바로」로 앱 안에서 다르게 보인다.
   - B 우리 규칙 흉내: Paddle 품목은 지금 `do_not_bill` 로 바꾸고(다음 갱신부터 낮은 금액) 우리 장부는 기간 끝까지 옛 플랜을 유지, 거두기는 옛 가격으로 다시 `do_not_bill`. 예약 중 올리기는 거절해야 한다(Paddle 이 낮은 가격 기준으로 일할해 손님이 더 낸다). 주기↓는 `next_billed_at` 을 같이 보내야 하는데 문서에 근거가 없어 실측 필요. kotlin 조각이 대 → 특대.
   - C 1차는 Paddle 내리기를 막고(409) 올리기 · 주기 늘리기만.
2. **샌드박스 연 가격** — `.env.local` 에 `PADDLE_PRICE_*_YEARLY` 가 없다(월 가격 셋만). 주기 변경(M2 · M4 · 끝 확인 ②~④)을 진짜로 재려면 연 가격이 필요하다.
   - **(추천)** qa 가 샌드박스 API 로 USD 연 가격 셋을 만들고 `.env.local` 에 이름만 더한다(키에 가격 쓰기 권한이 없으면 형이 대시보드에서 셋 만들고 id 를 넣어 준다). — 주기를 바꿀 때 Paddle 기간이 다시 시작하는지는 문서에 없어 실측해야만 안다. 샌드박스라 돈 · 실계정에 영향 없다.
   - 등급 바꾸기만 샌드박스로 재고 주기 변경은 가짜 Paddle e2e 로만.

## 실측 결과(2026-10-29)

샌드박스 구독 1개(USD, basic 월 3.99, 기간 10-13~11-13, 실측 시각 10-29 02:03 UTC). 금액은 최소 통화 단위(센트), 세금 포함. 고객·주소·결제 수단 id 는 지웠다. 키 권한: 구독 읽기·쓰기(PATCH·preview)는 되고, 가격·상품 읽기/만들기와 `/events` 는 `forbidden` — 연 가격을 못 만들어 월→연(M2)·연→월(M4)은 못 쟀다.

| M# | 요청 본문 | 핵심 응답 칸 | 결론 |
|---|---|---|---|
| M1 | `PATCH /subscriptions/{id}/preview` `{items:[{price_id: standard 월, quantity:1}], proration_billing_mode:"prorated_immediately"}` | `immediate_transaction.details.totals`: `total` 148 · `grand_total` 148 · **`credit` 0** · `credit_to_balance` 0 · `balance` 148. `update_summary`: `credit.amount` **-197** · `charge.amount` 345 · `result` {action `charge`, amount 148}. `immediate_transaction.billing_period` = 10-29~11-13(일할 구간). 응답 `current_billing_period` 는 그대로 10-13~11-13, `next_transaction` 699 · `recurring_transaction_details.totals.total` 699 | 청구액 = `grand_total`(= charge − credit 로 이미 상계된 값). **`totals.credit` 은 0 — 일할 크레딧은 `update_summary.credit.amount`(음수)에만 있다.** 같은 주기 올리기는 기간이 안 바뀐다 |
| M2 | (연 가격 없음) | — | 못 잼 |
| M3 | 내리기 `prorated_next_billing_period` | — | 형 결정으로 건너뜀 |
| M4 | (연 가격 없음) | — | 못 잼 |
| M5 | 같은 품목(basic)으로 preview 와 PATCH | 둘 다 200, `immediate_transaction` null · `update_summary` null, 새 거래 없음(거래 목록 그대로 1건) | 아무 일도 없다. 5.2 ① 의 「이미 그 플랜이면 200」은 우리 쪽 검사로 충분하고 Paddle 에 보내도 안전하다 |
| M6 | (건드리지 않음) | — | 못 잼. 최소 금액 미만 오류 코드는 문서 값(`subscription_update_transaction_balance_less_than_charge_limit`) 그대로 두고 가짜 Paddle 로만 시험 |
| M7 | 실제 `PATCH /subscriptions/{id}` basic→standard 월, `prorated_immediately` | **PATCH 응답**: 구독 전체는 오지만 `immediate_transaction` · `next_transaction` · `update_summary` 가 **없음/null**. 응답 items = standard, 기간·`next_billed_at` 그대로. 곧바로 생긴 거래: `origin: subscription_update`, status `completed`, `payments[0]` captured 148, `details.totals` total/grand_total 148 · **credit 0** · balance 0, `billing_period` = **전체 현재 기간** 10-13~11-13(미리보기의 일할 구간 아님). `line_items` 2줄: 새 품목 quantity 1 total 345, 옛 품목 quantity **-1** total **-197**, 둘 다 `proration.rate` 0.49308. `/adjustments?subscription_id` 는 빈 목록(크레딧 adjustment 없음) | 올리기는 응답 즉시 결제 완료. 웹훅 순서·내용은 `/events` 권한이 없어 못 봄(S1 에서 샌드박스 웹훅 수신으로 확인) |
| 추가(내리기 막는 근거) | preview, standard→basic `prorated_immediately` | `immediate_transaction.details.totals`: `total` -148 · `grand_total` 0 · `credit_to_balance` 148. `next_transaction` 399. `update_summary` {credit -345, charge 197, result action `credit` 148} | 내리면 결제 없이 148 이 고객 크레딧 잔액으로 쌓인다 — 막는 결정과 맞음 |

### K1 개발자에게 (문서와 다르게 나온 것)
1. 일할 크레딧을 `details.totals.credit` 으로 읽지 말고(항상 0) 미리보기는 `update_summary.credit.amount` 의 절댓값으로 읽는다. `charge` 는 문서대로 `immediate_transaction.details.totals.grand_total`(이미 크레딧을 뺀 값).
2. 실행(PATCH) 응답에는 금액이 없다(`immediate_transaction`·`update_summary` null) — 청구액·기간은 직전 미리보기 값을 쓰고, PATCH 응답에서는 `items` 만 믿는다.
3. 웹훅 `transaction.completed`(`origin: subscription_update`)는 `details.totals.credit` 이 0 이고 `total` 이 이미 상계된 실제 낸 돈이다. 계획의 「`credit > 0` 이면 주문 amount = total − credit」 규칙은 이 경로에서 발동하지 않으니, 변경 결제는 `total` 그대로 기록하고 credit 규칙은 갱신 결제용으로만 둔다(갱신에서 잔액 크레딧이 쓰일 때 값이 어떻게 오는지는 미실측). 거래 `billing_period` 가 일할 구간이 아니라 현재 기간 전체이므로 기간 갱신에 쓰지 않는다.
4. 같은 주기 올리기는 `current_billing_period` 가 바뀌지 않는다(`restartsPeriod` false).

### 구현이 따른 것 (K1)
적용(PATCH)은 응답의 품목만 확인하고(가격이 다르면 「모름」) 금액 · 기간은 직전 미리보기 견적을 쓴다. 미리보기 크레딧 = `|update_summary.credit.amount|`. 같은 주기 올리기는 `restartsPeriod=false`. **월 → 연 변경의 `restartsPeriod` · 새 기간은 미실측**이라 판정(`IMMEDIATE_NEW_PERIOD`)대로 `true` 로 두었다 - 연 가격을 만든 뒤 재서 고친다.
