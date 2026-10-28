# payment

제공자 중립 결제 계약(`PaymentService`, `PaymentProviderRouter`)과, 저장 어댑터(`payment-jdbc`)가 있을 때 켜지는 **결제 장부**(주문 · 환불 · 이력 · 웹훅 수신함 · 사용자/운영자 HTTP · 탈퇴 고리)다.
제공자(`payment-toss`, `payment-kakaopay`, `payment-naverpay`, `payment-stripe`, `payment-paddle`)가 각자 등록하고, 라우터가 이름으로 고른다.

프로젝트 여럿([multi-project](../multi-project.md) "결제"): 결제사 게이트웨이 · 라우팅(`payment.{default-provider, providers.*, routing.country-providers}`) · 웹훅 비밀 조각(`payment.webhook.path-token`)이 프로젝트마다고 `PaymentProviderRouter` 가 지금 프로젝트의 것으로 넘긴다. 결제사 모듈은 `PaymentGatewaySource` 빈 하나로 이 모양을 따른다(새 결제사도 같다). 장부는 스키마마다, 시각 · 한도 · 구독 정책은 공통. 빌링키 암호화는 프로젝트의 `crypto` 키. 닫는 순서도 같은 문서.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment"))` |
| 함께 오는 모듈 | `platform`, `crypto` |
| 컴파일 전용 | `alert` |
| 설정 접두사 | `skeleton.payment` — [docs/config/modules/payment.yml](../config/modules/payment.yml) |
| 기본 동작 | 조건부 — 라우팅 파사드는 늘 켜짐. 장부 · HTTP · 웹훅은 `PaymentLedgerStore` 빈(`payment-jdbc`)이 있을 때만 선다. 운영자 경로는 그 위에서도 기본 꺼짐. |
| 부팅에 필요한 것 | 없음. 결제하려면 제공자 모듈이 있어야 하고, 장부는 `payment-jdbc` 와 작업 큐(`job-queue-jdbc`)가 있어야 한다. |
| 교체 지점 | `PaymentProviderRouter`, `PaymentService`, 장부: `PaymentCatalog` · `PaymentOrderListener` · `PaymentReviewNotifier` · `PaymentOperatorPolicy`, 구독: `SubscriptionPlans` · `SubscriptionRenewalGuard` · `PaymentBillingGateway` |
| 마이그레이션 | 없음 (장부 표는 payment-jdbc 에 있다) |
| 프론트 짝 | `@skeleton/payment` |
| 테스트 | `modules/payment/src/test` · `modules/payment/src/noOptionalTest` · 시험 대역 `modules/payment/src/testFixtures` |

설계와 근거: `docs/superpowers/specs/2026-10-08-payment-ledger-design.md`. 화면과 맞춘 HTTP 계약: **[docs/payment-http-contract.md](../payment-http-contract.md)**.

## 장부란

- **주문 한 줄 = 결제 한 번**이고 줄의 상태가 돈의 사실이다. 환불은 줄이 따로(한 건 = 한 줄)이고, 모든 변화는 **추가만 하는 이력**(`payment_events`)에 남아 「이 주문이 왜 이 상태인가」를 로그 없이 답한다.
- **결제사와 장부를 맞추는 코드는 `PaymentConvergence.converge` 한 곳이다.** 승인 응답 · 조회 작업 · 웹훅 · 운영자의 「결제사와 맞추기」 · 환불 전 확인이 모두 이것으로 끝난다. 몇 번 불려도 결과가 같고, 바뀐 것이 없으면 줄도 이력도 작업도 건드리지 않는다.
- **락은 DB 의 주문 줄 하나**다(`select … for update`). 모든 전이는 락을 잡고 줄을 **다시 읽은 값**으로 판단한다. **결제사 호출은 락 · 트랜잭션 밖**이다 — 선점(커밋) → 호출 → 확정(커밋) 세 걸음으로 나눈다. 바깥 트랜잭션 안에서 부르면 서비스가 던진다.
- **웹훅은 신호일 뿐이다.** 본문의 상태 · 금액은 어디에도 쓰지 않고, 사실은 늘 결제사 조회에서 온다.
- 장부에는 **자유 문장이 들어갈 열이 없다.** 이름 · 이메일 · 전화 · 전체 카드 번호 · 유효기간 · 소유자명은 받지도 저장하지도 않는다. 개인정보로 볼 수 있는 칸은 카드사와 끝 네 글자 둘뿐이다.
- 장부를 켜는 스위치는 없다 — `payment-jdbc` 의 유무가 스위치다. 없으면 이 모듈은 장부가 생기기 전과 한 글자도 다르지 않다.

## 결제사 능력

한 결제사의 능력은 **한 빈**에 모인다(라우터는 id 당 빈 하나만 받는다 — 같은 id 의 두 빈은 기동 실패). 능력이 없으면 `400 PAYMENT.CAPABILITY_UNSUPPORTED`.

| 능력 | 하는 일 |
|---|---|
| `PaymentConfirmGateway` | 승인. `acceptsPaymentKey`(결제 키 **모양** 확인 — 선점 전에 불리고 **락 안이라 I/O 금지**) · `confirmDeclineCategory`(확정 거절 코드 → `DECLINED` 의 `data.category`) |
| `PaymentRefundGateway` | 전액 · 부분 환불. `refundDeclineCategory`(확정 거절 코드 → `REFUND_DECLINED` 의 `data.category`). **환불은 이 능력과 `PaymentLookupGateway`(환불 전 확인) 둘 다 있어야 한다** — 하나라도 없으면 운영자 환불 · 승인은 결제사를 부르기 전에 `409 REFUND_UNSUPPORTED`, 운영자 응답의 `refundBlock` 이 미리 말한다 |
| `PaymentLookupGateway` | 결제사가 아는 지금 상태(이뤄진 취소 `refunds`, 결제사가 **검토 중으로 쥔** 환불 `pendingRefunds` 포함). 결제가 없으면 null. `answersByOrderId`(기본 true)가 false 인 결제사(거래 번호로만 조회되는 Paddle)는 주문 번호만 든 조회가 결제사를 부르지 않고 null 이고, 만료 작업은 그 결제사의 `PENDING` 을 조회 없이 마감 뒤 닫는다 |
| `PaymentCheckoutGateway` | 결제창을 여는 데 프론트가 필요한 공개 값. **계약: 결제창 결제사는 서버 승인 없이 돈을 움직이지 않는다** — 모드가 다르거나 결제사 빈이 없는 `PENDING` 을 조회 없이 닫는 규칙이 이것에 기댄다. 서버 승인 없이 결제가 끝나는 결제사를 붙이려면 그 규칙을 먼저 고친다 |
| `PaymentWebhookGateway` | 웹훅의 진위를 확인하고 「어느 자원의 어떤 일」만 꺼낸다. `signed=false` 면 주소의 비밀 조각이 필수 |
| `mode` | 지금 끼운 키가 테스트 · 실결제 — 주문 줄에 박히고 배포 가드가 본다 |

결제사 모듈의 의무(확정/모름 분류 등)는 `payment-toss` 문서(결제사 모듈을 고른 프로젝트에 `docs/modules/payment-toss.md`). 결제창을 열 때 **결제사가 거래 번호를 먼저 정하는** 결제사(카카오페이 `ready`)는 `CheckoutInstruction.providerPaymentId` 로 돌려주고 장부가 주문에 박는다 - 승인 요청이 다른 번호를 들고 오면 결제사를 부르기 전에 막는다. 승인에 결제창이 돌려준 증거(`pg_token`)가 필요한 결제사는 `PaymentConfirmRequest.approvalToken` 으로 받는다. `PaymentProvider` 는 능력의 합(+`cancel`)이라 기존 구현은 고치지 않아도 된다.

## 구독 (`skeleton.payment.subscription.enabled`, 기본 꺼짐)

결제 장부 위에 얹는 자동결제 — 카드를 한 번 등록(토스 빌링키)하고 서버가 기간 끝마다 승인한다. 설계와 근거: `docs/superpowers/specs/2026-10-09-subscription-design.md`. **결제사의 스케줄러는 쓰지 않는다**(토스는 스케줄링을 하지 않는다) — 언제 얼마를 받을지는 우리 장부가 정한다.

