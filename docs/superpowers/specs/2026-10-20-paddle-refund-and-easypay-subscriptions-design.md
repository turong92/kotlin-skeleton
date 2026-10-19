# Paddle 환불 · 카카오페이/네이버페이 정기결제 — 설계

> 2026-10-20. 요청(원문): 「환불 하고 정기결제 넘어가자」 — 묶음 A(Paddle 환불 + 환불 못 하는 결제사 화면 처리)를 먼저, 묶음 B(카카오 · 네이버 정기결제 직접 연동)를 다음에.
> 대상: kotlin-skeleton(`payment` · `payment-jdbc` · `payment-paddle` · `payment-kakaopay` · `payment-naverpay` · `apps/sample`) + react-skeleton(`@skeleton/payment` · `apps/sample` · e2e 가짜 결제사).
> 앞 설계: `2026-10-08-payment-ledger-design.md`(장부) · `2026-10-09-subscription-design.md`(구독) · `2026-10-13-paddle-design.md`(Paddle 1차 — 환불은 2차로 미뤘다).

## 결정 (2026-10-20, 형)

| # | 물음 | 형 원문 | 결정 |
|---|---|---|---|
| 1 | 카카오 첫 결제 · 검증 방법 | 「가라로 하기로 안했니?」 | 카카오 문서 기본 흐름 — **1회차를 플랜 금액으로 결제하고 그 승인에서 SID** 를 받는다(§4.3). 카카오 · 네이버 정기결제 모두 **진짜 결제창 확인 없이 가짜 결제사 서버(e2e)로만** 검증한다. 진짜 키 확인은 「계약 · 키 생긴 뒤」(§9.3) |
| 2 | 구독 결제를 전액 환불하면 구독도 끝내나 | 「케이스마다 다를거 같은데. 우리 과실일 수도 있어서. 근데 선택 로직이 쉽지않다면 해지까지.」 | **체크, 기본 켜짐** — 운영자 환불 창(요청 승인 · 바로 환불)에 「구독도 해지」 체크(기본 켜짐), 끄면 환불만. 토스 · 카카오 · 네이버 · Paddle 구독 결제 모두 같다(§3.4) |
| 3 | Paddle 차지백 | 「장부에 남김」 | 웹훅 → 환불 줄 기록, 분쟁 결과(되돌림)까지 반영 — 조각 하나 더(§3.5) |

## 0. 비목표

- Paddle: 크레딧(`credit` · `credit_reverse`) 반영, 중도 해지 환불(`cancel-now`)과 플랜 변경, 환불 금액의 세금 따로 계산(`tax_mode=external`), 분쟁에서 이겨 돈이 돌아왔을 때 지급(이용권)을 자동으로 되살리기.
- 카카오 · 네이버: 진짜 결제창 · 진짜 키로 재기(계약 · 키 생긴 뒤), 결제 수단 바꾸기(새 SID · 새 등록), 순차결제(`TCSEQUENCE`), 네이버 SDK 등록창(SDK 없이 서버 예약 → 창 주소만), 무료 체험 · 0원 시작, 웹훅(둘 다 문서에 결제 이벤트 웹훅이 없다).
- 토스 · 카카오 · 네이버의 차지백(결제사가 알려 주는 길이 없다 — 지금 장부의 「결제사 쪽 취소 맞춰 넣기」가 받는 만큼만).
- 사용자 경로의 「환불 요청」 단추를 결제사 능력으로 막기, 운영자 「결제사와 맞추기」 단추를 능력으로 숨기기(이번에는 환불 단추만). 부분 환불로 구독을 끝내는 선택.
- 결제사의 스케줄러(토스와 같다 — 언제 얼마를 받을지는 우리 장부가 정한다).

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| A | 운영자가 Paddle 결제(단건 · 구독 결제)의 환불 요청을 승인하거나 바로 환불하면 Paddle 환불(adjustment)이 나가고, Paddle 의 승인 · 거절이 웹훅으로 장부에 닿는다. 차지백 · 그 되돌림도 웹훅으로 환불 줄에 남는다. 환불 창의 「구독도 해지」 체크가 구독 결제 전액 환불의 구독 반응을 정한다(모든 결제사). 환불 능력이 없는 결제사의 주문은 단추가 막히고 이유가 보이며 서버는 `409 PAYMENT.REFUND_UNSUPPORTED`. 형이 샌드박스로 2026-10-13 의 열린 요청(USD 3.99)을 승인해 `SUCCEEDED` 까지 본다 | 사용자 쪽 막기 · 맞추기 단추 능력 표시 · 분쟁 승소 시 지급 되살리기 |
| B | 「구독하기」에서 결제 수단(토스 카드 · 카카오페이 · 네이버페이)을 고르고, 카카오 · 네이버로 등록 → 첫 결제 → 갱신 → 해지(키 비활성) → 구독 결제 환불이 돈다. **둘 다 가짜 결제사 서버로 e2e** | 진짜 키 실측(§9.3) · 카카오 SID 상태 조회로 「모름」 자동 판정 · 결제 수단 바꾸기 |

## 2. 확장 지점 (developer 는 이것만 만든다)

