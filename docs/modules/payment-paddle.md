# payment-paddle

Paddle Billing(v2 API)을 `payment` 의 제공자로 구현한다. Paddle 이 판매자(해외 세금 대행)이고, 결제창은 프론트의 Paddle.js, 구독 갱신도 Paddle 이 한다.
서버가 먼저 Paddle 거래를 만들고(`POST /transactions`, 가격 id 는 `ProviderPriceRefs`(카탈로그의 링크) → 설정 `prices` 지도 순으로 찾고 둘 다 없으면 거래를 만들지 않고 `503 UNAVAILABLE`. 링크를 결제창이 열린 동안 바꾸면 그 결제는 품목 대조에서 「확인 필요」로 간다 — 설정 지도를 바꿔도 같다) 결제창은 그 거래 id 만 연다. 서버는 **서명된 웹훅**(`Paddle-Signature` = `ts=…;h1=…`, HMAC-SHA256(`ts:원문`), 시각 허용 오차 밖이면 거절)으로 결과를 받아 장부에 옮긴다. Paddle API 는 거래 만들기 · 구독 해지 · **구독 플랜 올리기** · **환불**(`POST /adjustments`)과 환불 전 확인의 **거래 조회**(`GET /transactions/{id}?include=adjustments`)에 부른다.

**환불**: 요청 금액이 거래 총액이고 승인 · 검토 중 환불이 없으면 `type: full`, 아니면 `partial` + 거래 품목 하나(서버가 만든 거래는 품목이 하나다 — 둘 이상이면 확정 거절 `PARTIAL_NOT_ALLOWED`). 금액은 구매자가 낸 세금 포함 총액 그대로다(`tax_mode` 를 보내지 않는다). 실계정은 대부분 Paddle 이 검토한 뒤(`pending_approval`) 승인 · 거절을 `adjustment.updated` 웹훅으로 알린다 — 장부는 줄을 `PENDING` 으로 두고 웹훅이 끝낸다(샌드박스는 10분마다 자동 승인). 검토 중인 환불이 있는 거래에는 새 환불을 보내지 않는다. Paddle 에는 멱등 키 헤더가 없다 — 「환불 한 줄에 호출 한 번」을 장부가 지키고, 모름(`adjustment_pending_refund_request` · `*_above_remaining_allowed` · 5xx · 시간 초과)이면 다시 보내지 않고 조회가 가린다. 확정 거절은 허용 목록(`PaddleErrorMapper.DEFINITIVE_CODES`)뿐이다. 조회는 거래 번호로만 된다(`answersByOrderId=false`). **차지백**은 Paddle 이 자동으로 만든 adjustment(`chargeback` · `chargeback_warning`, 되돌림은 `chargeback_reverse` · `chargeback_warning_reverse` 또는 원래 adjustment 가 `reversed`)이고 같은 두 웹훅으로 온다 — 장부가 사유 `CHARGEBACK` 환불 줄로 넣고 분쟁에서 이기면 되돌린다(자세히: [payment.md](payment.md)). 거래 조회는 승인된 환불 · 차지백(경고와 같은 금액의 차지백은 한 번만)을 이뤄진 취소로, `pending_approval` 환불을 검토 중으로 알려 준다. 크레딧(`credit` · `credit_reverse`)은 반영하지 않는다. 진짜 Paddle 에서 잰 것과 못 잰 것: [real-provider-setup.md §14](../real-provider-setup.md).
**플랜 올리기**: `PATCH /subscriptions/{id}`(미리보기는 `…/preview`)에 바뀐 뒤의 품목 하나 `{price_id, quantity: 1}` 과 `proration_billing_mode: prorated_immediately` 를 보낸다(`on_payment_failure` 는 보내지 않는다 - 기본 `prevent_change` 라 결제가 실패하면 바뀌지 않는다). 올리기(등급↑ · 월 → 연)만 하고 내리기는 막는다(`PlanChangeRules` 가 예약으로 판정하는 변경은 `409 PLAN_CHANGE_DOWNGRADE_UNSUPPORTED` - 크레딧 잔액이 생기는 길을 열지 않는다). 견적은 `immediate_transaction.details.totals.grand_total`(크레딧을 뺀 지금 결제액) · `update_summary.credit` · `recurring_transaction_details.totals.total`(앞으로 갱신마다) · 응답의 `current_billing_period` 에서 읽고 통화는 응답의 `currency_code` 다. **적용(PATCH) 응답에는 금액 · 기간이 없다**(샌드박스 실측) - 적용은 본문을 읽지 않고, 장부는 직전 미리보기의 견적을 쓴다. Paddle 최소 금액 미만(`subscription_update_transaction_balance_less_than_charge_limit`)이면 같은 호출을 `do_not_bill`(과금 없이 바꾸기)로 한 번 더 한다. 오류: `subscription_payment_declined` → 결제 거절(플랜 그대로), 갱신 잠금 · 연체 · 미결 거래 · 즉시 결제 한도 같은 막힘 → `409 PLAN_CHANGE_NOT_ALLOWED`, 그 밖 4xx 인증 · 권한 → 확정 실패, 5xx · 시간 초과 → 모름(허용 목록은 `PaddleErrorMapper.PLAN_CHANGE_DEFINITIVE_CODES`).
`subscription.updated` 가 `recurring_transaction_details` 를 실었으면 플랜과 함께 정기 금액도 맞춘다(안 실었으면 다음 결제가 맞춘다). 구독 변경 거래(`origin=subscription_update`)는 갱신이 아니라 주문만 남기고, 결제 수단 변경 거래(`subscription_payment_method_change`)는 주문 없이 수신함 줄만 닫고, 음수 `total`(바깥 내리기)은 0 원 주문이다. 호출 제한 시간은 `plan-change-timeout`(기본 30초).
**우리 규칙과 다른 점**: 금액은 Paddle 이 계산한다(분 단위 일할 · 세금 포함 · 구매자 통화 - 앱의 `plan-change.charge` · `min-charge` 는 토스 몫이다) / 202(결제 확인 중)가 없다(Paddle 은 결제까지 끝낸 뒤 응답한다 - 응답을 잃으면 오류로 답하고 `subscription.updated` 웹훅이 플랜을 맞춘다) / 남은 몫이 새 가격보다 크면 차액은 Paddle 의 고객 크레딧 잔액이 된다(보이지도 환불하지도 않는다) / 같은 요청의 재전송은 「이미 그 플랜」으로 알아본다.
**웹훅**: `transaction.completed` 의 `origin=subscription_update` 는 변경 결제(`SUBSCRIPTION_CHANGE` 주문 한 줄, 구독 금액 · 기간을 건드리지 않는다)이고, 갱신 결제에 `details.totals.credit` 이 있으면 주문 금액 = 실제로 낸 돈 · `discount_amount` = 크레딧이다(변경 거래의 `total` 은 이미 크레딧을 뺀 낸 돈이라 그대로 기록한다). `subscription.updated` 의 품목 하나가 가리키는 가격(카탈로그 링크 → 설정 `prices` 순으로 거꾸로 찾는다)이 줄의 플랜과 다르면 플랜 코드를 맞춘다(금액은 다음 결제가 맞춘다).