- **결제사가 갱신하는 구독(Paddle)**: `PaymentHostedSubscriptionGateway` 를 가진 결제사는 카드 등록 · 첫 승인 · 갱신을 하지 않는다 — 서버가 결제사에 거래를 먼저 만들고(`PaymentTransactionGateway`) 결제창은 그 거래 id 만 연다. 서명된 웹훅(`PaymentPushGateway`, 수신함 줄 `resource_type=PUSHED`)이 `PaymentPushedEvents` 로 결제(주문 `PAID`, 금액 · 통화는 웹훅 값)와 구독 상태(`provider_subscription_id` · `provider_synced_at` 열, 더 새로운 사건만 반영)를 옮긴다. 그런 구독은 우리 갱신 작업이 건너뛰고(이중 과금 방지) 해지는 결제사 API(기간 끝), 중도 해지 · 플랜 변경은 열리지 않는다(2차). 결제사는 `POST /subscriptions` · `POST /orders` 의 `provider` 로 고른다.
- **결제사가 먼저 예약하는 · 등록이 곧 첫 결제인 결제사**(카카오페이 · 네이버페이 정기): `PaymentBillingGateway.prepareBilling`(트랜잭션 밖 - 결제사를 불러 창 값과 번호 `reference` 를 받는다; 번호는 구독 줄의 첫 거래 번호 칸에 박히고 `activate` 의 `issue(…, reference)` 로 돌아온다) · `registrationCharges` + `registerWithCharge`(등록이 곧 첫 결제 - 자리 + 첫 주문(번호는 미리 정해진 `PaymentOrderIds.initialOrderId`, 결제 키 = 예약 번호)을 먼저 커밋하고, 락 밖에서 **한 번** 부르고, 받았으면 **같은 트랜잭션에** 토큰(암호문) · 카드 표시를 쓴다; 토큰을 못 읽었으면 구독은 켜지되 토큰이 비고 갱신 작업이 과금 없이 `PAYMENT_FAILED` 로 끝낸다). 조회 · 환불 요청에는 `recurring`(구독 주문)이 실려 정기용 인증값으로 묻는다. 승인이 「모름」이어도 결제사가 결제 번호를 줬으면 주문의 결제 키 자리에 박힌다. 주문 번호로 조회되지 않는 결제사(`answersByOrderId=false`)의 키 없는 구독 주문은 조회가 null 이어도 거절로 반응하지 않고 `CONFIRM_UNRESOLVED` 로 사람에게 간다.
- **켜는 법**: `skeleton.payment.subscription.enabled=true` + 앱이 내는 세 가지 — `SubscriptionPlans` 빈(플랜 코드 · 금액 · 주기 · 등급 — **구독 금액의 유일한 출처**, 구독 줄에는 만들 때의 값이 박혀 카탈로그 가격을 바꿔도 기존 구독에 조용히 적용되지 않는다), 빌링 능력을 가진 결제사(`PaymentBillingGateway` — `payment-toss`), 빌링키를 암호화할 `crypto` 키(`skeleton.crypto.keys`). **하나라도 없으면 기동 실패**다.
- **상태** `PENDING`(카드 등록 · 첫 결제 전 또는 확인 중) → `ACTIVE` ⇄ `PAST_DUE`(갱신 거절, 재시도 중 — 이용 불가) · `CANCELING`(해지 예약, 기간 끝까지 이용) → `ENDED`. 전이는 `SubscriptionTransitions` 한 곳이고 표 밖 전이는 버그다. 앱은 `PaymentSubscriptions.current(accountId)` 를 **읽을 때** `status.isInUse`(`ACTIVE` · `CANCELING`)로 판단한다 — 구독 리스너는 두지 않았다.
- **계정당 살아 있는 구독 하나**: 구독 줄의 `active_key`(= 계정 id, 끝나면 NULL)가 유니크다. 자리는 카드를 낸 뒤 첫 주문과 같은 트랜잭션에서 잡는다.
- **갱신이 한 번만 과금되는 법** — 장부의 주문 · `dedupe_key` · 작업 큐를 그대로 쓴다: 운전자는 작업 하나(`payment-subscription-renew`, `runAt` = 기간 끝)이고, 작업은 **구독 줄을 잠그고 다시 읽어** 기한이 됐을 때만 과금 주문을 넣는다(`dedupe_key = sub:<구독 id>:<다음 기간>:<시도>` 유니크 — 여러 워커가 동시에 와도 한 줄). 과금은 승인과 같은 세 걸음이다: 선점(`CONFIRMING` + 조회 작업, 커밋) → 결제사 호출(락 밖) → 확정. **모름이면 다시 보내지 않는다** — 조회 작업이 주문 번호로 물어 끝낸다. 지금 시도의 주문이 이미 있으면 새 주문을 만들지 않는다. **구독의 반응은 주문이 끝 상태가 되는 그 트랜잭션에 묶여 있다**(`SubscriptionLifecycle.onOrderClosed`, 주문 락 → 구독 락): 확정 거절 · 웹훅 · 「결제사와 맞추기」가 주문을 `FAILED` 로 닫거나, 결제사가 그 결제를 끝내 모른다고 해서(승인이 닿지 않았다 — 토스는 이때 조회가 404) 조회 작업이 죽으면, 첫 결제는 구독이 끝나 자리가 풀리고 갱신은 `PAST_DUE`(해지 예약 중이면 끝) 로 간다. 결제사 · 모드는 주문을 만들기 **전에** 확인한다.
- **기간은 지급 고리에서만 넘어간다**(`PaymentFulfillment` 의 주문 줄 락 안, `fulfilled_at` 이 비었을 때 한 번): 구독의 지금 과금 주문(`charge_order_id`)인 주문만 기간 +1 · `ACTIVE`. 다른 주문이 늦게 `PAID` 가 되면 기간은 두고 그 주문에 「확인 필요」(`SUBSCRIPTION_STALE_CHARGE`)를 찍는다(운영자가 환불). 기간 끝은 **첫 결제 시각 + 주기 × n**이라 늦게 깨어나도 밀리지 않는다. 락 순서는 **주문 → 구독**.
- **실패 · 해지 · 탈퇴**: 갱신 거절은 `PAST_DUE` + `renewal.retry-interval` 뒤 다시(`renewal.max-attempts` 번, 다 쓰면 `ENDED`/`PAYMENT_FAILED`). 해지는 `ACTIVE` → `CANCELING`(기간 끝에 종료, 갱신 없음)이고 `PAST_DUE` 는 바로 끝난다. 탈퇴(`PaymentAccountErasure`)는 그 계정의 구독을 닫고 카드 표시 두 칸을 지운다. 그 기간의 주문이 **전액 환불**되면 구독을 바로 끝낸다(환불했는데 또 과금되지 않게). 해지 · 탈퇴 뒤 깨어난 갱신 작업은 결제사를 부르지 않는다.
- **정지 · 삭제 유예 계정은 갱신 과금을 막는다**: 앱 계약 `SubscriptionRenewalGuard`(기본 = 항상 허용 — `payment` 는 `account` 를 모른다)가 거절하면 그 구독은 기간 끝에 `RENEWAL_BLOCKED` 로 끝난다. 갱신 시각마다 락 밖에서 불리니 짧게 끝낸다.
- **빌링키**: `crypto` 로 암호화해 저장하고(`billing_token` 은 암호문), 응답 · 로그 · `toString` 어디에도 평문이 없다. 구독이 끝나면 `payment-subscription-revoke` 작업이 결제사에서 빌링키를 지운 **뒤에** 줄의 빌링키 · 고객 키(`customer_key`)를 비운다 — 카드 표시 두 칸(`card_issuer` · `card_last4`)은 끝나는 즉시 비워진다. 고객 키는 `cus_` + 무작위 32자 16진수(추측 가능한 값이 아니다).
- **HTTP** (`<http.base-path>` 아래, [payment-http-contract §12](../payment-http-contract.md)): `GET /subscriptions/plans` · `POST /subscriptions`(Idempotency-Key, 카드 등록 창 값) · `POST /subscriptions/{id}/activate`(빌링키 발급 → 첫 결제, `200 ACTIVE` · `202` 확인 중 · `402 DECLINED`) · `GET /subscriptions/me` · `POST /subscriptions/{id}/cancel` · `GET .../cancel-preview` · `POST .../cancel-now`(중도 해지 + 환불 - 나라별 정책 `SubscriptionCancelPolicy`, 모듈 기본은 닫힘). 남의 구독 · 없는 구독은 같은 `404`, 본문에 금액은 없다. 운영자 경로는 없다 — 구독 주문은 기존 운영자 주문 목록에 `kind` 로 보인다.
- **이메일 없는 계정**: 구독에 이메일이 필요 없다(결제사에도 안 보낸다). `email.required=true` 인 앱은 기존 계정 게이트가 구독 쓰기 경로도 막는다. 알림이 없어 실패 · 종료는 「내 구독」 화면이 유일한 통지다.
- **한계**: 아래 등급으로 변경 · 일시정지 · 무료 체험 · 쿠폰 · 구독 주문의 일할 환불 · 카드 바꾸기 · 갱신 예고 알림은 없다. 갱신 작업이 죽으면 `ACTIVE` 인 채 기간이 지나 무료 이용이 된다(돈 사고는 아니다). 카드를 내지 않은 `PENDING` 줄은 정리하지 않는다. 플랜 변경(위로만 · 남은 기간 차액)은 2차다.

## 앱 계약 — 앱이 하는 일