| 지점 | 고른 이유 |
|---|---|
| `PaymentSnapshot.pendingRefunds`(기본 빈 목록) | 결제사가 「접수, 검토 중」으로 쥔 환불(Paddle `pending_approval`)을 정산이 「적용 안 됨」으로 닫지 않게 — 결제사 중립 칸 하나 |
| `PushedRefund`(종류 `REFUND` · `CHARGEBACK` · `CHARGEBACK_REVERSAL`) | Paddle 의 승인 · 거절 · 차지백은 조회 스냅샷으로 표현되지 않는다 — 서명된 웹훅 본문으로 줄을 닫는다. 기존 `PushedPayment` 와 같은 길 |
| 환불 줄의 `keep_subscription`(새 열, 기본 false) | 「구독도 해지」 선택은 환불이 **끝나는 때**(Paddle 은 며칠 뒤 웹훅) 적용되므로 줄에 남아야 한다 |
| 환불 줄 상태 `REVERSED` · 주문 전이 `REFUNDED`/`PARTIALLY_REFUNDED` → `PARTIALLY_REFUNDED`/`PAID`(결제사 증거로만) | 분쟁에서 이기면 돈이 돌아온다 — 환불 줄은 양수만 받으므로(`ck_payment_refunds_amount`) 음수 줄 대신 원래 줄을 되돌린다 |
| `PaymentBillingGateway.prepareBilling` · `registrationCharges`/`registerWithCharge` | 토스는 등록 창 값이 설정뿐이지만 카카오 · 네이버는 **서버가 먼저 예약**해야 창을 연다. 카카오는 **첫 결제가 곧 등록**(결정 1) |
| `PaymentLookupGateway.answersByOrderId`(기본 true) | 카카오는 거래 번호로만 조회된다 — 「주문 번호로 물었더니 모른다」를 「결제가 없다」로 읽으면 이중 과금 위험(§8) |
| `PaymentLookupQuery.recurring` · `PaymentRefundRequest.recurring`(기본 false) | 정기 결제는 다른 가맹점 코드(카카오 `TCSUBSCRIP`) · 다른 인증값(네이버 자동결제)으로 조회 · 취소한다. 장부가 `origin=SUBSCRIPTION` 인 주문에 true 로 넘긴다 |
| 결제사 빈 갈래: `KakaoPayBillingProvider` · `NaverPayRecurringProvider` | 능력은 인터페이스로 선언되므로(라우터는 `is` 로 본다) 정기 설정이 비면 빈 자체가 단건 클래스여야 한다 — 설정이 있을 때만 하위 클래스로 만든다 |

## 3. 묶음 A — Paddle 환불 · 차지백 · 구독 해지 선택

### 3.1 문서에서 확인한 것 (2026-10-20 읽음)