웹훅 주소는 `/api/v1/payments/webhooks/paddle`. 금액 · 통화는 웹훅 값 그대로 기록한다(여러 통화).

프로젝트 여럿([multi-project](../multi-project.md) "결제"): 프로젝트마다 자기 `payment-paddle.*`(API 키 · 클라이언트 토큰 · 웹훅 비밀값)로 게이트웨이를 만든다. 웹훅은 도메인으로 프로젝트가 정해지고 그 프로젝트의 비밀값으로 서명을 확인한다 — A 의 비밀값으로 서명한 웹훅은 B 도메인에서 거절.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment-paddle"))` |
| 함께 오는 모듈 | `platform`, `payment`, `crypto` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.payment-paddle` — [docs/config/modules/payment-paddle.yml](../config/modules/payment-paddle.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 와 세 비밀(`api-key` · `client-token` · `webhook-secret`)이 모두 있어야 켜진다 — 하나라도 비면 이 결제사만 없다(서버는 뜬다). |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `PaddlePaymentProvider` |
| 필요한 API 키 권한 | 거래 만들기 · 구독 해지 · **플랜 올리기(`subscription.write`)** 에 더해 **`adjustment.write` · `adjustment.read` · `transaction.read`**, 그리고 결제한 사람의 나라를 읽는 **`customer.read` · `address.read`**(없으면 결제 뒤 `could not look up the buyer address country` 로 나라를 못 읽는다). 알림 대상(웹훅)에 **`adjustment.created` · `adjustment.updated`** 도 켠다 |
| 마이그레이션 | 없음 (`payment-jdbc` 의 구독 열 두 개를 쓴다) |
| 프론트 짝 | `@skeleton/payment` (Paddle.js 결제창) |
| 테스트 | `modules/payment-paddle/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