| 앱이 하는 일 | 계약 |
|---|---|
| 무엇을 얼마에 파는가 (**금액의 유일한 출처**) | `PaymentCatalog` — 없으면 주문 만들기 경로가 열리지 않는다(서비스는 `PAYMENT.UNAVAILABLE`). 주문 줄에 박힌 값만 결제사로 간다. 살 수 없으면 `PaymentItemNotPurchasableException`(`422`). 결제창에 보이는 `orderName` 에는 개인정보를 넣지 않는다(최대 100자) |
| 결제 확정 · 환불 확정 뒤 지급 · 회수 | `PaymentOrderListener.onPaid` · `onRefunded` — 던져도 「결제됨」은 그대로이고 지급 · 회수 작업이 재시도한다 |
| 운영자 판정 | 핸들러마다 권한 하나(`@OperatorPermission`): 읽기 `payment.read` · 환불 승인과 직접 환불 `payment.refund` · 맞추기 / 닫기 / 웹훅 다시 처리 `payment.operate`. 기본 `PaymentOperatorPolicy` 는 platform 의 `OperatorAccess` 로 판정한다(`account` 가 있으면 매 호출 저장소 — 회수가 바로 먹는다). 인터셉터와 컨트롤러가 같은 표로 두 번 확인한다. 옛 람다 정책은 `permits` 기본 구현 덕에 그대로 통한다 |
| (선택) 쿠폰 같은 할인 수단 · 결제 면제 | 할인: `platform` 의 `DiscountSource` 빈(쿠폰 모듈이 낸다)을 `PaymentDiscounts` 가 `order` 순으로 모은다 - 아래 「할인 · 0원 · 결제 면제」. 면제: 권한 `payment.exempt` 를 가진 계정 — 앱은 「구매했나 / 구독 중인가」 검사에 `|| exemption.isExempt(accountId)` 한 줄 |
| (선택) 사람이 봐야 하는 일이 줄에 찍힐 때 알림 | `PaymentReviewNotifier` — `alert` 가 있으면 `PAYMENT_REVIEW_NEEDED`(주문 id 만 싣는다), 없으면 줄 · 로그에만 남는다 |
| (선택) 차지백이 들어왔을 때 · 분쟁에서 이겨 돌아왔을 때 알림 | `PaymentChargebackAlert` — 장부 + `alert` 가 있으면 `PAYMENT_CHARGEBACK` · `PAYMENT_CHARGEBACK_REVERSED`(주문 id 만 싣는다), 없으면 줄과 이력에만 남는다 |
| (선택) 결제사가 우리 키를 받아들이지 않았을 때 알림 | `PaymentProviderCredentialAlert` — 결제사 모듈이 키 거절(토스: 401 계열 `INVALID_API_KEY` · `UNAUTHORIZED_KEY` — `FORBIDDEN_REQUEST` 는 요청 불일치일 수도 있어 알리지 않는다)을 받으면 부른다. 장부 + `alert` 가 있으면 `PAYMENT_PROVIDER_CREDENTIALS_REJECTED`(결제사 id 만 싣는다 · 한 시간에 한 번으로 접힌다), 없으면 알리지 않는다. 고객에게는 여전히 「거절」이다 |
| (선택) 결제사가 다른 주문 · 금액의 결제를 이 주문의 승인으로 받았을 때 알림 | `PaymentProviderMismatchAlert` — 승인 API 가 결제 번호만 받는 결제사(네이버페이)가 그 승인을 곧바로 취소했거나 취소하지 못했을 때 부른다. 장부 + `alert` 가 있으면 `PAYMENT_MISMATCH_CANCELLED` · `PAYMENT_MISMATCH_CANCEL_PENDING`(취소가 아직 끝나지 않음) · `PAYMENT_MISMATCH_CANCEL_FAILED`(주문 id 만 싣는다), 없으면 알리지 않는다 |
| 성공/실패 리다이렉트 화면 · 웹훅 비밀 조각 | 앱 몫 — [HTTP](#http) 참고 |

**`PaymentOrderListener` 를 짧게 쓴다.** `onPaid` · `onRefunded` 는 **주문 줄 락 안에서 한 번** 돈다 — 같은 DB 를 쓰는 지급이 정확히 한 번인 이유다. 그동안 **그 주문의 웹훅 수신 · 승인 · 환불이 기다린다**(PostgreSQL 은 락 대기에 시간 제한이 없다). 느린 일(메일 · 외부 호출)은 리스너에서 직접 하지 말고 큐로 넘긴다(알림은 `NotificationPublisher`, 외부 시스템 호출은 작업으로).
드물게 다시 불릴 수 있으니 멱등하게 쓴다. **지급한 적 없는 주문에도 `onRefunded` 가 온다**(지급 전에 전액 환불되면 `onPaid` 는 건너뛰고 `onRefunded` 만) — 지급했는지는 `order.fulfilledAt == null` 로 가른다. `onRefunded` 가 `onPaid` 보다 앞서지 않는다(일부만 환불된 미지급 주문은 먼저 `onPaid` 가 불린다).

## 할인 · 0원 · 결제 면제

**금액은 여전히 서버만 정한다**: 카탈로그 가격 → (면제 또는 할인) → 최종가. 브라우저가 보내는 것은 어떤 할인 수단의 어느 것인지(`discounts: [{type:"coupon", ref}]`)뿐이다. `payment_orders.amount` 는 **최종 금액**으로 그대로 — 승인 대조 · 환불 상한(`paidAmount ?: amount`) · 중도 해지 환불이 맞는다. 새 칸 `original_amount`(할인 전, 없으면 null) · `discount_amount` 와 할인 내역 표 `payment_order_discounts`(주문 · 순번 · 수단 · 쿠폰 id · 금액 · 상태 `RESERVED → CONFIRMED / RELEASED / RESTORED`).

- **견적** `PaymentOrders.quote` ↔ `POST /payments/quote`: 주문과 같은 계산, 아무것도 안 쥔다. **주문** `create`: 줄을 먼저 넣고(같은 키의 동시 요청이 쿠폰을 두고 다투지 않고 승자의 주문을 받는다) 같은 트랜잭션에서 `reserve(holderRef = 주문 id)`. 한 주문에 할인 하나(첫 판).
- **한살이**(할인 수단은 `reserve → confirm / release / restore`): 지급 `PaymentFulfillment.fulfil` 이 앱 리스너보다 **먼저** `confirm`(리스너가 던지면 같이 되돌려져 재시도에 한 번) · 끝 상태(`FAILED` · `EXPIRED` · `CANCELED`)로 가는 모든 전이는 `PaymentLedgerWriter.transition` 한 곳이라 같은 트랜잭션에서 `release` · 늦은 `PAID` 는 같은 `fulfil` 의 `confirm` 이 다시 잡고, 그사이 다른 주문이 쥐었으면(`CONFLICT`) 둘 다 그대로 두고 이 주문에 `DISCOUNT_CONFLICT` 검토 표시(지급은 나간다) · **전액 환불**(`RefundLines` 의 `REFUNDED` 전이)은 `restore`(지급 전이라 아직 쥐고만 있던 쿠폰은 `release`), 부분 환불은 복원 없음 · 차지백 되돌림(`REVERSED`)은 쿠폰도 되살리지 않는다.
- **0원** (`provider = "free"`): 결제사를 부르지 않고 한 트랜잭션에서 `PENDING → PAID` + 지급 작업(만료 작업 · 결제창 없음). 지급 훅은 평소처럼 돈다. 환불 대상이 아니다(`NOTHING_TO_REFUND`). 결제 건수에는 들고 매출은 0. 모드는 열 수 있는 결제사의 모드.
- **결제 면제** (`provider = "exempt"`): 권한 `payment.exempt`([PaymentPermissions.EXEMPT], 기본 역할 없음 — 전권 `*` 는 배정 없이 가진다)를 가진 계정의 단건 주문은 결제사 없이 `ORDER_CREATED → EXEMPTED(행위자 = 그 계정) → PENDING → PAID` 로 끝난다. `original_amount` = 카탈로그 가격, `amount` 0, 할인 줄 없음, 쿠폰을 같이 보내도 쥐지 않는다. 대시보드 순매출 · 결제 건수 · 퍼널 「결제」(`payment-jdbc` 의 `CurrentModeOrders` 가 `provider <> 'exempt'`), 환불, 「첫 결제만」 판정(`hasPaidOrder` 는 `free` · `exempt` 를 세지 않는다)에서 빠진다. 판정은 요청마다 `PaymentExemption`(platform `OperatorAccess` — 계정 모듈이 있으면 저장된 역할을 매번 읽어 회수가 다음 요청부터 먹고, 역할 위계가 있으면 물려받은 권한도 따른다). 앱이 면제 주문을 가르려면 `order.provider == PaymentOrders.EXEMPT_PROVIDER`(`settlement`).
- **결제사 최소 금액**: `PaymentCheckoutGateway.minimumAmount(currency)`(기본 null) — 0 < 최종가 < 최소 금액이면 `PAYMENT.DISCOUNT_BELOW_MINIMUM`. 토스: 원화 100원(토스페이먼츠 오류 메시지 「신용카드는 100원 이상」에서 확인, 공식 문서 페이지로 확정하지 못했다 — 운영 전 계약으로 확인). 카카오페이 · 네이버페이는 공식 값을 찾지 못해 null(제한 없음으로 본다).
- **지원하지 않는 길**: 금액을 결제사가 직접 정하는 경로(거래를 먼저 만드는 결제사 — Paddle)는 `PAYMENT.DISCOUNT_NOT_SUPPORTED`. 구독 할인은 아래 「구독 할인」.

## 구독 할인 (쿠폰의 단계 일정 - 첫 달 무료 · n개월 반값 · 영원히 n%)

`POST /payments/subscriptions` 에 `discounts: [{type:"coupon", ref}]`(첫 판은 한 장). 쿠폰의 **단계 목록**(`[FREE×1, PERCENT50×3, …]`)을 시작 때 구독에 **복사**한다(`payment_subscription_discounts` + `_stages` - 쿠폰 정의를 고쳐도 진행 중인 구독은 그대로, Stripe 관행). 구독의 `SubscriptionDiscounts` 가 일정을 맡는다.

- **회차 = `periods_used`**: 할인이 붙은 구독 주문(첫 결제 · 갱신)이 `PAID` 될 때만 지급 고리(`SubscriptionLifecycle.onOrderPaid`, 구독 락 안)에서 1 오른다. 그래서 같은 기간의 재시도는 같은 금액이고 실패한 시도는 회차를 쓰지 않는다. 단계를 다 쓰면 `ENDED` → 정가. 이번 과금액 = 구독 줄의 금액에 `periods_used` 위치의 단계를 적용(`SubscriptionDiscounts.amountFor`).
- **첫 결제**: 시작 때는 쿠폰을 **보기만** 한다(첫 과금액 · 단계 얻기 - 아무것도 안 쥔다). `prepareBilling` 의 금액이 첫 과금액이다. 첫 결제(`activate`) 때 첫 주문 이름(`PaymentOrderIds.initialOrderId`)으로 쿠폰을 **쥐고** 일반 주문과 같은 `payment_order_discounts` 줄을 넣는다 - 확정(지급) · 해제(첫 결제 거절) · 복원(첫 주문 전액 환불)이 단건과 같은 길이다. 그 사이 쿠폰이 팔렸거나 정의가 바뀌어 첫 과금액이 달라졌으면 `409 DISCOUNT_UNAVAILABLE` 로 거절한다(방금 발급한 빌링키는 지운다).
- **첫 기간 무료**: 카드는 그래도 등록받는다. 토스 · 네이버페이(등록이 따로)는 빌링키를 받은 뒤 결제사 승인 대신 **내부 0원 주문**(`provider = "free"`, 조회 작업 없음)을 `PAID` 로 - 지급 고리가 평소처럼 기간을 연다. 카카오페이(등록이 곧 첫 결제)는 1회차를 **0원으로** 보내 승인 없이 빌링키(SID)만 받는다(공식 문서 「total_amount 0원으로 처리하면 승인은 발생하지 않고 SID만 발급」 - https://developers.kakaopay.com/docs/payment/online/subscription); 그 주문은 카카오페이 주문으로 남는다.
- **갱신**: `SubscriptionRenewTaskHandler` 가 락 안에서 이번 과금액을 정한다. 0원(무료 기간)이면 결제사를 부르지 않고 내부 0원 주문을 `PAID` 로 - **빌링키가 없는 구독도 무료 기간은 넘어간다**. 주문에는 `originalAmount`(정가) · `discountAmount` 가 찍힌다.
- **플랜 변경**: 남은 단계는 새 플랜 금액에 이어서 적용된다(과금액이 구독 줄의 금액에서 나온다; 정액은 새 금액을 넘지 않는다). 변경 차액 결제(`SUBSCRIPTION_CHANGE`)에는 할인도 회차도 없다(차액은 일할 계산이라 두 번 깎으면 안 된다). `FULL_RESTART` 로 `periodSeq` 가 1 로 돌아가도 `periods_used` 는 따로라 이어진다.
- **끝**: 해지 예약(`CANCELING`) 중에는 단계가 보존되고, 구독이 `ENDED` 가 되면(`SubscriptionLifecycle.end`) 할인도 `ENDED` - 다시 구독하면 새 줄이고 쿠폰은 되살아나지 않는다. 환불 · 중도 해지 환불은 그 주문의 **실제 결제액** 기준이라 따로 할 일이 없다; 소진한 회차는 돌려주지 않는다. 첫 주문 전액 환불(7일 철회)은 쿠폰을 돌려준다.
- **응답**: 구독 응답에 `discount: { label, stages[{type,value,maxDiscount,periods}], periodsUsed, nextAmount, remainingPeriods|null(영원) } | null`(끝난 할인은 `null`).
- **안 받는 길**: 결제사가 갱신까지 하는 구독(Paddle 등 `isProviderManaged`)은 `DISCOUNT_NOT_SUPPORTED`; 쿠폰 범위에 `SUBSCRIPTION` · 그 플랜이 없으면 `DISCOUNT_NOT_APPLICABLE`.

## 상태 전이

전이는 한 곳(`PaymentOrderTransitions` · `PaymentRefundTransitions`)이 정한다. 표에 없는 전이는 버그이므로 `IllegalStateException` 이고 사용자에게 나가지 않는다.

**주문**: `PENDING` → `CONFIRMING` · `CANCELED` · `EXPIRED` · `PAID` / `CONFIRMING` → `PAID` · `FAILED` / `PAID` → `PARTIALLY_REFUNDED` · `REFUNDED` / `PARTIALLY_REFUNDED` → `PARTIALLY_REFUNDED` · `REFUNDED`.
`FAILED` · `EXPIRED` · `CANCELED` → `PAID` 는 있지만 **결제사 조회 증거로만**이다(늦게 온 결제). **`PAID` 이후에서 `FAILED` · `EXPIRED` · `CANCELED` 로 가는 길은 없다** — 늦게 온 실패 신호가 받은 돈을 되돌리지 못한다.

**결제사가 결정하는 환불(Paddle)**: 호출이 `pending_approval` 로 답하면 줄이 `PENDING` 으로 남고 접수 번호(`adj_…`)가 줄의 `provider_refund_id` 에 박힌다(`failure_code=PROVIDER_PENDING`). 서명된 웹훅 `adjustment.created` · `adjustment.updated` 가 `PushedRefund` 사건으로 `PaymentPushedEvents` 에 닿아 줄을 끝낸다: `approved` → `SUCCEEDED`(`onRefunded` 한 번, 전액이면 주문 `REFUNDED`), `rejected` → `FAILED` + `failure_code=PROVIDER_REJECTED`(주문은 다시 환불할 수 있다). 줄은 번호로, 번호가 없으면 같은 금액의 열린 `PENDING` 줄 하나로 찾는다(응답을 잃은 호출). 우리 줄이 없는 승인(결제사 대시보드의 환불)은 `PROVIDER` 줄로 넣는다. 끝 상태가 된 줄은 다시 움직이지 않는다 — 단 `FAILED` 줄에 같은 번호 · 같은 금액의 승인이 오면 위 증거 전이로 `SUCCEEDED` 가 된다. 정산(`RefundSettlement`)은 결제사가 `pendingRefunds` 로 쥔 줄을 「적용 안 됨」으로 닫지 않는다.

**차지백(Paddle)**: 구매자가 카드사에 이의를 제기하면 Paddle 이 adjustment(`chargeback`, 경고는 `chargeback_warning`)를 **스스로 만들고** 같은 웹훅 둘(`adjustment.created` · `adjustment.updated`)로 알린다. 승인된 것은 `PROVIDER` 환불 줄 `SUCCEEDED`(사유 `CHARGEBACK`)로 들어가 환불과 같은 길로 회수 작업 · 전액이면 구독 끝 + 결제사 해지가 돈다. 경고와 같은 금액의 차지백은 같은 이의라 한 줄이다(금액이 다르면 둘 다 넣고 `REFUND_MISMATCH` 로 사람이 본다). 분쟁에서 이기면 원래 adjustment 가 `reversed` 로 바뀌거나 되돌림 adjustment(`chargeback_reverse`)가 만들어진다 — 어느 쪽이든(둘 다 와도) 짝 줄(같은 번호, 없으면 같은 거래 · 같은 금액의 차지백 줄)이 **한 번만** `REVERSED` 가 되고 주문의 환불된 금액에서 빠져 `PARTIALLY_REFUNDED`/`PAID` 로 돌아간다. 이 전이는 주문 전이표에 없는 **결제사 증거로만** 가는 길이다(`PaymentOrderTransitions.isAllowedByProviderEvidence`). 되돌림이 차지백보다 먼저 도착하면 처리 작업이 실패로 남아 재시도하다 짝이 생기면 맞춘다(다 쓰면 수신함 `DEAD` — 운영자 「다시 처리」). 지급(이용권) · 끝난 구독은 **되살리지 않는다**(운영자가 판단한다). 분쟁에서 졌을 때 Paddle 은 따로 알리지 않는다(줄은 `SUCCEEDED` 그대로). 알림은 `PaymentChargebackAlert`(주문 id 만).

**구독 결제 환불의 「구독도 해지」**: 운영자 환불의 두 입구(`refund` · `approve`)가 `endSubscription`(생략 = `true`)을 받고, `false` 면 환불 줄에 `keep_subscription=true` 를 쓴다. 그 줄이 `SUCCEEDED` 가 돼 지금 기간의 구독 주문이 전액 환불돼도 구독을 끝내지 않는다(환불이 끝나는 때 적용되므로 줄에 남는다). 운영자가 아닌 환불(결제사 콘솔 취소)은 지금처럼 구독을 끝낸다. 결제사가 갱신하는 구독이 환불로 끝나면 결제사에도 즉시 해지 작업(`payment-provider-subscription-cancel`)을 넣는다.

**환불**: `REQUESTED` → `PENDING` · `REJECTED` · `WITHDRAWN` / `PENDING` → `SUCCEEDED` · `FAILED`. 표에 없지만 **결제사 증거로만** `FAILED` → `SUCCEEDED` 가 열려 있다(`PaymentRefundTransitions.isAllowedByProviderEvidence`): 우리가 닫은 줄(적용 안 됨 · 거절)에 같은 번호 · 같은 금액의 환불을 결제사가 이뤄졌다고 알려 오면(웹훅 승인 · 조회 맞추기 · 늦게 온 응답) 줄이 되살아나 주문에 한 번 반영된다(합계가 결제액을 넘으면 되살리지 않고 `REFUND_MISMATCH` 로 사람이 본다). 줄이 처음 생길 때의 상태는 `REQUESTED`(본인 요청) · `PENDING`(운영자 직접 환불) · `SUCCEEDED`(결제사 쪽 취소를 장부에 맞춰 넣은 줄). 주문당 열린 환불(`REQUESTED` · `PENDING`)은 하나뿐이다(`open_key` 유니크).

흐름: 주문 만들기 → (결제창) → 승인(`confirm`: 선점 → 호출 → 확정) → 지급. 승인에서 결제사의 답을 모르면 `CONFIRMING` 에 두고 **조회 작업**(`reconcile.first-delay` 뒤 첫 조회, `max-attempts` 번)이 결제사가 끝 상태를 말할 때까지 묻는다. 안 만든 결제는 **만료 작업**(`checkout-ttl` 뒤)이 결제사에 물어 닫는다. 작업 여섯 종: `payment-order-expire` · `payment-order-reconcile` · `payment-order-fulfil` · `payment-refund-reconcile` · `payment-refund-apply` · `payment-webhook`.

## 검토 이유와 누가 푸는가

「확인 필요」(`reviewReason`)는 **사람이 봐야 한다는 표시**이고 한 칸이다. 돈이 어긋난 이유가 지급 쪽 이유를 덮어쓰고 반대는 안 된다. 아홉 가지(백엔드가 찍는 일곱 + 파생 둘)와 뜻 · 푸는 길의 정본은 [계약 문서 §7](../payment-http-contract.md). 요약:

| 이유 | 푸는 길 |
|---|---|
| `AMOUNT_MISMATCH` · `ORDER_MISMATCH` | 장부에는 푸는 단추가 없다 — 사람이 결제사 쪽을 확인해 처리한다. 주문은 `PAID` 로 올라가지 않았다 |
| `REFUND_MISMATCH` | 숫자가 맞게 되면 웹훅 · 조회가 푼다. 사람이 봐야 하는 이상(풀리지 않은 `PENDING` · 다른 금액 · 늦은 성공 응답)은 사람이 누른 「결제사와 맞추기」만. 이 표시가 있으면 환불을 보내지 않는다 |
| `MODE_MISMATCH` | 키를 맞추고 「결제사와 맞추기」. **승인을 선점한 뒤(`CONFIRMING`)이거나 결제된 주문에만 찍힌다** — 선점한 적 없는 `PENDING` 은 표시 없이 마감 뒤 `EXPIRED` 로 닫힌다 |
| `CONFIRM_UNRESOLVED` | 조회 작업이 끝내 못 물었다 — 웹훅 · 「결제사와 맞추기」가 `PAID` 로 맞춘다 |
| `BUYER_COUNTRY_KR` | 해외 결제사(Paddle)로 첫 결제한 구매자의 주소 나라가 한국이다 — 막지 않았다(돈은 받았다). 장부에는 푸는 단추가 없다 |
| `FULFILMENT_UNRESOLVED` · `REFUND_APPLY_UNRESOLVED` | 지급 · 회수 작업이 죽었다 — 「결제사와 맞추기」가 작업을 다시 넣고 푼다 |
| `STALE_CONFIRMING` · `STALE_REFUND`(파생) | `admin.stale-after`(기본 1시간)보다 오래 `CONFIRMING` · `PENDING` 환불 — 기반 상태가 움직이면 사라진다 |

표시하는 고리가 같이 실패해도 놓치지 않도록, 줄에 이유가 없어도 `stale-after` 가 지난 **미지급 `PAID` 계열** · **미회수 `SUCCEEDED` 환불**은 운영자 「확인 필요」 목록에 파생 이유로 올라온다(마지막 안전망). 큐가 작업을 죽이는 모든 길에서 `payment-jdbc` 가 작업의 죽은 고리(`onDead`)를 불러 이유를 찍는다.

## `sync` — 결제사와 맞추기가 하는 일

운영자 경로의 `POST /orders/{id}/sync` 는 `PaymentRefunds.sync` 다. 순서:

1. **모드 확인** — 주문의 키 모드와 지금 결제사의 모드가 다르면 `409 MODE_MISMATCH`. `CONFIRMING` · 결제된 주문에는 `MODE_MISMATCH` 표시를 찍고(커밋), `PENDING`(선점한 적 없다 — 만료 작업이 닫는다)과 **끝난 주문**(`FAILED` · `EXPIRED` · `CANCELED`, 또는 열린 환불 줄이 없는 `REFUNDED`)에는 표시도 이력도 남기지 않고 409 만 낸다. 열린 환불 줄이 있으면 돈이 걸려 있어 끝난 주문이 아니다.
2. **조회** — 결제사에 묻는다(락 밖). 물을 수 없으면 `502 PROVIDER_ERROR`(결제사가 오류로 답함, `detail` 은 고정 문장) 또는 `503 UNAVAILABLE`(대답이 없음). **결제사가 그 결제를 모른다고 답하면**(조회 결과 없음) `404 PROVIDER_PAYMENT_NOT_FOUND` — 장부는 바뀌지 않고 `MODE_MISMATCH` 표시도 풀지 않는다.
3. **`MODE_MISMATCH` 해제** — 지금 모드가 주문과 같고 결제사가 끝 상태를 말했을 때.
4. **`converge`** — 결제사의 상태 · 취소 목록을 장부에 맞춘다(결제사 관리 화면에서 취소한 것도 환불 줄로 넣는다).
5. **`PENDING` 환불 줄 정산** — 결제사의 남은 금액이 장부와 같으면 「적용되지 않았다」(`NOT_APPLIED`)로 닫는다(단 `settle-delay` 가 스냅샷을 얻은 시각으로 지난 뒤). 결제사가 **접수했다**고 답한 줄(`PROVIDER_PENDING`)은 자동으로 닫지 않고 `refund.provider-pending-timeout`(기본 24시간)이 지난 뒤 **사람이 누른 `sync` 만** 닫는다. 요청과 다른 금액이 취소된 줄(`RESULT_MISMATCH`)도 사람이 누른 `sync` 만 닫는다.
6. **다시 `converge`.**
7. **미지급 · 미회수 작업 재투입** — 맞추기 전부터 미지급이던 `PAID` 계열 주문에 지급 작업을, 미회수 `SUCCEEDED` 환불에 회수 작업을 한 번씩 다시 넣는다(지급 전에 전액 환불된 주문은 제외).
8. **지급 쪽 이유 해제** — `FULFILMENT_UNRESOLVED` · `REFUND_APPLY_UNRESOLVED` 를 푼다. 돈 쪽 이유는 건드리지 않는다.

다시 눌러도 안전하지만, 16명이 동시에 누르면 지급 · 회수 작업이 최대 16개 들어갈 수 있다(작업은 멱등이라 적용은 한 번).
환불 전 확인(운영자 직접 환불 · 승인 앞의 조회 → 맞추기)은 **한 번 · 재시도 없음**이고 실패하면 환불을 보내지 않는다(`503 UNAVAILABLE`). 결제사가 그 결제를 모르거나 「받지 않았다」(진행 중 · 실패 · 만료)고 답해도 보내지 않는다 — `REFUND_MISMATCH` 를 찍고 `409 REFUND_NOT_ALLOWED`, 환불 줄은 만들지 않는다 — 오류 뒤 멱등 키를 바꿔 같은 요청을 다시 보내는 것은 위험하다.

## 웹훅

- **주소**: `POST <http.base-path>/webhooks/{provider}[/{token}]` (기본 `/api/v1/payments/webhooks/toss/<token>`). 서명 없는 결제사(토스의 결제 이벤트)는 **주소 끝의 비밀 조각**(`webhook.path-token`)이 진위의 전부다. 모르는 결제사 · 조각이 틀림은 같은 404, 서명이 틀리면 401, 본문 초과 413, 요청 수 초과 429 — [계약 문서 §8](../payment-http-contract.md).
- **비밀 조각**: 앱이 정한다(24자 이상, 영문 · 숫자 · `-` `.` `_` `~`, 서로 다른 글자 8개 이상). 보호 환경(stage · prod)에서 서명 없는 결제사가 있는데 비었거나 약하면 **기동 실패**, 그 밖은 경고. 환경변수 `<P>_PAYMENT_WEBHOOK_PATH_TOKEN`(운영자 값 — [deploy.md](../deploy.md) §7). 조각은 우리 로그에 남기지 않는다(요청 로그의 경로를 가린다 — 아래 [로그에 관해](#로그에-관해)).
- **수신함**: 받는 것은 **DB 쓰기 한 번**(수신함 줄 + 처리 작업, 한 트랜잭션)이고 결제사를 부르지 않는다. `(provider, event_key)` 유니크라 같은 배달이 동시에 열여섯 번 와도 줄 하나 · 작업 하나다. 모르는 주문 · 결제 이벤트가 아닌 이벤트는 저장하지 않는다.
- **재전송**: 줄이 `FAILED` · `DEAD` 일 때만 새 작업을 넣는다(`RECEIVED` 는 작업이 이미 기다리는 중, `PROCESSED` · `IGNORED` 는 끝남). **`webhook.redelivery-requeue-limit` 은 넣어 준 횟수가 아니라 그 줄의 총 전달 수(`deliveries`) 기준이다** — 첫 전달도 센다. 이번 전달을 센 `deliveries` 가 이 값 **이하**일 때만 새 작업을 넣고, 넘으면 받은 것으로만 답한다(기본 10이면 총 전달 10번째까지; 1 이하면 재전송은 작업을 넣지 않는다). 운영자의 「다시 처리」는 이 값과 무관하다.
- **주문 단위 창 상한**: 새 이벤트 키는 그 주문에 `webhook.per-order-limit.window` 안에 저장된 줄이 `capacity` 에 닿으면 저장하지 않고 `200` 으로만 답한다(전달 id 를 호출자가 정하므로 줄 · 작업 · 결제사 조회를 늘리지 못하게 하는 제동). 세기와 넣기는 주문 줄 락 안이라 여러 대에서도 같다(동시 요청 수만큼은 넘을 수 있다 — 폭주 제동이지 정확한 계량이 아니다).
- **표식 줄**: 상한에 걸린 신호는 버리지 않는다 — 주문당 창마다 **표식 줄 하나**(`resourceType=LIMIT_MARKER`, `eventType=LIMIT_REACHED`, DB 의 `event_key = limited:<주문 id>:<창 번호>`)와 **창 끝에 도는 처리 작업**이 걸린다. 그 작업이 결제사에 조회해 맞추므로 상한에 걸린 진짜 신호(예: 결제사 콘솔의 취소)도 창이 끝나면 장부에 닿는다. **`per-order-limit.window` 의 길이가 미룬 조회의 최대 지연이다.** 표식 줄은 상한 계산에 세지 않고 운영자 수신함 목록에 보통 줄과 같이 나온다.
- **처리 작업**(`payment-webhook`): 줄이 가리키는 주문을 **결제사에 조회**해 `converge` 한다(행위자 `PROVIDER`). 조회를 시작한 시각을 `snapshotAt` 으로 넘겨 낡은 스냅샷이 거짓 `REFUND_MISMATCH` 를 찍지 않게 한다. 웹훅의 맞추기는 `PENDING` 환불 줄을 `NOT_APPLIED` 로 닫지 않는다(그것은 환불 정산의 일). 실패 사유는 우리 코드만 `last_error` 에 쓴다. 모드가 다르면 조회 없이 줄을 `DEAD` 로 한다. 줄이 `DEAD` 가 된 뒤에는 결제사의 재전송이나 운영자의 「다시 처리」가 새 작업을 넣는다.
- **즉시 반영**(`webhook.inline.enabled`, 기본 꺼짐): 수신 트랜잭션(줄 + 작업)이 **커밋된 뒤** 같은 요청 안에서 그 줄의 `payment-webhook` 작업을 큐에서 **임대해** 한 번 돌린다(`PaymentTasksNow`, `payment-jdbc` 가 낸다). 일꾼이 같은 작업을 동시에 집을 수 없고(`SKIP LOCKED` 임대), 인라인이 끝낸 작업은 일꾼이 다시 돌리지 않는다 - 처리기 자신도 몇 번 돌아도 같은 결과(멱등). 요청은 `time-budget` 까지만 기다리고 넘으면 바로 `200`(처리는 별도 스레드에서 끝까지 이어진다; 스레드 8개가 모두 바쁘면 시도하지 않는다). 실패하면 작업은 큐의 재시도 · 백오프에 남아 지금처럼 일꾼이 맡는다. 대기 시각이 있는 작업(주문 단위 상한의 표식)과 운영자의 다시 처리는 대상이 아니다. 켜면 결제사 조회가 요청 스레드 대신 별도 스레드에서 돈다. **요청 구동 모드(`job-queue.mode=on-request`)에서는 무시된다**(기동 경고): 한도를 넘긴 시도는 응답 뒤 CPU 를 못 받거나 인스턴스와 함께 죽어 임대가 `stale-lock-timeout`(10분) 동안 남고, 그 사이 깨우기 호출이 그 웹훅을 못 집어 끄는 것보다 느려진다. 그 모드에서는 다음 깨우기가 바로 집는다.
- 웹훅 경로는 `webhook.enabled` 로만 닫는다 — `http.enabled=false` 는 사용자 경로만 닫는다.

## HTTP

장부가 있고 서블릿 웹 앱이며 Spring Security 가 있을 때만 선다. 컨트롤러는 HTTP 변환만 하고 `@Transactional` 이 없다. 호출자는 `Authentication.name`(auth 모듈에서는 계정 id), 익명은 401.

| 경로 | 켜는 조건 |
|---|---|
| 사용자 `/api/v1/payments` — `config` · 주문 읽기 · 목록 · 승인 · 그만둠 · 환불 요청 · 요청 거둠 | `http.enabled`(기본 켜짐) |
| 주문 만들기 `POST /api/v1/payments/orders` | 위 + **앱이 `PaymentCatalog` 빈을 둘 때만**. 계정 · IP 별 요청 수 한도(`http.order-rate-limit`) |
| 운영자 `/api/v1/admin/payments` — 주문 목록 · 한 건 · 직접 환불 · 환불 요청 목록 · 승인 · 거절 · 결제사와 맞추기 · 웹훅 수신함 · 다시 처리 | `admin.enabled=true`(**기본 꺼짐 — 경로가 없어 404**). 켜지면 핸들러의 권한(`payment.read` · `payment.refund` · `payment.operate`)을 매 호출 확인(거절 403) |
| 웹훅 `POST /api/v1/payments/webhooks/{provider}[/{token}]` | `webhook.enabled`(기본 켜짐). 공개 경로 — 공개 OpenAPI 문서에서 숨김 |

**`WebMvcConfigurer` 를 읽지 않는 조립에서는 운영자 경로가 닫힌 채 뜬다.** 운영자 접근 규칙(`PaymentOperatorInterceptor`)은 `WebMvcConfigurer` 로 걸린다. `WebMvcConfigurationSupport` 를 직접 상속하는 앱처럼 설정자를 읽지 않는 조립에서 `admin.enabled=true` 로 켜면 인터셉터가 안 걸려, 컨트롤러의 둘째 잠금이 **운영자를 포함한 모든 호출을 `403`** 으로 막는다(데이터는 나가지 않는다). 열려면 앱이 `PaymentOperatorInterceptor` 를 직접 등록해야 한다(`admin.enabled=true` 이면 같은 이름의 빈이 이미 있으니 자기 설정의 `addInterceptors` 에서 그 빈을 걸면 된다).

본인은 환불을 **요청만** 한다. 돈을 움직이는 환불 · 승인은 운영자 경로뿐이다. 정확한 응답 모양 · 상태 코드 · 오류 코드 · 프론트가 맞출 것은 **[docs/payment-http-contract.md](../payment-http-contract.md)**.

### 앱이 직접 쓰는 것
주문 만들기 · 승인 · 환불 요청 · 운영자 경로는 모듈이 연다. 앱이 쓰는 것은 `PaymentCatalog` · `PaymentOrderListener` · (선택) `PaymentOperatorPolicy` 빈, 웹훅 비밀 조각, 결제 성공/실패 리다이렉트 화면(`successUrl` · `failUrl` 은 프론트가 정해 결제창에 넘기고 서버는 받지 않는다)이다.

### HTTP 엔드포인트 — 세 조건이 이 설계로 채워졌다
예전에는 이 모듈이 `POST /api/v1/payments/confirm` 을 열지 않았다. `PaymentService.confirm` 은 금액을 **그대로** 결제사에 넘기고, 브라우저가 돌려보내는 `amount` 는 주소에서 온 값이라 믿을 수 없으며, 맞는 금액은 앱의 주문 저장소에만 있었기 때문이다. 그래서 먼저 계약이어야 했던 세 가지가 이 설계로 채워졌다:

1. **금액의 출처** — 앱이 구현하는 `PaymentCatalog`. 금액은 서버가 정하고 주문 줄에 박는다. 승인 요청의 `amount` 는 비교만 하고(달라도 결제사를 부르지 않는다 — `400 AMOUNT_MISMATCH`) 카탈로그 빈이 없으면 주문 만들기 경로가 열리지 않는다.
2. **결과를 앱의 주문 상태에 반영하는 훅** — `PaymentOrderListener`(주문 줄 락 안에서 한 번, 지급이 끝났는지는 `fulfilled_at`). 트랜잭션 경계는 장부의 것이다.
3. **`Idempotency-Key` 와 웹훅** — 주문 만들기 · 환불은 호출자 + 키로 중복을 막고(줄의 유니크 열), 웹훅은 수신함 + 서명 없는 결제사는 주소의 비밀 조각으로 진위를 지킨다.

장부가 없는 조립(결제사 모듈만 쓰는 앱)에서는 지금도 `PaymentService.confirm` 이 금액을 그대로 넘기므로 **HTTP 가 열리지 않는다** — 그 경우 서버의 주문 금액과 비교하는 코드가 앱에 있어야 한다.

## 탈퇴 · 익명화 · 보존

법률 자문이 아니다. 근거 조문과 기간은 설계 문서 §8.1 에 있고 판단은 앱을 운영하는 쪽의 몫이다. 이 모듈은 `account` 를 모르고 플랫폼의 `AccountErasureListener` · `AccountDataExporter` 만 구현한다(아무도 부르지 않으면 아무 일도 일어나지 않는다).

- **주문 줄은 남는다.**(전자상거래법: 대금결제 기록의 보존 의무) 계정이 지워질 때 하는 일(`PaymentAccountErasure`, 멱등 — 두 번째 호출은 아무것도 바꾸지 않는다):
  1. 결제하지 않은 `PENDING` 주문은 `CANCELED` 로 닫는다(결제할 사람이 없다). 주문마다 잠가서 id 오름차순으로. `CONFIRMING` 은 건드리지 않는다 — 조회 작업이 끝낸다.
  2. 그 계정의 **모든** 주문 줄에서 `card_issuer` · `card_last4` 를 NULL 로, `account_erased_at` 을 찍는다(한 문장 update). 이 시각이 찍힌 줄은 나중에 `PAID` 가 돼도 카드 두 칸을 다시 쓰지 않는다 — 지운 개인정보가 되살아나지 않는다. **탈퇴 때 지우는 것은 이 두 칸뿐**이라 보존 의무와 익명화가 부딪히지 않는다.
  3. `erasure.account-id` 가 `KEEP`(기본)이면 계정 id 를 그대로 둔다 — 계정 모듈의 기본(`ANONYMIZE`)이 계정 id 를 남기므로 결제 줄의 계정 id 는 그 익명 줄을 가리킬 뿐이다. `TOMBSTONE` 이면 **그 계정이 산 주문의 줄에서만** 계정 id(주문의 `account_id` · 그 주문의 환불 줄 `requested_by` · `decided_by` · 이력 `actor_id`)를 탈퇴 표식으로 바꾼다. **운영자로서 남의 주문에 남긴 id 는 남는다**(누가 환불을 결정했는가의 기록). `KEEP` → `TOMBSTONE` 은 나중에 할 수 있지만 반대는 못 한다.
  4. 열린 환불 요청(`REQUESTED`)은 남는다 — 운영자가 처리할 수 있다. 환불은 원래 결제 수단으로 돌아가므로 계정이 없어도 된다.
- **고리의 순서**: 계정 모듈은 탈퇴 고리를 `@Order` / `Ordered` 순으로 부른다. `PaymentAccountErasure` 는 `Ordered` 로 **앞에**(`ORDER` = 0) 서고, 순서를 말하지 않은 고리(기본 가장 뒤)는 모두 그 뒤에 돈다. 앱이 자기 표(예: 응원 기록)를 지우는 고리는 이 뒤여야 한다 — 이 고리가 `account_erased_at` 을 먼저 찍어야, 그 사이 `PAID` 가 되는 주문의 훅이 방금 지운 표에 줄을 남기지 않는다.
- **지급 · 회수 리스너(`PaymentOrderListener`)는 `order.accountErasedAt` 을 본다.** 탈퇴한 계정의 주문이 뒤늦게 `PAID` 가 될 수 있다(탈퇴 때 `CONFIRMING` 이던 주문을 조회 작업이 나중에 끝내는 경우) — 그 주문은 카드 두 칸이 비어 있고 `accountErasedAt` 이 찍혀 있다. 지급 쪽 앱은 지워진 계정에 지급할지 말지를 이 칸으로 정한다.
- **운영자 목록은 탈퇴한 계정의 줄을 기본으로 숨긴다**(`includeErased=true` · `q` · `review=true` 에서는 보인다). 탈퇴한 사람의 결제 내역은 사용자 경로로는 아무도 볼 수 없다(로그인할 계정이 없다).
- **내보내기**: 구역 `payments` — 내 결제와 환불 줄을 사용자 응답과 같은 모양으로(카드 표시 포함 — 지워지기 전의 본인 데이터다). 결제하지 않은 시도는 빠진다. 모으는 엔드포인트는 앱의 몫이다.
- **보존 기간이 지난 줄 지우기는 만들지 않는다**(비목표). 기간은 앱의 정책이고 `account_erased_at` · `paid_at` 이 있어 나중에 실행기를 더해도 표를 고칠 필요가 없다.
- **처리방침에 적을 것**은 앱의 몫이다 — [accounts.md](../accounts.md) 「처리방침에 적을 것」.

## 설정

접두사 `skeleton.payment` — 복사해 쓰는 블록은 [payment.yml](../config/modules/payment.yml). 환경변수는 키의 대문자(예 `skeleton.payment.admin.enabled` → `<P>_PAYMENT_ADMIN_ENABLED`).

| 키 | 기본 | 뜻 |
|---|---|---|
| `default-provider` | — | 경로가 안 맞을 때 쓸 결제사 id (결제사가 하나뿐이면 그것) |
| `routing.country-providers` | — | 접속 나라(ISO-3166 두 글자 대문자) → 결제사 id. 예 `KR: toss`. 지도에 있는 나라는 **그 결제사만**(못 열면 `503 UNAVAILABLE` — 다른 결제사로 넘기지 않는다). 지도에 없는 나라는 `default-provider`, 열려 있지 않으면 열린 결제사가 하나뿐일 때 그것. 화면이 보낸 `provider` 는 읽지 않는다 |
| `routing.dev-country-switch` | `false` | **로컬 개발 전용** — 켜면 요청의 `X-Dev-Country: US` 헤더가 그 요청의 나라가 된다(재기동 없이 토스 ↔ Paddle). 꺼져 있으면 헤더는 완전히 무시된다. 보호 환경에서 켜져 있으면 기동 실패. `GET /config` 의 `devCountrySwitch` 로 알린다 |
| `routing.dev-country` | — | **로컬 개발 전용** — 정하면 모든 요청이 이 나라다(접속 나라 무시). 보호 환경(stage · prod)에서 정해져 있으면 기동 실패 |
| `providers.<id>.{enabled, currencies, countries}` | — | 결제사 id 별로 라우팅에서 빼거나 통화 · 국가를 좁힌다 |
| `checkout-ttl` | `30m` | 만든 주문으로 결제할 수 있는 시간. 지나면 만료 작업이 결제사에 묻고 닫는다. 양수 |
| `reconcile.first-delay` | `60s` | 승인 선점 뒤 첫 조회까지. **결제사 응답 제한 시간보다 길어야** 한다(`payment-toss` 가드가 어기면 기동 실패) |
| `reconcile.max-attempts` | `12` | 조회 작업의 시도 수 (큐 기본 백오프로 약 4시간). 1 이상 |
| `refund.requests-enabled` | `true` | 본인이 환불 **요청**을 만들 수 있다 (돈은 운영자가 움직인다) |
| `refund.settle-delay` | `2m` | 환불 선점 뒤 첫 조회까지. 결제사 응답 제한보다 길어야 한다(어기면 기동 실패 — 「적용 안 됨」으로 닫은 뒤 앞선 요청이 늦게 적용되는 경우를 줄인다) |
| `refund.provider-pending-timeout` | `24h` | 결제사가 **접수했다**고 답했는데 조회에 안 보이는 환불 줄을, 이 시간 뒤 사람이 누른 「결제사와 맞추기」가 `NOT_APPLIED` 로 닫을 수 있다 |
| `webhook.enabled` | `true` | 웹훅 경로를 연다 (`PaymentWebhookGateway` 빈이 있을 때만 뜻이 있다). 수신함 서비스 · 처리 작업은 이 값과 무관하게 선다 |
| `webhook.path-token` | `""` | 주소 끝의 비밀 조각. 서명 없는 결제사는 이 값이 맞아야 닿는다(비면 늘 404). 보호 환경에서 비었거나 약하면 기동 실패. 비밀이라 `toString` 이 찍지 않는다 |
| `webhook.max-body-bytes` | `65536` | 본문 크기 제한. 넘으면 413. 1 ~ 1048576 |
| `webhook.rate-limit.capacity` · `.window` | `120` · `1m` | 호출자 IP 마다 요청 수 — 주소를 확인하기 전에 센다 |
| `webhook.per-order-limit.capacity` · `.window` | `30` · `10m` | 주문마다 새 이벤트 키의 창 상한. **`window` 가 미룬 조회의 최대 지연** |
| `webhook.inline.enabled` · `.time-budget` | `false` · `3s` | 수신 커밋 뒤 같은 요청 안에서 처리 작업을 한 번 바로 돌려 본다 - 아래 「즉시 반영」 |
| `webhook.redelivery-requeue-limit` | `10` | `FAILED` · `DEAD` 줄의 재전송이 새 작업을 넣는 상한 — **그 줄의 총 전달 수(`deliveries`) 기준**. 0 이상 |
| `http.enabled` | `true` | 사용자 경로를 연다 (웹훅 경로는 무관) |
| `http.base-path` | `/api/v1/payments` | 사용자 · 웹훅 경로의 기준. `/` 로 시작하고 `/` 로 끝나지 않는다 |
| `http.order-rate-limit.account-capacity` · `.ip-capacity` · `.window` | `10` · `30` · `10m` | 주문 만들기 요청 수 — 계정마다 · IP 마다. 넘으면 429 |
| `admin.enabled` | `false` | 운영자 경로를 연다. 꺼져 있으면 404 |
| `admin.base-path` | `/api/v1/admin/payments` | 운영자 경로의 기준 |
| `admin.stale-after` | `1h` | 「오래됨」의 기준 — 파생 검토 이유(`STALE_CONFIRMING` · `STALE_REFUND` · 지급 · 회수 안전망) |
| `erasure.account-id` | `KEEP` | `KEEP`: 탈퇴해도 결제 줄의 계정 id 를 그대로 / `TOMBSTONE`: 그 계정이 산 주문의 줄에서만 바꾼다 |

작업의 폴링 · 백오프 · 워커는 `skeleton.job-queue` 가 정한다(`payment-jdbc` 는 설정 접두사가 없다). 저장소 어댑터가 구현할 약속은 시험 소스 `PaymentLedgerStoreContract` 에 있다(`testFixtures`: 메모리 저장소 · `ScriptedGateway` 대역도 거기 — 제품 코드가 아니다).

## 여러 인스턴스 · 시계

- **정합은 프로세스 안 상태에 기대지 않는다.** 보장의 근거는 DB 의 락과 유니크다: 주문 줄 `select … for update` 선점(승인 · 만료 · 환불 · 지급 · 회수 · 웹훅 처리의 모든 전이가 락 뒤에 다시 읽고 판단) · 유니크(주문 · 환불의 `dedupe_key`, `(provider, provider_payment_id)`, 열린 환불 `open_key`, `(order_id, provider_refund_id)`, 웹훅 `(provider, event_key)`) · 작업 큐의 `SKIP LOCKED` 분배 · 웹훅 주문 단위 상한은 표의 줄 수로 센다. 멱등 키는 메모리 저장소가 아니라 줄의 유니크 열이다. 같은 DB 를 보는 독립된 백엔드 여럿으로 재는 시험은 `payment-jdbc`.
- **워커 전용(웹 없는) 인스턴스도** 서명 없는 결제사가 있는데 웹훅 토큰이 비었거나 약하면 prod 에서 **기동 실패**한다 — 웹 경로가 없어도 배포 가드는 장부 조립에 붙어 있다(모든 인스턴스가 같은 값을 받아야 한다).
- **시계/NTP 가 운영 전제다.** 마감(`checkout-ttl`) · `settle-delay` · `provider-pending-timeout` 은 **판정하는 인스턴스의 시계**로 잰다. 1분 빠른 인스턴스는 다른 대가 아직 받아 주는 승인을 거절하고 만료 · 정산 대기를 일찍 끝낼 수 있다(환불을 일찍 `NOT_APPLIED` 로 닫는 것 포함) — 끝 상태가 둘이 되지는 않고 늦게 적용된 환불은 맞추기가 잡지만, 인스턴스 시계는 NTP 로 맞춘다.
- **요청 수 한도의 저장소는 플랫폼의 `RateLimitStore`** 다 — `redis-rate-limit` 이 있으면 Redis 로 인스턴스끼리 공유한다. **없으면 인스턴스별 메모리라 n 대면 한도가 n 배**다(정합과는 무관하고 폭주 제동의 정밀도만). 웹훅 요청 수 한도와 주문 만들기 한도가 이 저장소를 쓴다.
- 클라이언트 IP 규칙(`skeleton.web.client-ip.mode` — 홈서버 플랫폼이 넣는다, [client-ip.md](../client-ip.md))을 배포에서 정한다 — 한도의 키가 호출자 IP 이기 때문이다.

## 로그에 관해

- **웹훅 주소의 비밀 조각**은 요청 로그의 경로에서 가려진다. 이를 위해 `payment` 웹훅이 켜진 앱은 **모든 요청의 경로가 요청 로그에서 512자로 잘린다**(가리기 전에 자른다 — 인증 · 한도보다 앞에서 아무 호출자의 경로가 가림 규칙에 태워지므로 입력을 묶는다).
- 새 `LogMaskingContributor` 를 쓸 때는 정규식이 입력 길이에 제곱으로 느려지지 않게 쓴다 — **반복은 유한하게, 앞에 고정 글자를 둔다**(`LogMasker` 가 규칙을 요청 경로에도 태운다).
- **logback 가림 조각을 넣지 않은 앱**에서 `org.springframework.web` 을 DEBUG 로 올리거나 `spring.mvc.log-resolved-exception` 을 켜면 **웹훅 주소의 토큰이 평문으로 로그에 남는다**. 어떤 앱도 기본으로는 그 조각을 넣지 않는다 — 켜지 말거나 조각을 넣는다.

## 변이 확인 기록

시험이 정말 보장을 지키는지 — 보장을 깨는 **변이**(한 줄을 지우거나 바꾸는 것)를 손으로 넣어 시험이 빨개지는지 본 기록이다. 변이는 확인 뒤 되돌렸고 커밋에 남지 않았다. 시험은 가짜 결제사(`ScriptedGateway` · 가짜 토스 HTTP 서버)로 돌고, 진짜 토스로 잰 것은 [real-provider-setup §11.6](../real-provider-setup.md) 에 있다.

### 스펙이 꼽은 네 가지 — 2026-10-09 새로 돌렸다 (바뀐 범위의 시험 클래스만)

| 보장 (스펙 §3) | 변이 | 빨개진 시험 |
|---|---|---|
| 3 금액 변조 — 요청의 `amount` 는 비교만 하고 다르면 결제사를 부르지 않는다 | `PaymentOrders` 승인의 금액 비교 한 줄(`!amount.sameAs(order.amount)` → 던지기)을 지움 | `OrderConfirmTest`: `a tampered amount never reaches the provider` |
| 12 환불 타임아웃 → 이중 환불 — 모름이면 다시 보내지 않고 조회해서 맞춘다 | 환불 정산 작업(`RefundReconcileTaskHandler`)이 조회 전에 같은 멱등 키로 환불을 다시 보내게 함 | `RefundReconcileTest` 4개: `a refund that timed out but was applied is settled once` · `…was not applied is failed and the order is refundable again` · `a console cancel that lands between the send and the settle…` · `the settle job does not run before the settle delay…` |
| 7 웹훅 위조 — 본문의 상태를 쓰지 않고 결제사에 조회한다 | 웹훅 처리 작업이 조회하지 않고 "결제됨" 이라는 결과를 지어내게 함 (웹훅 봉투에 상태가 없어 「본문을 믿는다」를 이렇게 흉내 냈다) | `WebhookInboxTest` 여러 개: `a forged DONE for an unpaid order changes nothing` 포함 |
| 6 지급 한 번 — 지급은 주문 줄 락 안에서 `fulfilled_at` 이 비었을 때만 | `PaymentFulfillment` 의 `fulfilledAt != null` 조건을 지움 | `FulfillmentTest`: `two workers fulfil one order exactly once` |

### 앞 작업들이 이미 한 것 — 진행 문서 · 커밋 메시지에서 모았다

(시험 이름이 기록에 없으면 비워 둔다.)

| 언제 | 변이 | 결과 |
|---|---|---|
| B3 수정 1회차 | 변이 2건 | 시험이 빨개짐 (내용은 기록에 없다) |
| B4 수정 2회차 (`806787e`) | 환불 정산 작업 `onDead` 의 락 안 다시 읽기를 뺌 | 그 시험이 빨개짐 |
| B4 수정 2회차 (`3ebb2928`) | 카드 번호가 `failure_code` 로 새게 함 | 옛 칸 목록은 초록으로 남고 새 목록(`PaymentPlantedValuesDbTest`)이 빨개짐 — 두 방언 |
| B5 수정 1회차 | 승인의 락 안 재검사를 뺌 · 새 시험 24개 중 18개를 빨강으로 확인 | 빨개짐 (나머지 6개의 빨강 확인은 기록에 없다) |
| B5 수정 2회차 (`9f529d5`) | 한 줄 변이 10가지 | 각각 시험을 하나 이상 빨갛게 함 |
| B6 수정 3회차 (`9f876b9`) | 로그 가림 정규식을 옛것으로 되돌림 | 입력을 두 배로 했을 때의 증가율 시험이 빨개짐 |
| B7 수정 1회차 | 12가지 | 빨개짐 (내용은 기록에 없다) |
| B8 수정 1회차 (`c5974d9`) | 운영자 인터셉터가 `/sync` 를 건너뛰게 함 | 샘플의 HTTP 표면 훑기(`CheerHttpSurfaceIntegrationTest`)의 403 훑기가 빨개짐 |
| B8 수정 2회차 (`b61fd82`, `7ec3fa4`) | 미지급 질의의 `limit` 를 CTE 안으로 옮김 | 저장소 계약의 미지급 시험(`PaymentLedgerStoreContract`)이 빨개짐 — PostgreSQL |
| B10 (`96a1198`) | 주문 줄 락(`lockOrder` 의 `for update`)을 뺌 | 여러 대 시험의 시나리오 2 · 3 · 4 · 5 · 9 가 빨개짐. 1 · 6 · 8 · 10 · 11 은 유니크 제약 · `SKIP LOCKED` 가 받쳐서 초록으로 남는다 — 통과가 DB 락 덕분이라는 증거 |
| B9 (`PaymentAdminContractHttpTest`) | 운영자 응답 DTO 에서 사용자 DTO 의 칸 하나를 뺌 | `PaymentAdminContractHttpTest` 의 운영자 칸 순서 시험이 빨개짐 |

**안 한 것**: 위 표 밖의 §3 보장(1 · 2 · 4 · 5 · 8~11 · 13~20)에 대해서는 이번에 새 변이를 돌리지 않았다. 앞 작업의 변이 가운데 내용이 기록에 남지 않은 것(B3 둘 · B7 열둘)은 다시 재현하지 않았다.

## 알려진 한계

- **「확인 필요」에 영구히 남는 줄 둘, 지금 출구는 DB 직접 수정뿐이다.** ① 승인을 선점한 뒤(`CONFIRMING`)에 모드가 바뀐 주문 — `MODE_MISMATCH` 가 찍힌 채 키를 되돌리기 전에는 조회할 수 없고, 되돌려도 결제사가 그 결제를 모르면(테스트 키로 낸 결제는 실결제 키로 보이지 않는다) 풀리지 않는다. ② 결제사가 그 결제를 모른다고 답하는 주문(`404 PROVIDER_PAYMENT_NOT_FOUND`) — 장부는 바뀌지 않고 표시도 풀리지 않는다. 운영자가 「확인함」으로 닫는 동작은 아직 없다(주인 결정 대기).
- 선점한 적 없는 `PENDING` 은 이 한계에 들지 않는다: 모드가 바뀌어도(`sync` · 웹훅도 표시를 찍지 않는다) · 결제사 빈이 없거나 꺼져 있어도 마감 뒤 만료 작업이 표시 · 경보 없이 `EXPIRED` 로 닫는다(닫을 때 그 줄에 남은 `MODE_MISMATCH` 는 비운다). 마감 전이면 재시도한다. 예외 하나: 결제사가 「받았다」고 답한 적이 있는 `PENDING`(`AMOUNT_MISMATCH` · `ORDER_MISMATCH`)은 조회 없는 길에서는 닫지 않고 작업이 죽는다 — 사람이 본다(같은 모드 경로에서 결제사가 이번에는 「결제 없음 · 실패 · 만료」로 답하면 표시를 단 채 `EXPIRED` 로 닫힌다 — 표시가 남아 안전하다). 이 규칙은 결제창 결제사가 서버 승인 없이 돈을 움직이지 않는다는 계약(`PaymentCheckoutGateway`)에 기댄다.
- **전환 뒤 옛 `PAID` 주문에 `sync` 를 누르면 `MODE_MISMATCH` 가 영구히 남는다** — 돈이 걸린 주문이라 표시하고, 키를 되돌리고 결제사가 그 결제를 알아야 풀린다(테스트 키로 낸 결제는 실결제 키로 보이지 않는다). 위 ①과 같은 모양이다.
- **결제사가 계속 「받지 않았다」고 답하는 `PAID` 주문은 `REFUND_MISMATCH` 가 남는다.** 이때 `sync` 는 404 가 아니라 **200** 이라(결제사가 진행 중 · 실패 · 만료로 답했으므로 「모른다」가 아니다) 화면이 이유를 말해 줄 코드가 없다. 환불 쪽 `409 REFUND_NOT_ALLOWED` 의 「먼저 결제사와 맞추기」 안내가 이 경우에는 풀지 못한다 — 지금 출구는 결제사 쪽을 확인하는 것뿐이다.

## 지원 안 하는 것

- 구독 · 정기 결제 관리(자리만 있다 — `kind` 의 `SUBSCRIPTION_*`).
- 해외 결제사 · 다중 통화 · 세금 계산(통화는 최소 단위 정수로만).
- 결제 수단 저장(빌링키) · 구매자 정보 전달.
- 보존 기간이 지난 줄을 지우는 실행기.
- 물리적으로 따로 보존하는 표(탈퇴한 소비자의 기록은 `account_erased_at` 으로 구분할 뿐이다).
- 장부 없는 조립에서의 HTTP (위 「세 조건」).

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