[Create an adjustment](https://developer.paddle.com/api-reference/adjustments/create-adjustment) · [Refund or credit a transaction](https://developer.paddle.com/build/transactions/create-transaction-adjustments) · [Adjustments 개요](https://developer.paddle.com/api-reference/adjustments/overview) · [List adjustments](https://developer.paddle.com/api-reference/adjustments/list-adjustments) · [adjustment.updated](https://developer.paddle.com/webhooks/adjustments/adjustment-updated) · [Get a transaction](https://developer.paddle.com/api-reference/transactions/get-transaction) · [오류 코드 목록](https://developer.paddle.com/llms/errors.txt) · [구독 해지](https://developer.paddle.com/build/lifecycle/subscription-cancellation).

- `POST /adjustments` `{action: refund, transaction_id, reason, type, items?}` — 권한 `adjustment.write`. 환불은 `completed` 거래에만. **`type: full` 은 거래 총액 전체**, `partial`(기본)은 `items[{item_id: txnitm_…, type: full|partial, amount}]` 가 필수다. 부분 단위는 **거래 품목(라인아이템)별 금액**이다.
- **세금**: `tax_mode` 기본 `internal` = 보낸 금액이 **세금 포함**(부분 환불에만 뜻이 있다). 우리 장부의 금액도 구매자가 낸 세금 포함 총액이라 기본값 그대로 쓴다(결제사 기본값).
- **승인**: 실계정은 대부분 `pending_approval` → Paddle 검토 뒤 `approved` · `rejected`(USD 400 이하 · 잔액 안 · 계좌이체 아님 등이면 바로 `approved`). **샌드박스는 10분마다 모든 환불을 자동 승인**한다. 검토 중인 환불이 있는 거래에는 새 adjustment 를 만들 수 없다(`adjustment_pending_refund_request`). adjustment 는 고치거나 지울 수 없다.
- **차지백**: `action` 값 — `chargeback`(「구매자가 이의 제기에 성공하면 Paddle 이 자동으로 만든다」) · `chargeback_warning`(「다가올 차지백의 경고」, 조기 경고가 있는 차지백 — 「이의 금액은 환불된다」) · `chargeback_reverse`(「Paddle 이 차지백에 이의를 제기해 이기면」 맡긴 금액을 돌려주려고 만든다) · `chargeback_warning_reverse`(경고의 되돌림). 상태 `reversed` = 「되돌림 adjustment 가 만들어졌을 때 Paddle 이 원래 것에 찍는다」. 모두 Paddle 이 자동으로 만들고 웹훅은 같은 `adjustment.created` · `adjustment.updated`.
- 웹훅 `adjustment.created`(만들 때) · `adjustment.updated`(승인 · 거절 · 되돌림) — 문서는 둘 다 받으라고 한다. 본문 `data` = adjustment(id `adj_`, `action`, `transaction_id`, `subscription_id`, `status`, `totals.total` …).
- 거래 조회 `GET /transactions/{id}?include=adjustments` — `details.line_items[].id`(`txnitm_`, 「adjustments 에 쓴다」), `details.totals`, `details.adjusted_totals`.
- **환불 · 차지백이 구독에 미치는 영향은 문서에서 못 찾았다** → 우리 장부 규칙(§3.4)으로 정한다.

### 3.2 환불 흐름 — 기존 환불 장부(요청 → 선점 → 호출 → 확정) 그대로

1. **환불 전 확인**: Paddle 이 이제 `PaymentLookupGateway` 를 가진다 — `GET /transactions/{txn}?include=adjustments`. 대응: `completed` → 받았다(승인된 환불 · 차지백 합에 따라 `PAID` · `PARTIALLY_REFUNDED` · `REFUNDED`) / `draft` · `ready` · `billed` · `paid` · `past_due` → 진행 중 / `canceled` → 실패. `total` = `details.totals.total`(장부가 웹훅에서 기록한 칸과 같은 칸). `refunds` = `approved` 인 `refund` · `chargeback` · `chargeback_warning`(`reversed` 는 뺀다), `pendingRefunds` = `pending_approval` 인 `refund`. 주문 번호로만 물으면 호출 없이 null(`answersByOrderId=false`) — 지금 만료 작업의 동작(조회 없이 닫기)과 같다. 검토 중 환불이 있으면 보내지 않는다(`409 REFUND_IN_PROGRESS`).
2. **선점** — 지금과 같다(`PENDING` 줄, 정산 작업). 줄에 `keep_subscription`(§3.4)을 같이 쓴다.
3. **호출**(`PaymentRefundGateway.refund`): 요청 금액 = 거래 총액이고 승인 · 검토 중 환불이 없으면 `type: full`, 아니면 `type: partial` + 품목 하나(`txnitm_` 은 같은 호출 안에서 거래 조회로 얻는다; 서버가 만든 거래는 품목이 하나다 — 둘 이상이면 확정 거절 `PARTIAL_NOT_ALLOWED`). `reason` = 장부의 고정 문구 + 환불 줄 id(개인정보 없음, 사람이 대시보드에서 잇는 열쇠). 결과: `approved` → `REFUNDED`(금액 = `totals.total`, 거래 키 = `adj_` id), `pending_approval` → `PENDING`(거래 키 = `adj_` id).
4. **확정**: 결과가 `PENDING` 이고 거래 키가 있으면 **줄의 `provider_refund_id` 에 박고** `PROVIDER_PENDING` 표시(코어 변경 — 지금은 표시만 남긴다). 정산 작업은 스냅샷의 `pendingRefunds` 에 그 id(또는 같은 금액)가 있으면 닫지 않는다.
5. **웹훅이 끝낸다**(`action=refund`): `PushedRefund` → 주문(`provider_payment_id = txn`)을 잠그고 그 `adj` 의 줄 → 없으면 그 주문의 열린 `PENDING` 줄 중 거래 키가 비고 금액이 같은 것에 붙인다(응답 유실 — Paddle 은 거래당 검토 중 환불이 하나뿐이고 우리는 주문당 열린 환불이 하나뿐이라 짝이 하나다).
   - `approved`: 줄 `SUCCEEDED`(`RefundLines.succeed`, 금액이 다르면 지금처럼 `REFUND_MISMATCH` + `RESULT_MISMATCH`). 줄이 없으면(대시보드에서 낸 환불) `PROVIDER` 줄로 넣는다(`RefundLines.addFromProvider`, 사유 `PROVIDER_CONSOLE`).
   - `rejected`: 줄 `FAILED`, `failureCode=PROVIDER_REJECTED`(새 우리 코드) — 주문은 다시 환불할 수 있다. 줄이 없으면 무시.
   - `pending_approval`: 거래 키만 붙인다. `credit` · `credit_reverse` 는 `IGNORED`.
   - 끝 상태가 된 줄은 다시 움직이지 않는다(뒤바뀐 순서 · 재전송은 같은 결과).
6. **지급 회수 · 구독**: `SUCCEEDED` 는 지금처럼 `onRefunded` · 회수 작업, 구독 반응은 §3.4.

오류 분류(허용 목록, 나머지는 모름): 확정 = 4xx 인증 · 권한(`invalid_token` · `forbidden`) · `adjustment_amount_cannot_be_zero` · `adjustment_payout_earnings_cannot_be_zero` · `adjustment_transaction_invalid_status_for_refund` · `adjustment_transaction_without_captured_payment` · `adjustment_transaction_item_invalid` · `adjustment_invalid_combination_of_types` · `adjustment_tax_mode_not_allowed` · `adjustment_transaction_missing_customer_id`. **모름**(앞선 요청이 적용됐을 수 있다) = `adjustment_pending_refund_request` · `adjustment_*_above_remaining_allowed` · `adjustment_transaction_item_has_already_been_fully_adjusted` · `adjustment_transaction_item_over_adjustment` · 5xx · 시간 초과. Paddle 에는 멱등 키 헤더가 없다(문서에서 못 찾음) — 장부의 「환불 한 줄에 호출 한 번」이 막는다.

### 3.3 환불 못 하는 결제사

- 장부 환불은 `PaymentRefundGateway` + `PaymentLookupGateway`(환불 전 확인) 둘 다 필요하다. A 뒤에는 `payment-stripe`(조회 없음)가 여기에 든다.
- 운영자 응답 `AdminOrder` · `AdminRefund` 에 **`refundBlock`**(늘 오는 칸, `null` = 막힘 없음): `UNSUPPORTED`(그 결제사에 능력이 없다) · `PROVIDER_OFF`(결제사 빈이 지금 없다 — 꺼짐 · 키 없음) · `MODE_MISMATCH`(키 모드가 주문과 다르다). 설정만 보고 계산한다(외부 호출 없음). 환불 요청 목록은 저장소 질의가 주문의 `provider` · `provider_mode` · `kind` 를 같이 읽는다(질의만).
- 서버: 능력이 없으면 승인 · 직접 환불이 **결제사를 부르기 전에** `409 PAYMENT.REFUND_UNSUPPORTED`(새 코드). 빈이 없으면 지금처럼 `503 UNAVAILABLE`(일시적). 계약 §3.2 ③ 의 「`CAPABILITY_UNSUPPORTED` → 503」을 이 코드로 바꾼다.

### 3.4 「구독도 해지」 선택 (결정 2 — 모든 결제사)

- **지금 규칙**: 지금 기간의 구독 주문(첫 결제 · 갱신 — 변경 주문 · 늦은 이중 결제는 빼고)이 전액 환불되면 `SubscriptionLifecycle.onOrderRefunded` 가 구독을 `ENDED(REFUNDED)` 로 끝낸다(누가 낸 환불이든).
- **바뀌는 것**: 운영자 환불의 두 입구가 선택 본문 칸 **`endSubscription`(불리언, 생략 = `true`)** 을 받는다 — `POST /admin/payments/refunds/{id}/approve` `{amount?, endSubscription?}` · `POST /admin/payments/orders/{id}/refunds` `{reasonCode, amount?, endSubscription?}`. `false` 면 줄에 `keep_subscription=true` 를 쓰고, 그 줄이 `SUCCEEDED` 가 돼 주문이 전액 환불될 때 `onOrderRefunded` 가 구독을 끝내지 않는다(환불만). 같은 키 재전송에서 이 값이 다르면 `409 IDEMPOTENCY_CONFLICT`(금액과 같은 규칙).
- **호환**: 칸을 보내지 않으면(지금 프런트 · 다른 앱) 줄은 `keep_subscription=false` — **지금 동작 그대로**. 운영자가 아닌 환불(결제사 콘솔 취소 · 차지백 · 본인의 중도 해지 `cancel-now`)도 지금 규칙 그대로(끝낸다; 중도 해지는 이미 끝낸 뒤 환불한다). 부분 환불은 어느 쪽이든 구독을 건드리지 않는다(지금과 같다).
- **결제사가 갱신하는 구독(Paddle)**: 구독을 끝낼 때 결제사에도 **즉시 해지 작업**(`payment-provider-subscription-cancel`)을 넣는다 — 지금은 탈퇴 때만 넣어, 환불로 끝난 Paddle 구독이 다음 달에도 과금될 수 있었다. 끄면 Paddle 에는 아무것도 보내지 않는다.
- **화면**: 운영자 환불 창(승인 · 바로 환불)에서 주문 `kind` 가 `SUBSCRIPTION_INITIAL` · `SUBSCRIPTION_RENEWAL` 이면 체크 「전액 환불되면 구독도 해지」(기본 켜짐, 설명 한 줄: 「지금 기간 결제를 전액 환불할 때만 적용돼요. 끄면 다음 결제일에 계속 결제돼요」). 다른 주문에는 체크가 없고 칸도 보내지 않는다. 운영자 환불 줄 응답에 `keepSubscription`(불리언, 늘 온다).

### 3.5 Paddle 차지백 (결정 3)

- **기록**: `action` 이 `chargeback` · `chargeback_warning` 이고 `approved` 인 adjustment(웹훅 `created` · `updated`) → 그 주문에 `PROVIDER` 환불 줄 `SUCCEEDED`(사유 **`CHARGEBACK`** — 새 사유, 사용자 · 운영자가 고를 수 없다, 금액 `totals.total`, 거래 키 `adj_` id; `RefundLines.addFromProvider` 와 같은 길이라 `onRefunded` · 회수 작업 · 전액이면 §3.4 의 기본 규칙(구독 끝 + Paddle 해지)이 같이 돈다). 남은 금액을 넘으면(열린 우리 환불과 겹침 등) 지금처럼 `REFUND_MISMATCH`. 주인 알림 `PAYMENT_CHARGEBACK`(`alert` 가 있을 때, 주문 id 만).
- **경고 뒤 차지백**: 같은 거래의 `chargeback_warning` 줄이 이미 있고 금액이 같은 `chargeback` 이 오면 새 줄을 만들지 않는다(같은 이의를 두 번 세지 않게 — 문서가 경고에도 「금액은 환불된다」고 하므로 경고가 곧 차감이다). 금액이 다르면 줄을 만들고 `REFUND_MISMATCH` 로 사람이 본다.
- **분쟁 결과 — 이김(돈이 돌아옴)**: 원래 adjustment 가 `reversed` 로 바뀐 `updated` 웹훅, 또는 `chargeback_reverse` · `chargeback_warning_reverse` 의 `created` 웹훅(같은 거래 · 같은 금액의 `CHARGEBACK` 줄과 짝) → 그 줄 `SUCCEEDED` → **`REVERSED`**(새 상태, 끝 상태), 주문의 환불된 금액에서 빼고 주문 상태를 `REFUNDED`/`PARTIALLY_REFUNDED` → `PARTIALLY_REFUNDED`/`PAID` 로(**결제사 증거로만** 있는 전이 — 표의 다른 길로는 못 간다). 이력 `CHARGEBACK_REVERSED`, 알림 `PAYMENT_CHARGEBACK_REVERSED`. 지급(이용권) · 끝난 구독은 되살리지 않는다(비목표 — 운영자가 판단).
- **분쟁 결과 — 짐**: Paddle 은 따로 알리지 않는다(되돌림이 오지 않을 뿐) — 줄은 `SUCCEEDED` 그대로.
- 차지백은 Paddle 대시보드 · 운영자 화면에서 읽기만 한다(운영자 동작 없음).

## 4. 묶음 B — 카카오페이 · 네이버페이 정기결제

### 4.1 문서에서 확인한 것 (2026-10-20 읽음)

- **카카오** — [정기 결제](https://developers.kakaopay.com/docs/payment/online/subscription) · [주문 조회](https://developers.kakaopay.com/docs/payment/online/payment-detail) · [에러 코드](https://developers.kakaopay.com/docs/getting-started/api-common-guide/error-code)(본문은 `https://developers.kakaopay.com/api/v1/documents/<문서 코드>/html` 로 받았다). 테스트 가맹점 코드 **`TCSUBSCRIP`** + Secret key(dev). **1회차 = 정기용 CID 로 단건 결제(`ready` → `approve`)이고 승인 응답에 `sid`** 가 온다(0원이면 승인 없이 SID 만 — 「프로모션 등」, 쓰지 않는다). 2회차부터 `POST /online/v1/payment/subscription {cid, sid, partner_order_id, partner_user_id(첫 결제와 같아야 함), item_name, quantity, total_amount, tax_free_amount}` — 실패 예 `-782` + `extras.method_result_code`. 해지 `POST /online/v1/payment/manage/subscription/inactive {cid, sid}`, 상태 `…/status`(`available` · `status` · `last_approved_at`). 결제 수단 변경 = 새 SID + 옛 SID 비활성. **주문 조회는 `tid` 로만**, 응답에 `sid` 칸은 없다. 카드 정보는 `bin` · 발급사뿐(끝 네 자리 없음).
- **네이버** — [개요](https://docs.pay.naver.com/docs/recurrent-payment/recurrent-payment-overview) · [등록 예약](https://docs.pay.naver.com/docs/more-information/non-sdk-integration/recurrent-regist-reserve) · [등록창 호출](https://docs.pay.naver.com/docs/more-information/non-sdk-integration/recurrent-payment-auth-window) · [등록 완료](https://docs.pay.naver.com/docs/recurrent-payment/binding/recurrent-register-approval) · [등록 해지](https://docs.pay.naver.com/docs/recurrent-payment/binding/recurrent-expire-request) · [자동결제 예약](https://docs.pay.naver.com/docs/recurrent-payment/payment/recurrent-pay-reserve) · [자동결제 승인](https://docs.pay.naver.com/docs/recurrent-payment/payment/recurrent-pay-approval) · [취소](https://docs.pay.naver.com/docs/recurrent-payment/payment/recurrent-payment-cancel) · [결제 내역 조회](https://docs.pay.naver.com/docs/recurrent-payment/additional/payments_history) · [멱등성](https://docs.pay.naver.com/docs/common/idempotency) (주소 뒤 `.md` 로 본문). **등록은 돈이 움직이지 않는다**: `recurrent/regist/v1/reserve {productCode(같은 코드는 중복 등록 불가), productName, totalPayAmount(표시용), returnUrl, merchantUserId(개인정보 불가)}` → `reserveId` → 창 `{서비스 도메인}/payments/recurrent/{reserveId}` → `returnUrl?resultCode=Success&reserveId&tempReceiptId`(`DataExist` · `UserCancel`) → `regist/v1/approval {reserveId, tempReceiptId}` → `recurrentId`(+ 가려진 이메일 — 저장 · 로그 금지). 결제 = `recurrent/pay/v3/reserve {recurrentId, totalPayAmount(≥10원), taxScopeAmount, taxExScopeAmount, productName, merchantPayId(64B), merchantUserId}` → `paymentId` → `recurrent/pay/v3/approval {paymentId}`(제한 60초). 해지 `recurrent/expire/v1/request {recurrentId, expireRequester, expireReason}`(`Expired` = 이미 해지). 취소 · 내역 조회는 단건과 같은 경로. 모든 쓰기에 `X-NaverPay-Idempotency-Key`(재요청은 `409` + 처음 응답). 인증값은 단건과 **클라이언트 아이디 · 체인 아이디가 다르다**.

### 4.2 코어 변경 (`payment`)

- **결제 수단 고르기**: `GET /config` 에 `subscriptionChoices`(이 나라에서 빌링 능력을 가진, 지금 열 수 있는 결제사 id — `choices` 와 같은 규칙, 늘 온다). `POST /subscriptions` 가 선택 본문 `provider`(목록 안일 때만) · `returnOrigin` 을 받는다(목록 밖 · 비면 지금처럼 서버가 정한다).
- **`prepareBilling(BillingPrepareRequest{subscriptionId, customerKey, planName, amount, firstOrderId, returnOrigin}): PreparedBilling{setup, reference}`** — 기본 구현은 `billingSetup(customerKey)` 그대로(토스 무변경). `POST /subscriptions` 가 **트랜잭션 밖에서** 부르고 `reference`(카카오 `tid` · 네이버 `reserveId`)를 `payment_subscriptions.first_transaction_id` 에 박는다(이미 있는 열 · 유니크). 같은 키 재전송이 카드 전이면 다시 예약한다(새 `reference` 로 덮는다 — 단건 카카오와 같은 한계, §8).
- **`issue(customerKey, providerPayload, reference)`** — `reference` 를 더한다(토스는 무시). 네이버는 돌아온 `reserveId` 가 `reference` 와 다르면 결제사를 부르지 않고 400.
- **등록이 곧 첫 결제(카카오, 결정 1)**: `registrationCharges=true` 인 결제사는 `activate` 순서가 바뀐다 — ① 자리 + 첫 주문(`CONFIRMING`, 결제 키 = `reference`, 주문 id = `PaymentOrderIds.initialOrderId(구독 id)` — `ready` 의 `partner_order_id` 와 같아야 해서 미리 정해지는 id) 커밋 → ② `registerWithCharge`(락 밖, 돈이 움직임) → ③ 결과로 주문 확정 + 같은 트랜잭션에서 토큰(SID, 암호문) · 카드 표시를 구독 줄에. 자리에 지면 ②를 부르지 않는다(돈이 안 움직였다). ②가 모름이면 `202`, 조회 작업이 `tid` 로 맞춘다 — 그때는 SID 를 모른다(조회 응답에 없다) → 구독은 `ACTIVE` 이되 토큰이 비고, 갱신 작업이 토큰 없는 구독을 만나면 과금 없이 `ENDED(PAYMENT_FAILED)` + 로그. 낸 기간은 쓴다.
- **모름 보강 두 가지**: ⑴ 조회 작업의 구독 반응(마지막 시도 + 조회 null → 거절로 반응)을 **`answersByOrderId=false` 이고 주문에 결제 키가 없으면 하지 않는다** — `CONFIRM_UNRESOLVED` 로 사람에게. ⑵ `SubscriptionCharges` 가 상태 `UNKNOWN` 인 결과도 받아, 결제 키가 있으면 `CONFIRMING` 주문의 결제 키 자리에 박는다(네이버: 예약으로 받은 `paymentId`).
- **정기 힌트**: 조회 · 환불 요청에 `recurring = (origin == SUBSCRIPTION)` — 토스 · Paddle 은 무시.
- `BillingSetup.clientKey` 를 `String?` 로(리다이렉트 결제사는 공개 키가 없다). 구독 응답에 `easyPayBrand`(결제사 id 에서 — `kakaopay` → `KAKAOPAY`, `naverpay` → `NAVERPAY`, 그 밖 `null`; 열 없음).

### 4.3 카카오 (`payment-kakaopay`)

설정 `subscription-cid`(기본 빈 값 = 정기 없음, env `<P>_PAYMENT_KAKAOPAY_SUBSCRIPTION_CID`). 모드는 단건 `cid` 와 같아야 한다(다르면 기동 실패; `TCSUBSCRIP` 은 이미 TEST). 돌아올 주소는 기존 `return-url` + `kind=KAKAOPAY_BILLING_REDIRECT&provider=<id>&sub=<구독 id>&result=…`(설정 · 개발자센터 도메인 등록이 늘지 않는다).

| 단계 | 호출 | 메모 |
|---|---|---|
| 창 값(`prepareBilling`) | `ready` (`cid`=정기, `partner_order_id`=첫 주문 id, `partner_user_id`=`customer_key`, 금액 · 이름 = 플랜) | `kind=KAKAOPAY_BILLING_REDIRECT`, `attributes`=`pcUrl` · `mobileUrl` · `appUrl`, `reference`=`tid` |
| 첫 결제 + 등록(`registerWithCharge`) | `approve`(`pg_token`) | 응답의 `sid` = 토큰(없으면 모름), 카드 = 발급사 코드만 |
| 갱신(`charge`) | `subscription`(`sid`, `partner_order_id`=주문 id, `partner_user_id`=`customer_key`) | 응답 `tid` 가 주문의 결제 키. `-782` = 확정 거절(우리는 한 주문에 한 번만 보낸다 — 그 호출이 실패했다는 뜻), 그 밖 오류 · 시간 초과 = 모름(조회 불가 → 사람) |
| 해지(`revoke`) | `inactive` | 실패하면 `status` 를 물어 `INACTIVE` 면 정상(멱등) |
| 조회 · 환불 | 기존 `order` · `cancel` | `recurring` 이면 `cid`=정기 |

### 4.4 네이버 (`payment-naverpay`)

설정 `recurrent.{client-id, client-secret, chain-id}`(셋 다 있어야 정기 능력; 단건 값을 물려받지 않는다, env `<P>_PAYMENT_NAVERPAY_RECURRENT_*`). 경로 · 제한 시간은 기존 블록처럼 키로 둔다(가짜 서버용 `base-url` · `window-base-url` 은 단건과 같이 쓴다).

| 단계 | 호출 | 메모 |
|---|---|---|
| 창 값(`prepareBilling`) | `regist/v1/reserve`(`productCode`=구독 id — 다시 구독해도 `DataExist` 가 나지 않게, `merchantUserId`=`customer_key`, `totalPayAmount`=플랜 금액(표시용), 멱등 키 `regist-<구독 id>-<n>`) | `kind=NAVERPAY_BILLING_REDIRECT`, `attributes.windowUrl`=`<창 도메인>/payments/recurrent/<reserveId>`, `reference`=`reserveId`. 돌아올 주소 = `return-url` + `kind=NAVERPAY_BILLING_REDIRECT&provider=<id>&sub=<구독 id>` |
| 등록(`issue`) | `regist/v1/approval`(`reserveId`, `tempReceiptId`) | 토큰 = `recurrentId`. 이메일 · 사용자 구분값은 읽지 않는다 |
| 과금(`charge`) | `pay/v3/reserve`(멱등 키 `reserve-<주문 id>`, `merchantPayId`=주문 id, 전액 과세) → `pay/v3/approval`(멱등 키 `approve-<paymentId>`, 60초) | 예약 실패는 **확정 거절**(승인을 안 불렀으니 돈이 안 움직였다, 분류 `TEMPORARY`). 승인이 모름이면 던지지 않고 `UNKNOWN` + `paymentId` 를 돌려준다(§4.2 ⑵). 승인 확정 거절 = `InvalidRecurrentId` · `UnderAgeAmountLimit` · `FailUnderAgeAmountLimitValidation` · `PaymentBlocked` · `NotEnoughAccountBalance` · `BankCheckTime` · `BankMaintenance` · `MaintenanceOngoing` · `FaultCheckOngoing` · `InvalidMerchant`; `Fail` · `AlreadyOnGoing` · `409` 은 모름 → 내역 조회가 `SUCCESS`/`FAIL` 로 맞춘다 |
| 해지(`revoke`) | `expire/v1/request`(`expireRequester=2`, 고정 사유) | `Expired` = 정상 |
| 조회 · 환불 | 기존 내역 조회 · 취소 | `recurring` 이면 정기 인증값 |

## 5. 장부 · 마이그레이션

- **새 V 파일 하나**(`payment-jdbc`, 두 방언, `./gradlew newMigration -Pname=payment_refunds_keep_subscription -Pmodule=modules/payment-jdbc`): `alter table payment_refunds add column keep_subscription boolean not null default false` — 기본값이 있어 옛 줄 · 업그레이드 시험(`MigrationUpgradeIntegrationTest`)이 그대로 산다. 잠긴 파일은 건드리지 않고 `perl scripts/migrations-lock.pl` 로 새 줄만 더한다.
- 열 없이 쓰는 값: 환불 줄 상태 `REVERSED`(상태 열은 `varchar(16)`, 검사 제약 없음) · 사유 `CHARGEBACK` · `failure_code` `PROVIDER_REJECTED` · 이력 `CHARGEBACK_REVERSED`.
- 쓰는 기존 열: `payment_refunds.provider_refund_id`(Paddle `adj_`) / `payment_subscriptions.first_transaction_id`(카카오 `tid` · 네이버 `reserveId`) · `billing_token`(SID · `recurrentId` 암호문) · `customer_key`(`partner_user_id` · `merchantUserId`) · `card_issuer`. 환불 요청 목록 질의가 주문과 이어 읽는다(질의만).

## 6. API (계약 문서 `payment-http-contract.md` 에 반영)

- 운영자: `AdminOrder.refundBlock` · `AdminRefund.refundBlock`(`null` | `UNSUPPORTED` | `PROVIDER_OFF` | `MODE_MISMATCH`) / 새 오류 `409 PAYMENT.REFUND_UNSUPPORTED` / 승인 · 바로 환불 본문의 `endSubscription`(생략 = `true`), 운영자 환불 줄의 `keepSubscription` / 환불 줄 `status` 에 `REVERSED`, `reasonCode` 에 `CHARGEBACK`(origin `PROVIDER`), `failureCode` 에 `PROVIDER_REJECTED` / 이력 `type` 에 `CHARGEBACK_REVERSED` / 알림 `PAYMENT_CHARGEBACK` · `PAYMENT_CHARGEBACK_REVERSED`. Paddle 직접 환불 · 승인은 대개 `202 PENDING`(검토 중)이다.
- 사용자: `Refund.status` 에 `REVERSED` 가 올 수 있다(차지백 되돌림 — 사용자 한 건 응답) / `GET /config` 의 `subscriptionChoices` / `POST /subscriptions` 의 `provider` · `returnOrigin` / `billingAuth.kind` 에 `KAKAOPAY_BILLING_REDIRECT` · `NAVERPAY_BILLING_REDIRECT`, `clientKey: string | null` / `activate` 의 `providerPayload` = 카카오 `{pg_token}` · 네이버 `{reserveId, tempReceiptId}` / `Subscription.easyPayBrand`.

## 7. 화면 (react-skeleton)

- **A**: `RefundRequestTable` 의 승인 · `AdminOrderPanel` 의 바로 환불 단추를 `refundBlock` 이 있으면 끄고(눌리지 않게 보이는 모양) 바로 옆에 이유 한 줄(「이 결제사는 서버에서 환불할 수 없어요 — 결제사 관리 화면에서 환불한 뒤 요청을 거절하세요」 · 「결제사가 지금 꺼져 있어요」 · 「키 모드가 달라요」). 거절 단추는 그대로. `AdminRefundDialog` 에 구독 결제면 「전액 환불되면 구독도 해지」 체크(§3.4). 문구: `REFUND_UNSUPPORTED` 오류, `PROVIDER_PENDING` 줄 「결제사 검토 중」, `PROVIDER_REJECTED`, 사유 `CHARGEBACK`(「카드사 이의 제기(차지백)」), 상태 `REVERSED`(「분쟁에서 이겨 돌아옴」). 한국어 · 영어 라벨.
- **B**: `/subscribe` 에 `subscriptionChoices` 가 둘 이상이면 「결제 수단」 고르기(카드 · 카카오페이 · 네이버페이, 결제 화면의 고르기와 같은 부품). `openBillingAuth` 가 두 새 `kind` 를 같은 창 이동으로 연다. 돌아온 화면이 `kind` 로 갈라 `activate` 를 부른다(`readBillingReturn` 확장, 실패 · 그만둠 · `DataExist` 는 다시 고르게). 「내 구독」 카드는 카드 끝 네 자리가 없으면 `easyPayBrand`(「카카오페이」 · 「네이버페이」)를 보인다.

## 8. 실패 · 경합

| 상황 | 결과 |
|---|---|
| Paddle 환불 응답 유실 | 줄 `PENDING`(거래 키 없음) → `adjustment.created` 가 금액 · 거래로 짝지어 붙인다. 정산 작업은 `pendingRefunds` 에 있으면 닫지 않는다 |
| 웹훅이 호출 응답보다 먼저 | 줄이 이미 붙었거나 끝났으면 확정 단계는 지금의 「늦은 결과」 규칙대로(같은 거래 키는 무시) |
| Paddle 거절 | 줄 `FAILED`(`PROVIDER_REJECTED`) — 다시 환불하거나 요청을 거절 |
| 「구독도 해지」를 끄고 승인했는데 Paddle 이 며칠 뒤 승인 | 선택은 줄에 있어 웹훅이 끝낼 때 그대로 적용 — 구독 유지 |
| 전액 환불 · 차지백으로 끝난 Paddle 구독의 갱신이 해지보다 먼저 결제됨 | 끝난 구독의 결제 → 지금 규칙(우리 구독이 받지 않은 결제 = 결제사 해지 + 확인 필요)으로 사람이 환불 |
| 차지백과 우리 환불이 겹침(합이 결제액을 넘음) | `REFUND_MISMATCH` — 열린 우리 환불은 보내지 않고 사람이 「결제사와 맞추기」 |
| 차지백 웹훅 재전송 · 경고와 차지백 둘 다 옴 | 거래 키(`adj_`) 유니크 · 경고 짝 규칙으로 한 줄 |
| 되돌림이 차지백보다 먼저 도착 | 짝 줄이 없으면 처리 작업이 실패로 남아 재시도 — 차지백이 들어온 뒤 짝짓는다(재시도를 다 쓰면 수신함 `DEAD`, 운영자 「다시 처리」) |
| 카카오 갱신 과금 응답 유실 | `tid` 를 몰라 조회 불가 → **새 과금 없음**, `CONFIRM_UNRESOLVED`. 운영자는 카카오 가맹점 관리 화면에서 확인한 뒤 「결제 안 됨으로 닫기」(닫기 화면이 이 경우 「결제사 화면에서 먼저 확인」 문구) |
| 카카오 창을 두 번 엶(같은 키 재전송) | 나중 `tid` 만 박힌다 — 앞 창의 `pg_token` 은 `-780`(모름) → 첫 주문 `CONFIRMING` → 조회가 `tid` 로 닫는다. 단건과 같은 알려진 한계 |
| `activate` 연타 | 자리 · 첫 주문 id 유니크 — 결제사 호출은 한 번, 뒤 요청은 같은 답 |
| 네이버 승인 모름 | 결제 키(`paymentId`)가 박혀 내역 조회가 끝낸다 |
| 해지 · 탈퇴 뒤 깨어난 갱신 | 지금과 같다(결제사를 부르지 않는다). 키 비활성은 기존 삭제 작업이 재시도 |

## 9. 검증 목록 (깨지면 사고 나는 것만)

### 9.1 A — 가짜 Paddle HTTP + 서명 웹훅 · 서비스 시험

- 환불: 전액은 `type: full`, 부분은 품목 하나 + 세금 포함 금액 · `tax_mode` 안 보냄 / `pending_approval` → `approved` 웹훅에 `SUCCEEDED` + `onRefunded` 한 번 / `rejected` → `FAILED` 후 다시 환불 가능 / 응답 유실 + `created` 웹훅이 짝짓기, 같은 웹훅 두 번 · 순서 뒤바뀜에도 한 결과 / 대시보드 환불 → `PROVIDER` 줄 / 검토 중이면 두 번째 환불을 보내지 않음 / 모름 코드면 다시 안 보냄 / 기존 Paddle 결제 · 만료 시험이 그대로 초록.
- 구독 해지 선택(토스 대역 + Paddle): 칸 생략 = 지금 동작(전액 환불 → `ENDED(REFUNDED)`) / `false` → 구독 유지 · 다음 갱신 과금 / Paddle 승인이 늦게 와도 줄의 선택대로 / Paddle 구독을 끝낼 때만 결제사 해지 작업 / 같은 키에 다른 선택 → 409 / 업그레이드 시험(새 열 기본값).
- 차지백: `chargeback` → `CHARGEBACK` 줄 · 회수 · 전액이면 구독 끝 / 경고 + 같은 금액 차지백 → 한 줄 / 되돌림(`reversed` 갱신 · `*_reverse` 생성 어느 쪽이든, 둘 다 와도) → 줄 `REVERSED` · 주문 금액 · 상태 되돌림 한 번 / 되돌림이 먼저 오면 재시도 뒤 짝 / 다른 길로는 `REFUNDED` → `PAID` 가 안 됨(전이 표 시험).
- 환불 못 하는 결제사 → 결제사 호출 없이 409 + `refundBlock`.

### 9.2 B — 가짜 결제사 서버로만 (결정 1)

- 카카오(모듈 시험의 가짜 HTTP + react e2e `fakeKakaoPay` 정기 경로): 1회차 승인 → `ACTIVE` · SID 암호문 · 응답 · 로그 · `toString` 에 SID 없음 / 갱신 SID 과금 / `-782` → `PAST_DUE` / 과금 모름 → 다시 안 보내고 구독 반응 없음 / 1회차 모름 → 조회로 `PAID`, 토큰 없는 구독은 갱신 때 과금 없이 끝 / 끝 → `inactive` / 구독 주문 환불 · 조회가 정기 `cid` 로.
- 네이버(가짜 HTTP + `fakeNaverPay` 정기 경로 · 가짜 등록창): 돌아온 `reserveId` 바꿔치기 → 400 / 예약 실패 → 확정 거절 · 승인 호출 없음 / 승인 모름 → `paymentId` 박힘 → 조회로 `PAID` / `Expired` 해지 = 정상 / 정기 주문은 정기 인증값 / 이메일 미저장.
- 프런트: 단위 시험 + e2e(구독하기 → 결제 수단 고르기 → 가짜 창 → 돌아옴 → 「내 구독」 브랜드 표시 → 해지).
- **사람 확인**: 형이 샌드박스로 열린 Paddle 요청 승인 → 10분 안에 `SUCCEEDED`(A 의 유일한 진짜 결제사 확인). 카카오 · 네이버는 없음.

### 9.3 계약 · 키 생긴 뒤 (이번 작업 아님)

카카오 `TCSUBSCRIP` + Secret key(dev) 로 1회차 · 갱신 · 해지 · 구독 결제 환불(정기 `cid` 로 조회 · 취소되는지) · `-782` 의 `method_result_code` · 이미 비활성인 SID 의 `inactive` 응답. 네이버 자동결제 인증값으로 등록창 도메인 · `merchantPayKey` = `merchantPayId` · 승인 거절이 `Fail` 로 오는지 · 업무 오류의 HTTP 상태 · 정기 시크릿이 단건과 다른지. 결과는 `real-provider-setup.md` §12 · §13 의 「못 잰 가정」 표에 둔다.

## 10. 작업 목록 — 7조각

| # | 묶음 | 조각 | 선행 |
|---|---|---|---|
| A1 | A · 백 | 코어(`pendingRefunds` · `PushedRefund`(환불) · PENDING 줄 거래 키 · `refundBlock` · `REFUND_UNSUPPORTED` · 「구독도 해지」 선택 + V 파일 · 결제사 구독 해지) + `payment-paddle` 조회 · 환불 · adjustment 웹훅 + 문서(계약 · 모듈 · `real-provider-setup` Paddle 절 신설) | — |
| A2 | A · 프 | 운영자 화면: 단추 막기 · 이유 · 「구독도 해지」 체크 · 문구 + `fakePaddle` adjustment · e2e | A1 |
| A3 | A · 백+프 | Paddle 차지백: `PushedRefund` 차지백 · 되돌림, 줄 상태 `REVERSED` · 주문 되돌림 전이 · 알림 두 개 + 문구 · 라벨 + 계약 | A1 (A2 와 나란히) |
| B1 | B · 백 | 구독 코어(§4.2) + 계약 문서 | A1 · A3(같은 파일을 만지므로 뒤에) |
| B2 | B · 백 | `payment-kakaopay` 정기 + 설정 · 가드 · 문서 | B1 |
| B3 | B · 백 | `payment-naverpay` 자동결제 + 설정 · 가드 · 문서 | B1 (B2 와 나란히) |
| B4 | B · 프 | 결제 수단 고르기 · 돌아온 화면 · 내 구독 표시 + e2e 가짜(`fakeKakaoPay` · `fakeNaverPay` 정기 경로 · 가짜 등록창) | B2 · B3 |

developer 7회(A2 ∥ A3, B2 ∥ B3), 묶음마다 리뷰 1 + 확인 1. 전체 스위트는 묶음 끝에 지휘 세션이 한 번.

## 11. 밖에서 할 일 (형)

- Paddle 샌드박스: API 키 권한에 **`adjustment.write` · `adjustment.read` · `transaction.read`** 를 더하고, 알림 대상(웹훅)에 **`adjustment.created` · `adjustment.updated`** 를 켠다(차지백도 이 둘로 온다).
- 카카오 · 네이버: 이번에는 없음(가짜 서버). 계약 · 키 생긴 뒤 §9.3.

## 정해야 할 것

없음. (설계에서 추천안으로 정한 것 — 바꾸려면 말해 달라: 분쟁에서 이겨 돈이 돌아와도 지급 · 구독은 자동으로 되살리지 않고 알림만 / 운영자가 아닌 전액 환불(결제사 콘솔 취소 · 차지백)은 지금처럼 구독을 끝낸다.)
