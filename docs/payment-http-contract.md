# 결제 장부 — HTTP 계약 (kotlin-skeleton 백엔드)

대상: 프론트(react-skeleton `@skeleton/payment`, 운영자 하위 경로 `@skeleton/payment/admin`)와 결제사 설정을 하는 사람. 백엔드 모듈: `payment` + `payment-jdbc`(장부가 있어야 경로가 열린다) + 결제사 모듈(`payment-toss`).
배경과 근거: `docs/superpowers/specs/2026-10-08-payment-ledger-design.md` §6 · §7 · 모듈 설명 [modules/payment.md](modules/payment.md). 응답 봉투 · 오류 본문 · 토큰 규칙은 [account-http-contract.md](account-http-contract.md) §0 과 같다.
**이 문서가 프론트와 맞춘 정본이다.** 코드와 어긋나면 `PaymentUserHttpTest` · `PaymentAdminHttpTest` · `PaymentWebhookWebTest` 가 이 문서가 아니라 코드의 증거이므로 먼저 이 문서를 고친다.

## 0. 규약

- **기준 경로** 셋 (모두 설정으로 바꿀 수 있고 프론트는 기본값을 쓴다):
  - 사용자 `/api/v1/payments` (`skeleton.payment.http.base-path`) — `skeleton.payment.http.enabled=false` 면 사용자 경로만 없어진다.
  - 운영자 `/api/v1/admin/payments` (`skeleton.payment.admin.base-path`) — **`skeleton.payment.admin.enabled=true` 일 때만** 선다(기본 꺼짐: 경로가 없어 `404 COMMON.NOT_FOUND`). 켜져 있으면 매 호출 핸들러의 권한(`payment.read` · `payment.refund` · `payment.operate`, §3 표)을 확인하고 거절은 `403 COMMON.FORBIDDEN`.
  - 웹훅 `POST /api/v1/payments/webhooks/{provider}` · `…/{provider}/{token}` (사용자 기준 경로 아래) — 결제사가 부르는 **공개** 경로이고 `skeleton.payment.webhook.enabled` 로만 닫는다(사용자 경로를 꺼도 이미 일어난 결제의 신호는 받아야 하므로 `http.enabled` 와 무관). 공개 OpenAPI 문서에서는 숨긴다. §8.
- **장부(`payment-jdbc`)가 없으면 아무 경로도 열리지 않는다.** 주문 만들기(`POST /orders`)는 **앱이 `PaymentCatalog` 빈을 둘 때만** 있다 — 금액의 출처가 없으면 경로가 없다.
- 사용자 경로는 **로그인 필요**(`Authentication.name` = 계정 id). 익명은 `401 COMMON.UNAUTHORIZED`. 인증 자체는 앱의 보안 설정이 건다.
- **봉투**: 단건 `{ "value": …, "meta": … }`, 목록 `{ "values": […], "cursor": { "nextCursor": "…" | null, "hasNext": true | false }, "meta": … }`. 오류 `{ code, title, status, detail?, traceId, spanId, timestamp, errors?, data? }` — 분기는 `code` 로만 하고 `detail` 은 읽지 않는다.
- **커서는 불투명한 글자**다(읽지 말고 그대로 되돌려 보낸다). 다음 쪽 = 응답의 `cursor.nextCursor` 를 `?cursor=` 로. 다른 목록의 커서나 깨진 값은 `400 COMMON.VALIDATION_FAILED`(field `cursor`). `size` 는 기본 20, 1~100 밖은 **오류가 아니라 맞춰진다**. 모든 목록은 최신순.
- 시각은 ISO-8601 UTC(`…Z`), 금액은 `{ "amount": 3000, "currency": "KRW" }`(통화 **최소 단위의 정수**).
- **`Idempotency-Key` 헤더**(주문 만들기 · 환불 요청 · 운영자 직접 환불): 필수, 출력 가능한 ASCII 1~200자(UUID 로 좁히지 않는다). 없거나 어긋나면 `400 COMMON.VALIDATION_FAILED`(field `Idempotency-Key`). **키의 범위는 호출자 + 키**다(남의 키와 겹치지 않는다).
- 요청 본문의 숫자 칸은 **정수 JSON 숫자만** 받는다(`"3000"` · `30.5` 는 `400 COMMON.VALIDATION_FAILED` — 조용히 바꿔 읽지 않는다). 문자 칸이 없거나 빈 값이거나 너무 길어도 같다.
- 남의 것과 없는 것은 **같은 404**다(`PAYMENT.ORDER_NOT_FOUND` · `PAYMENT.REFUND_NOT_FOUND`) — id 가 맞는지 알려 주지 않는다. 운영자 경로는 계정과 상관없이 찾는다.
- 컨트롤러에는 `@Transactional` 이 없다. 승인 · 환불 · 맞추기는 결제사를 부르는 입구라 서비스가 **바깥 트랜잭션 안에서 부르면 던진다**(앱이 이 서비스들을 직접 부를 때도 같다).

## 1. 공통 모양

### 1.1 주문 `Order` (사용자 · 운영자 공통)

```jsonc
{ "id": "ord_…", "status": "PAID", "kind": "ONE_TIME", "itemType": "cheer", "itemRef": "cheer-3000", "quantity": 1,
  "orderName": "응원하기 3,000원", "amount": { "amount": 3000, "currency": "KRW" },
  "refundedAmount": 0, "refundableAmount": 3000, "refundPending": false, "openRefundStatus": null,
  "method": "CARD", "card": { "issuer": "HYUNDAI", "last4": "1234" } /* | null */, "easyPayBrand": null /* | "KAKAOPAY" | "NAVERPAY" */, "testMode": true,
  "failureCode": null, "createdAt": "…Z", "paidAt": "…Z" /* | null */, "fulfilled": true,
  "originalAmount": 3000 /* | null */, "discountAmount": 0, "discounts": [ { "type": "coupon", "ref": "cpn_1", "label": "30% off", "amount": 900 } ], "settlement": "PROVIDER" /* | "FREE" | "EXEMPT" */ }
```

- `status`: `PENDING`(결제창 대기) · `CONFIRMING`(승인 확인 중) · `PAID` · `PARTIALLY_REFUNDED` · `REFUNDED` · `FAILED` · `EXPIRED` · `CANCELED`. `kind`: `ONE_TIME`(1단계는 이것뿐).
- `refundableAmount` = 결제된 총액 − 환불된 금액 − **`PENDING`(환불 중)인 열린 환불의 금액** (`PAID` · `PARTIALLY_REFUNDED` 일 때만, 아니면 0). **`REQUESTED`(본인이 요청만 한 상태)는 줄이지 않는다** — 돈이 움직이지 않았고 승인은 그 요청의 금액으로 실행된다.
- `refundPending`: 열린 환불(요청됨 · 환불 중)이 있다. `openRefundStatus`: `"REQUESTED"`(본인이 요청함) · `"PENDING"`(환불 중) · 없으면 `null` — 화면이 「환불 요청함」과 「환불 중」을 가르는 칸.
- `method`: 결제 수단 **종류 코드**(`CARD` · `EASY_PAY` · `VIRTUAL_ACCOUNT` · `TRANSFER` · `MOBILE_PHONE` …, 영문 대문자로 시작하는 코드만 — 모르는 수단이면 `null`). 한글 라벨이 오지 않는다.
- `card`: 카드 결제일 때만 `{ issuer, last4 }`(각각 `null` 일 수 있다). `issuer` 는 결제사 중립의 대문자 영문 코드, `last4` 는 숫자 또는 `*` 최대 네 글자. 카드가 아니거나 **계정이 지워져 두 칸이 비워졌으면 `card` 자체가 `null`**.
- `easyPayBrand`: 간편결제의 **브랜드**(`"KAKAOPAY"` · `"NAVERPAY"` · `"TOSSPAY"` · `"SAMSUNGPAY"` …, 결제사 중립 대문자 코드; 그 밖의 값은 영문 코드 모양일 때만) - 화면이 「간편결제」 대신 앱 이름을 쓴다. 토스 경유는 확인 응답의 `easyPay.provider`(상호명)를 코드로 옮긴 것이고, 카카오페이 · 네이버페이 직접 연동은 결제사 자신이다. **경로(어느 결제사로 갔나)와 별개다** - 경로는 운영자 응답의 `provider`. 간편결제가 아니거나 모르면(이 칸이 생기기 전의 주문 포함) `null`. **칸은 늘 온다.**
- `testMode`: 이 주문이 테스트 키로 만들어졌다(화면은 늘 「테스트 결제」 띠를 보인다).
- `failureCode`(주문): 결제사가 승인을 거절한 코드(식별자 모양만, 코드를 못 읽으면 `DECLINED`) · `PROVIDER_EXPIRED` · `PROVIDER_ABORTED`(결제사가 만료 · 중단으로 끝냈다) · `EXPIRED_UNPAID`(결제 없이 시간이 지났다). 결제된 주문은 `null`.
- `fulfilled`: 앱의 지급(`PaymentOrderListener.onPaid`)이 끝났다.
- `originalAmount`(할인 전 금액 · 칸은 늘 온다)·`discountAmount`·`discounts`·`settlement` (C-P — 기존 칸에 **더하기만**): `amount` 는 늘 **최종 금액**(결제사가 받는 값 · 환불 상한)이다. 할인도 면제도 없는 주문(옛 줄 포함)은 `originalAmount: null` · `discountAmount: 0` · `discounts: []` · `settlement: "PROVIDER"`. `discounts[]` 의 `type` 은 할인 수단(`coupon`), `ref` 는 그 수단 안의 식별(쿠폰 id), `amount` 는 깎인 금액(양수) — 금액은 서버가 계산한 값이다. `settlement`: `PROVIDER`(결제사가 받았다) · `FREE`(쿠폰으로 0원 — **결제사 없이** 처음부터 `PAID`, `checkout: null`, `refundableAmount: 0`) · `EXEMPT`(결제 면제 권한 `payment.exempt` 계정 — 결제사 없이 `PAID`, `originalAmount` = 카탈로그 가격, `amount: 0`, `discountAmount: 0`, 할인 줄 없음). 운영자 응답은 같은 칸에 `provider` 가 `free` · `exempt` 로 보인다.

### 1.2 환불 `Refund` (사용자 응답)

```jsonc
{ "id": "rfd_…", "orderId": "ord_…", "status": "REQUESTED", "amount": { "amount": 3000, "currency": "KRW" },
  "reasonCode": "CUSTOMER_REQUEST", "origin": "USER", "requestedAt": "…Z", "completedAt": null, "closedBy": null }
```

- `status`: `REQUESTED`(본인이 요청 — 돈은 안 움직임) · `PENDING`(결제사에 보냈고 답을 모른다 — 조회 작업이 맞춘다) · `SUCCEEDED` · `FAILED` · `REJECTED`(운영자가 거절) · `WITHDRAWN`(닫힘) · `REVERSED`(이뤄졌던 환불이 **결제사 증거로 되돌려졌다** — 차지백 분쟁에서 이겨 돈이 돌아왔다. 끝 상태이고 주문의 환불된 금액에서 빠진다. 사용자 한 건 응답에도 올 수 있다).
- `origin`: `USER`(본인 요청) · `OPERATOR`(운영자 직접 환불) · `PROVIDER`(결제사 쪽에서 일어난 일을 장부에 넣은 줄 — 결제사 관리 화면의 취소는 `reasonCode` 가 `PROVIDER_CONSOLE`, 구매자의 이의 제기로 결제사가 돈을 가져간 **차지백**은 `CHARGEBACK`).
- `reasonCode`: 본인은 `CUSTOMER_REQUEST` · `DUPLICATE` · `NOT_DELIVERED` 만 고른다(그 밖은 `400`). 운영자는 거기에 `OPERATOR`. **자유 문장은 받지도 저장하지도 않는다.**
- `closedBy`: `WITHDRAWN` 줄만 값이 있다 — `"USER"`(본인이 거뒀다) · `"SYSTEM"`(주문이 전액 환불돼 요청이 닫혔다). 다른 상태는 `null`. 결제사 코드를 내보내지 않고 중립 칸 하나로만 알린다.

### 1.3 결제창 값 `checkout`

주문 만들기 응답에만 온다. 공개해도 되는 값만 담는다 — 프론트가 `kind` 로 갈라 연다.

```jsonc
{ "provider": "toss", "kind": "TOSS_PAYMENT_WINDOW", "clientKey": "test_ck_…", "testMode": true,
  "orderId": "ord_…", "orderName": "응원하기 3,000원", "amount": { "amount": 3000, "currency": "KRW" } } /* + "attributes": {} (Paddle: environment · priceId · customData) */
```

카카오페이(`payment-kakaopay`)의 결제창 값은 `"kind": "KAKAOPAY_REDIRECT"`, `clientKey: null`(공개 키가 없다), `attributes` = `{ pcUrl, mobileUrl, appUrl? }` - 프론트가 **구매자를 그 주소로 보낸다**(PC · 모바일에 맞는 것). 서버가 `ready` 를 부르고 받은 거래 번호(`tid`)는 이 주문의 결제사 결제 번호로 박힌다(사용자 응답에는 나오지 않는다 - 승인 요청이 번호 없이 간다). 구매자가 인증을 마치면 카카오페이가 서버 설정 `return-url` 에 `?kind=KAKAOPAY_REDIRECT&provider=<결제사 id>&order=<주문 id>&result=success&pg_token=…` 을 붙여 돌려보낸다(취소 · 실패는 `result=cancel` · `fail`) - 프론트가 이 `pg_token` 을 `/confirm` 의 `approvalToken` 으로 보낸다.

네이버페이(`payment-naverpay`)의 결제창 값은 `"kind": "NAVERPAY_REDIRECT"`, `clientKey: null`(공개 키가 없다), `attributes` = `{ windowUrl }` - 프론트가 **구매자를 그 주소로 보낸다**. 서버가 결제를 미리 예약하고(`v2/reserve` - 금액 · 주문 번호 · 돌아올 주소는 서버가 정한 값이라 구매자가 금액을 바꿀 수 없다) 받은 예약 번호로 결제창 주소(`<결제창 도메인>/payments/<예약 번호>`)를 만든다. 주문의 결제 번호는 비어 있고, 구매자가 결제를 마치면 네이버페이가 서버 설정 `return-url` 에 `?kind=NAVERPAY_REDIRECT&provider=naverpay&order=<주문 id>&resultCode=Success&paymentId=…` 을 붙여 돌려보낸다(그만두면 `resultCode=UserCancel`, 그 밖은 `resultCode` · `resultMessage` · `reserveId`) - 프론트가 이 `paymentId` 를 `/confirm` 의 `providerPaymentId` 로 보낸다(토스의 `paymentKey` 와 같은 자리, `approvalToken` 은 없다). 승인 응답의 총액 · 주문 번호가 주문과 다르면(다른 주문의 결제 번호를 들고 온 경우 등) 주문을 `PAID` 로 두지 않고 확인이 필요하다고 표시한다.

`clientKey` 는 `string | null` 이다 — 결제사 모듈이 정하고, 낼 수 없으면(토스: 공개 키가 비었거나 시크릿 키와 모드가 다름) `null` 이다. 그때 `GET /config` 의 `available` 도 `false` 다(§2). 돌아올 주소(`successUrl` · `failUrl`)는 프론트가 정해 결제창에 넘긴다 — **서버는 받지도 저장하지도 않는다.** 구매자 이름 · 이메일 · 전화는 결제창에 넘기지 않는다.

### 1.4 운영자 주문 `AdminOrder`

`Order` 의 모든 칸 **에 더해**(같은 객체에 펼쳐서): `accountId`(주문한 계정. `TOMBSTONE` 모드로 지워진 계정이면 대체 값) · `provider` · `providerPaymentId`(결제사 결제 번호, 없을 수 있음) · `reviewReason`(§7, 없으면 `null`) · `accountErasedAt`(계정이 탈퇴한 시각 `string | null` — 탈퇴 전이면 `null`, **칸은 늘 온다**) · `refundBlock`(서버에서 환불을 보낼 수 없는 이유 `"UNSUPPORTED" | "PROVIDER_OFF" | "MODE_MISMATCH" | null`, **칸은 늘 온다** — `UNSUPPORTED` = 그 결제사에 환불·조회 능력이 없다 · `PROVIDER_OFF` = 결제사 빈이 지금 없다(꺼짐 · 키 없음) · `MODE_MISMATCH` = 지금 끼운 키의 모드가 주문과 다르다. 설정만 보고 계산하며 외부 호출은 없다). 목록 줄 · 한 건 · `sync` 응답 모두 같다.
`accountId` 는 `TOMBSTONE` 모드로 지워진 계정이면 `deleted:` + 16진수 16자(계정 id 의 SHA-256 앞자리 — 계정마다 하나, 되돌릴 수 없다) 다.

### 1.5 운영자 환불 `AdminRefund`

`Refund` 의 모든 칸에 더해: `requestedBy`(요청한 사람 — 본인 요청이면 계정 id, 운영자 직접 환불이면 운영자 id) · `decidedBy`(보낸 · 거절한 운영자) · `failureCode`(§6.1) · `keepSubscription`(`true` = 운영자가 「구독도 해지」를 끄고 보낸 환불, **칸은 늘 온다**) · `orderKind`(그 주문의 `kind` — 화면이 「구독도 해지」 선택을 보일지 정한다) · `refundBlock`(그 주문의 것, `AdminOrder.refundBlock` 과 같다). 결제사가 검토 중으로 쥔 환불(Paddle)은 `PENDING` 이고 `failureCode` 가 `PROVIDER_PENDING` 이다. 결제사가 거절한 환불은 `FAILED` + `failureCode` `PROVIDER_REJECTED`.

### 1.6 이력 `Event` (운영자 한 건 응답에만)

```jsonc
{ "id": 1, "refundId": null, "type": "ORDER_CREATED", "fromStatus": null, "toStatus": "PENDING", "actor": "USER", "actorId": "acc_…",
  "providerCode": null, "providerTraceId": null, "traceId": "…", "createdAt": "…Z" }
```

- **`id` 는 그 주문 이력 안의 순번**이다(1 부터, 오래된 것부터). 다른 주문의 `id` 와 겹친다 — 전역 키로 쓰지 않는다.
- `refundId`: 환불 줄에 대한 이력이면 그 줄의 id. 이때 `fromStatus` · `toStatus` 는 **환불 줄의** 상태이고, 주문 이력에서는 주문의 상태다.
- `type`: `ORDER_CREATED` · `CONFIRM_STARTED` · `CONFIRM_SUCCEEDED` · `CONFIRM_DECLINED` · `CONFIRM_UNKNOWN` · `CONVERGED`(결제사와 맞춰 상태가 움직였다) · `EXPIRED` · `CANCELED` · `REOPENED_PAID`(닫혔던 주문이 결제사 증거로 `PAID` 가 됐다) · `FULFILLED` · `REFUND_REQUESTED` · `REFUND_SENT` · `REFUND_SUCCEEDED` · `REFUND_FAILED` · `REFUND_UNKNOWN` · `REFUND_REJECTED` · `REFUND_WITHDRAWN` · `REFUND_SYNCED`(결제사 쪽 취소를 장부에 넣었다) · `REFUND_APPLIED`(환불에 맞춰 앱이 지급을 거뒀다) · `REVIEW_FLAGGED` · `TRANSACTION_FAILED`(결제사가 거래 만들기를 거절했다 — `providerCode` 는 `400:결제사코드` 또는 `HTTP_상태`. 구독 시작의 거절은 줄이 없어 로그에만 남는다). · `PAYMENT_FAILED`(결제 시도가 실패했다 — Paddle `transaction.payment_failed` 웹훅의 `payments[].error_code` 가 `providerCode`, 주문 상태는 그대로 `PENDING`).
- `actor`: `USER` · `OPERATOR` · `SYSTEM` · `PROVIDER`. `actorId`: 사람이면 그 계정 · 운영자 id, 시스템 · 결제사면 `null`.
- `providerCode`: 결제사의 오류 · 상태 코드이거나 **우리 코드**(열이 하나뿐이다). 값은 §6.2. `providerTraceId`: 결제사 응답의 추적 번호. `traceId`: 우리 요청의 추적 번호.

## 2. 사용자 경로 (로그인 필요)

기준 경로 `/api/v1/payments`. 상태 코드 규칙의 요약은 §4.

### GET /config
결제를 받을 수 있는가. `200 { value: { available, testMode, refundRequestsEnabled, providers, pricePreviews, easyPays, country, provider, cancelPolicy, devCountrySwitch, choices, currency } }`. `providers`: 결제창을 열 수 있는 켜진 결제사 id 목록(예 `["paddle","toss"]`). **나라 · 결제사 · 해지·환불 정책은 서버가 이 요청마다 정한다**: `country` = 접속 나라(Cloudflare 의 `CF-IPCountry` — 클라이언트 IP 모드가 `cloudflare` 일 때만 믿는다 → 로컬 스위치 `routing.dev-country` → `subscription.cancel.default-country` 순, 못 정하면 `null`), `provider` = 그 나라의 결제사(`routing.country-providers` 에 그 나라가 있으면 그 결제사 — **못 열면 다른 결제사로 넘기지 않고 `null`, `available` 도 `false`**; 없는 나라는 `default-provider` → 열린 결제사가 하나뿐이면 그것), `devCountrySwitch`(로컬 개발용 `routing.dev-country-switch` 가 켜져 있으면 `true` — 그때만 요청의 `X-Dev-Country` 헤더가 `country` 를 정한다; 꺼져 있으면 헤더는 읽지 않는다), `cancelPolicy = { key, penaltyPercent?, withdrawalDays? }` = 그 나라의 해지·환불 정책(`korea` · `period-end-only` …, 숫자는 `korea` 일 때 설정값). `currency` = 이 요청의 결제 통화(ISO-4217) — 라우팅이 고른 결제사의 통화(`routing.provider-currencies.<결제사>` → 그 결제사가 받는 통화가 하나뿐이면 그것 → `routing.default-currency`, 정할 수 없으면 `null`; 칸은 늘 나간다), 구독 플랜 목록이 이 통화로 걸러진다. 주문 · 구독은 이 `provider` 를 실제로 쓴다 — **본문의 `country` 는 읽지 않는다(무시).** 본문의 `provider` 는 `choices`(아래) 안에 있을 때만 받고 그 밖은 읽지 않는다(구독 만들기는 읽지 않는다). 나라 지도가 정한 결제사가 못 열리면 새 주문 · 구독은 `503 UNAVAILABLE`(한국 방문자를 Paddle 로 보내지 않는다); 이미 있는 `Idempotency-Key` 의 재전송은 나라가 바뀌었어도 만든 때의 줄을 그대로 돌려준다. 주문 · 구독 줄에는 서버가 정한 나라가 기록된다.
`subscriptionChoices`: **구독을 시작할 때 구매자가 고를 수 있는 결제사** - `choices` 가운데 **빌링 능력**(카드 등록 · 간편결제 정기)을 가진 것. 같은 규칙: 둘 이상일 때만 화면이 「결제 수단」 고르기를 그리고 고른 id 를 `POST /subscriptions` 의 `provider` 로 보낸다. 칸은 늘 나간다(없으면 `[]`).
`choices`: **구매자가 고를 수 있는 결제사** - 이 요청의 나라를 자기 `supportedCountries` 에 적은, 지금 결제창을 열 수 있는 결제사 id(운영자가 같은 나라에 결제사를 둘 이상 켰을 때: 토스 + 카카오페이). 둘 이상일 때만 화면이 「결제 방법」 고르기를 그리고 고른 id 를 주문 만들기의 `provider` 로 보낸다. 나라 지도에 그 나라의 결제사가 없고 후보가 둘이면 `provider` 는 `null` 이라 **고르지 않은 주문은 `503 UNAVAILABLE`** 이다. 나라 지도가 정한 결제사가 못 열리면 `choices` 는 비고 구매자의 선택으로도 다른 결제사로 넘어가지 않는다. 다른 나라의 결제사(Paddle)는 `choices` 에 들지 않고, 서버가 통화를 바꿔 결제하는 결제사(거래를 먼저 만드는 Paddle)는 나라가 같아도 들지 않는다(원화 견적을 받지 않아 구매자가 고르면 늘 거절되므로). 칸은 `pricePreviews` 처럼 **늘 나간다**(고를 것이 없으면 `[]`) - 구버전 서버만 칸이 없다. 후보는 운영자의 `skeleton.payment.providers.<id>.countries` 덮어쓰기까지 반영한 **실효 나라 목록**으로 거른다. 구매자가 고른 결제사는 견적 통화도 받아야 한다(아니면 `PAYMENT.ROUTING_FAILED`). 서버가 나라로 정한 결제사도 받는 통화를 적어 둔 결제사라면 견적 통화를 받아야 한다 - 통화별 가격을 파는 앱에서 한국 방문자가 달러 가격 코드를 보내도 토스로 가지 않는다(받는 통화를 적지 않은 결제사 - Paddle - 는 거르지 않는다).
`pricePreviews`: 결제사 id → `{ clientToken, environment, prices }` (Paddle 만 — 클라이언트 토큰과 환경은 결제창 값과 같고, `prices` 는 상품 · 플랜 코드 → 가격 id 로 비밀이 아니다). 화면이 Paddle.js 의 가격 미리보기로 **구매자 통화로 환산된 실제 결제 금액**을 보이는 데 쓴다 — 우리 플랜 표의 원화 금액은 Paddle 결제의 금액이 아니다. 없는 결제사는 키가 없다.
`easyPays`: 결제사 id → 결제창 안에서 바로 열 간편결제 코드 목록(`["NAVERPAY","KAKAOPAY","TOSSPAY"]` — 토스 `skeleton.payment-toss.easy-pays`). 화면이 **주문을 만들기 전에** 카드 단추 옆에 단추를 그리는 데 쓴다. 목록이 빈 결제사는 키가 없고, 아무 결제사도 없으면 `{}` 이다(`pricePreviews` 와 같다 = 카드 단추 하나). 같은 목록이 그 결제사의 checkout 응답 `attributes.easyPays`(쉼표로 이은 문자열)에도 실려, 화면이 고른 코드를 거기서 한 번 더 맞춰 본다. 단건 결제만 - 자동결제 카드 등록에는 없다.
`available`: `PaymentCatalog` 빈이 있고 결제창을 여는 결제사가 있고 **그 결제사가 지금 결제창 값을 낼 수 있다**(토스: 클라이언트 키가 비었거나 시크릿 키와 모드가 다르면 `false`). `testMode`: `available` 이면서 그 결제사가 테스트 키다. `refundRequestsEnabled`: `skeleton.payment.refund.requests-enabled`.

### POST /orders — 주문 만들기 (`PaymentCatalog` 빈이 있을 때만)

선택 본문 칸 `provider`(결제사 id, 소문자) - **`GET /config` 의 `choices` 안에 있을 때만 받는다**(같은 나라에 결제사가 둘 이상 켜져 있고 구매자가 고른 것). 비우거나 후보 밖이면 서버가 정한 결제사(나라 지도 → 기본 결제사 → 열린 결제사가 하나뿐이면 그것)를 쓴다. 서명된 웹훅이 곧 사실인 결제사(Paddle)는 승인 호출(`/confirm`)이 없다 — 결제창이 끝나면 웹훅이 주문을 `PAID` 로 옮기므로 화면은 `GET /orders/{id}` 를 폴링한다. 그 주문의 금액 · 통화는 결제사가 받은 값으로 바뀐다(여러 통화).
헤더 `Idempotency-Key`. 본문 `{ "itemType": "cheer", "itemRef": "cheer-3000", "quantity": 1 }`(+ 선택 `returnOrigin`: 결제를 시작한 화면의 origin, 예 `"http://localhost:5173"` - 카카오페이 · 네이버페이가 돌아올 주소를 서버 설정 `allowed-return-origins` 안의 origin 으로 맞출 때만 쓰고, 목록 밖이거나 origin 모양이 아니면 읽지 않는다 = 설정의 `return-url`. 토스 · Paddle 은 읽지 않는다)(`itemType` 1~40자 · `itemRef` 1~128자 · `quantity` 생략하면 1).
**금액은 본문에 없다** — 앱의 카탈로그가 `(itemType, itemRef, quantity)` 로 정한다. 브라우저가 금액을 정하는 길은 없다.

| 응답 | 뜻 |
|---|---|
| `201 { value: { order, checkout } }` | 새 주문(`PENDING`) |
| `200 { value: { order, checkout } }` | 같은 `Idempotency-Key` 를 다시 보냈다 — 같은 주문. 주문이 이미 `PENDING` 이 아니거나 **결제 가능 시간이 지났으면**(만료 작업이 아직 안 돌았어도) `checkout` 은 `null` 이다(칸은 늘 온다). `order` 의 `refundPending` · `openRefundStatus` · `refundableAmount` 는 그 주문의 지금 환불 줄로 계산한 값이다 |
| `400 COMMON.VALIDATION_FAILED` | 헤더 없음 · 본문 모양이 틀림 |
| `409 PAYMENT.IDEMPOTENCY_CONFLICT` | 같은 키에 `itemType` · `itemRef` · `quantity` 가 다르다 |
| `422 PAYMENT.ITEM_NOT_PURCHASABLE` | 카탈로그가 거절(목록에 없는 상품 · 수량 · 품절) 또는 `quantity` < 1 또는 금액이 0 이하 |
| `429 COMMON.TOO_MANY_REQUESTS` | 계정마다 · IP 마다 요청 수 한도 (§9). `Retry-After` 헤더와 본문 `data.retryAfterSeconds` |
| `503 PAYMENT.UNAVAILABLE` | 결제창을 여는 결제사가 없다 **또는 그 결제사가 결제창 값을 못 낸다**(`GET /config` 가 `available: false` 라고 답하는 때와 같은 판정 — **새 주문이면** 주문 · 만료 작업은 만들어지지 않는다. **같은 키의 재전송이면** 앞선 `PENDING` 주문은 그대로 남고 결제창 값만 주지 않으며, 그 주문은 만료 작업이 닫는다) — **같은 키의 재전송에서 주문의 결제사 빈이 없어진 경우도 같은 코드**다(새 주문이든 재전송이든 `400 PROVIDER_NOT_FOUND` 가 아니다) |

주문은 `skeleton.payment.checkout-ttl`(기본 30분) 동안만 결제할 수 있다. 지나면 만료 작업이 결제사에 물어 `EXPIRED` 로 닫거나(결제가 있었으면 `PAID`), 늦게 온 결제를 `PAID` 로 맞춘다.

선택 본문 칸 `discounts: [{ "type": "coupon", "ref": "<쿠폰 id>" }]` — 첫 판은 **최대 1개**(더 보내면 `400 DISCOUNT_NOT_APPLICABLE`). 서버가 카탈로그 가격에 할인을 적용해 **최종가를 정하고**(브라우저가 보낸 금액·할인액은 어디에도 없다), 쿠폰을 그 주문 이름으로 **예약**한다(같은 쿠폰을 두 주문이 쥘 수 없다 — 진 쪽은 `409 DISCOUNT_UNAVAILABLE`). 최종가가 0 이면 결제사를 부르지 않고 `201 { order(status PAID, settlement FREE), checkout: null }`. 호출자가 `payment.exempt` 를 가졌으면 쿠폰은 보지도 않고 `settlement EXEMPT` 로 끝난다. 같은 `Idempotency-Key` 의 재전송은 처음 줄을 돌려준다(예약은 한 번). 결제를 시작하지 않은 주문은 만료 · 그만둠 · 거절 때 쿠폰을 풀고, 늦게 `PAID` 가 된 주문은 쿠폰을 다시 잡으며 그사이 다른 주문이 쥐었으면 둘 다 그대로 두고 운영자 응답의 `reviewReason` 에 `DISCOUNT_CONFLICT` 가 찍힌다(지급은 평소처럼 나간다). 전액 환불은 쿠폰을 돌려주고 부분 환불은 돌려주지 않는다.

### POST /quote — 주문을 만들지 않고 금액 보기 (`PaymentCatalog` 빈이 있을 때만)
본문 `{ "itemType", "itemRef", "quantity"?, "provider"?, "discounts"? }`(주문 만들기와 같은 칸, `Idempotency-Key` 없음) → `200 { value: { originalAmount, amount, currency, discounts: [{ type, ref, label, amount }], settlement } }`. **주문 만들기와 같은 계산**이라 견적의 `amount` 가 주문의 `amount` 다. 아무것도 쥐거나 쓰지 않는다. `settlement: "FREE"` 면 결제창 없이 끝나는 0원, `"EXEMPT"` 면 호출자가 결제 면제(`amount: 0`). 오류는 주문 만들기와 같은 `PAYMENT.DISCOUNT_*` · `422 ITEM_NOT_PURCHASABLE`. 요청 수 한도는 걸지 않는다(쿠폰함이 쿠폰마다 부른다).

### POST /orders/{id}/confirm — 결제창에서 돌아온 결제를 승인
본문 `{ "providerPaymentId": "…", "amount": 3000 }`(`providerPaymentId` 1~200자 - 아래 예외를 빼면 필수 · `amount` 정수) + 선택 `"approvalToken"`(1~200자 문자열 - 돌아온 결제창이 승인을 허락한 증거, 카카오페이의 `pg_token`. 토스는 보내지 않는다). **주문에 결제사가 먼저 정한 거래 번호가 박혀 있으면(카카오페이) `providerPaymentId` 는 생략할 수 있다** - 서버가 박힌 번호를 쓴다. 보냈는데 박힌 번호와 다르면 결제사를 부르기 전에 `409 ORDER_NOT_PAYABLE`. `approvalToken` 이 결제사가 받는 모양이 아니거나(카카오페이는 필수) 없으면 **선점 전에** `400 VALIDATION_FAILED`(field `approvalToken`) - 주문은 `PENDING` 그대로다. 박힌 번호가 없는 주문(토스)에서 생략하면 `400 VALIDATION_FAILED`. **`amount` 는 비교만 한다** — 결제사에는 주문 줄의 금액이 간다.

| 응답 | 뜻 |
|---|---|
| `200 { value: order }` | `PAID`. **같은 결제 키로 다시 보낸 것도 200**(결제사를 다시 부르지 않는다) |
| `202 { value: order }` | `CONFIRMING` — 결제사가 답하지 않았다. `GET /orders/{id}` 를 되풀이해 부른다. 다시 승인을 보내도 결제사는 다시 불리지 않는다 |
| `400 PAYMENT.AMOUNT_MISMATCH` | 보낸 `amount` 가 주문 금액과 다르다. **결제사를 부르기 전에** 막고 주문은 `PENDING` 그대로 |
| `400 COMMON.VALIDATION_FAILED` | 본문 모양 · 음수 금액 · **결제사가 받을 수 없는 모양의 결제 키**(field `providerPaymentId`. 토스는 영문 · 숫자 · `-` · `_`). 선점 전에 막으므로 주문은 `PENDING` 그대로이고 결제사 호출도 조회 작업도 생기지 않는다 |
| `402 PAYMENT.DECLINED` | 결제사가 확정 거절. 주문은 `FAILED`. `data.providerCode`(결제사 코드) · `data.category`: `LIMIT_OR_BALANCE`(한도 · 잔액) · `CARD_DECLINED`(카드사 거절) · `TEMPORARY`(일시) · `OTHER` — **문구는 category 로 고르고 코드는 참조 번호로만** 보인다 |
| `404 PAYMENT.ORDER_NOT_FOUND` | |
| `409 PAYMENT.ORDER_NOT_PAYABLE` | 주문이 `FAILED` · `EXPIRED` · `CANCELED` 이거나 결제 시간이 지났거나, 이미 결제된 주문에 **다른** 결제 키를 보냈거나, 그 결제 키가 다른 주문에 붙어 있다 |
| `409 PAYMENT.MODE_MISMATCH` | 주문을 만들 때의 키 모드(테스트 · 실결제)와 지금 끼운 키의 모드가 다르다 |

`CONFIRMING` 인 주문에 다시 승인을 보내면 **결제 키가 같든 다르든** 결제사를 부르지 않고 `202` 로 지금 주문을 돌려준다(다른 결제 키는 장부에 남지 않는다). `PAID` 인 주문에 **다른** 결제 키를 보내면 `409 ORDER_NOT_PAYABLE` 이다. 결과를 모르는 오류(네트워크 · 시간 초과 · 5xx)를 받은 화면은 **같은 주문의 승인을 다시 보내도 안전하다.**

### POST /orders/{id}/abandon — 결제창에서 그만뒀다
본문 없음. `200 { value: order }` — `PENDING` 이면 `CANCELED`. **이미 끝난 주문이면 오류가 아니라 지금 상태 그대로** 돌려준다(`PAID` 여도).

### GET /orders/{id}
한 건과 그 환불 줄. `200 { value: { order, refunds } }` — `refunds` 는 `Refund` 배열, 오래된 순. **`PENDING`(결제하지 않은 시도)도 본인에게는 준다.** 결과 화면이 `CONFIRMING` 동안 이것을 되풀이해 부른다.

### GET /orders?cursor=&size= — 내 결제 내역
`200 { values: Order[], cursor }`. 최신순. **`PENDING` 만 뺀다**(결제하지 않은 시도) — 실패 · 만료 · 그만둔 주문은 나온다.

### POST /orders/{id}/refund-requests — 환불 요청
헤더 `Idempotency-Key`. 본문 `{ "reasonCode": "CUSTOMER_REQUEST", "amount": 1000 }` — `amount` 는 생략하면 **남은 전액**. **요청일 뿐이다 — 돈은 운영자가 결정할 때까지 움직이지 않는다.**

| 응답 | 뜻 |
|---|---|
| `201 { value: refund }` | `REQUESTED` 줄 |
| `200 { value: refund }` | 같은 `Idempotency-Key` 를 다시 보냈다 — 그 줄 그대로 |
| `400 COMMON.VALIDATION_FAILED` | 헤더 없음 · 본인이 고를 수 없는 `reasonCode`(`OPERATOR` 등) · `amount` ≤ 0 |
| `400 PAYMENT.REFUND_AMOUNT_EXCEEDED` | 환불할 수 있는 금액(`refundableAmount`)을 넘는다 |
| `404 PAYMENT.ORDER_NOT_FOUND` | |
| `409 PAYMENT.REFUND_NOT_ALLOWED` | 결제된 주문이 아니다 · `refund.requests-enabled=false` |
| `409 PAYMENT.REFUND_IN_PROGRESS` | 이 주문에 이미 열린 환불(요청됨 · 환불 중)이 있다 — 주문당 하나뿐 |
| `409 PAYMENT.IDEMPOTENCY_CONFLICT` | 같은 키에 주문 · 사유 · 금액이 다르다 |

### POST /refunds/{id}/withdraw — 내 요청을 거둔다
본문 없음. `200 { value: refund }`(`WITHDRAWN`, `closedBy: "USER"`). `REQUESTED` 일 때만 — 이미 결정됐거나 거뒀으면 `409 PAYMENT.REFUND_NOT_ALLOWED`. 남의 줄 · 없는 줄은 `404 PAYMENT.REFUND_NOT_FOUND`.

## 3. 운영자 경로 (`skeleton.payment.admin.enabled=true`)

기준 경로 `/api/v1/admin/payments`. 로그인 필요(익명 `401`), 권한이 없으면 `403 COMMON.FORBIDDEN`. 판정은 핸들러마다 권한 하나이고, 인터셉터와 컨트롤러 두 곳이 **같은 표**로 매 호출 확인한다:

| 권한 | 경로 |
|---|---|
| `payment.read` | `GET /orders` · `GET /orders/{id}` · `GET /refunds` · `GET /webhook-events` |
| `payment.refund` | `POST /orders/{id}/refunds`(직접 환불) · `POST /refunds/{id}/approve` · `POST /refunds/{id}/reject` |
| `payment.operate` | `POST /orders/{id}/sync` · `POST /orders/{id}/close` · `POST /webhook-events/{id}/retry` |

CS 처럼 `payment.read` + `payment.refund` 만 가진 역할은 환불 줄만 만지고 결제사와 맞추기 · 닫기 · 웹훅 다시 처리는 `403` 이다. 누가 어느 권한을 갖는지는 `skeleton.authz`(전권 역할 `ADMIN` 은 `*` 로 모두). `account` 를 쓰는 앱은 매 호출 저장소의 역할을 읽으므로 회수 · 정지가 토큰이 유효해도 다음 호출부터 먹는다. 앱이 `PaymentOperatorPolicy` 빈을 두면 그 판정이 대신한다(`isOperator` 만 구현하면 권한을 가르지 않고 전 경로를 허락 · 거절한다). 꺼져 있으면(기본) 아래 모든 경로가 `404 COMMON.NOT_FOUND` — 프론트의 「꺼짐」 판정은 이 코드가 `PAYMENT.ORDER_NOT_FOUND` · `PAYMENT.REFUND_NOT_FOUND` · `PAYMENT.WEBHOOK_EVENT_NOT_FOUND` · `PAYMENT.PROVIDER_PAYMENT_NOT_FOUND` 넷 중 어느 것도 아닌 404 라는 것으로 한다.

### GET /orders?status=&accountId=&q=&review=&includeErased=&settlement=&cursor=&size=
`200 { values: AdminOrder[], cursor }`, 최신순. 모든 쿼리는 선택.

| 쿼리 | 뜻 |
|---|---|
| `status` | 주문 상태 하나(§1.1). 모르는 값은 `400 COMMON.VALIDATION_FAILED` |
| `accountId` | 그 계정의 주문 |
| `q` | **정확한** 주문 id 또는 결제사 결제 번호(앞뒤 공백은 뗀다). 부분 일치는 없다. 한두 줄이라 **커서가 없다**(`hasNext: false`). `status` · `accountId` · `review` 는 같이 걸린다 |
| `review=true` | 사람이 봐야 하는 주문만(§7). 줄에 이유가 찍힌 것 + 파생(오래 확인 중 · 오래 환불 중 · 지급 안 끝남 · 회수 안 끝남) |
| `includeErased=true` | 탈퇴한 계정의 주문도 (§3.1) |

#### 3.1 탈퇴한 계정의 줄
계정이 지워진 주문(`account_erased_at` 이 찍힘)은 **목록에서 기본으로 숨긴다**. 보이는 경우: `includeErased=true` · `q` 로 정확히 찾았을 때 · `review=true`. 한 건(`GET /orders/{id}`)과 환불 요청 목록(`GET /refunds`)은 항상 보인다 — 탈퇴한 사람의 열린 환불 요청도 운영자가 처리할 수 있다.

### GET /orders/{id}
`200 { value: { order: AdminOrder, refunds: AdminRefund[], events: Event[] } }`. `refunds` 는 오래된 순, `events` 는 **오래된 순**(§1.6). 없으면 `404 PAYMENT.ORDER_NOT_FOUND`. 파생 사유(`STALE_CONFIRMING` · `STALE_REFUND` 등, §7)는 목록 줄뿐 아니라 여기와 `sync` 응답의 `order.reviewReason` 에도 실린다.

### POST /orders/{id}/refunds — 바로 환불
헤더 `Idempotency-Key`. 본문 `{ "reasonCode": "OPERATOR", "amount": 1000, "endSubscription": true }` — `reasonCode` 는 `OPERATOR` · `CUSTOMER_REQUEST` · `DUPLICATE` · `NOT_DELIVERED`(그 밖은 `400`), `amount` 생략하면 남은 전액. `endSubscription`(선택, 생략 = `true`)은 **구독 결제를 전액 환불하면 구독도 해지**할지다 — `false` 면 환불만 하고 구독은 다음 갱신에서 계속 과금된다(지금 기간의 구독 결제를 전액 환불할 때만 뜻이 있다; 부분 환불은 어느 쪽이든 구독을 건드리지 않는다). 선택은 환불 줄에 남아(`keepSubscription`) 결제사가 며칠 뒤 끝내는 환불에도 그대로 적용된다. 같은 `Idempotency-Key` 에 다른 값이면 `409 IDEMPOTENCY_CONFLICT`. 칸이 `true`/`false` 가 아니면 `400 COMMON.VALIDATION_FAILED`. **Paddle 은 대개 `202 PENDING`** 이다 — Paddle 이 검토한 뒤 승인 · 거절을 웹훅으로 알린다(`PROVIDER_PENDING`). 실행 순서는 **환불 전 확인(결제사 조회 → 맞추기) → 선점(`PENDING`, 커밋) → 결제사 호출 → 확정**이다. 서버가 조회와 환불을 둘 다 하므로 프론트는 **45초**를 기다린다.

| 응답 | 뜻 |
|---|---|
| `200 { value: AdminRefund }` | `SUCCEEDED`. **같은 `Idempotency-Key` 재전송**이면 그 줄을 **지금 상태 그대로**(`FAILED` · `REJECTED` 여도) 200 으로 — 결제사를 부르지 않는다 |
| `202 { value: AdminRefund }` | `PENDING` — 결제사의 답을 모른다. **다시 보내지 않는다** — 조회 작업이 정산한다 |
| `400 PAYMENT.REFUND_AMOUNT_EXCEEDED` | 환불할 수 있는 금액 초과 |
| `400 COMMON.VALIDATION_FAILED` | 헤더 없음 · 사유 코드 아님 · 본문 모양 · **`amount` 가 0 이하**(`REFUND_AMOUNT_EXCEEDED` 가 아니다) |
| `400 COMMON.MALFORMED_REQUEST` · `COMMON.PARAMETER_VALIDATION_FAILED` | 깨진 JSON 본문 / 타입이 틀린 쿼리(§10). **이 세 검증 코드는 모두 환불 줄을 선점하기 전에만 나간다** — 프론트는 "아무것도 안 나갔다"로 읽고 멱등 키를 놓는다 |
| `402 PAYMENT.REFUND_DECLINED` | 결제사가 확정 거절. 줄은 `FAILED` 로 닫히고 주문은 다시 환불할 수 있다. `data.category`: `ALREADY_REFUNDED` · `AMOUNT_EXCEEDED` · `PARTIAL_NOT_ALLOWED` · `PERIOD_EXPIRED` · `TEMPORARY` · `OTHER`, `data.providerCode`(코드 없는 거절이면 줄의 `failureCode` 는 `REFUND_DECLINED`) |
| `404 PAYMENT.ORDER_NOT_FOUND` | |
| `409 PAYMENT.REFUND_IN_PROGRESS` | 이 주문에 열린 환불이 있다(본인의 `REQUESTED` 요청 포함 — 그것은 승인 · 거절로 처리한다) · **결제사가 검토 중으로 쥔 환불이 있는 거래**(Paddle — 환불 전 확인이 알아내고 보내지 않는다) |
| `409 PAYMENT.REFUND_UNSUPPORTED` | 그 결제사는 서버에서 환불을 못 한다(환불 · 조회 능력이 없다 — 예 `payment-stripe`). **결제사를 부르기 전에** 나간다. 결제사 관리 화면에서 환불한 뒤 요청을 거절한다 — 응답의 `refundBlock` 이 미리 말해 준다 |
| `409 PAYMENT.REFUND_NOT_ALLOWED` | 결제된 주문이 아니다 · 결제사 결제 번호가 없다 · **`REFUND_MISMATCH` 표시가 있다 · 또는 환불 전 확인에서 결제사에 그 결제가 없거나 「받지 않았다」(진행 중 · 실패 · 만료)고 했거나 결제사의 남은 금액이 장부와 달랐다**(이 어긋남에는 `REFUND_MISMATCH` 가 찍히고 환불 호출 · 환불 줄은 없다) — 이때는 먼저 「결제사와 맞추기」 |
| `409 PAYMENT.MODE_MISMATCH` | 주문의 키 모드와 지금 키의 모드가 다르다 |
| `409 PAYMENT.IDEMPOTENCY_CONFLICT` | 같은 키에 주문 · 사유 · 금액이 다르다 |
| `503 PAYMENT.UNAVAILABLE` | **아무것도 보내지 않았다** — 환불 전 확인 조회가 실패했거나 결제사 빈이 지금 없다(§4). 다시 누르면 새 줄이다 |

### GET /refunds?status=&cursor=&size=
`200 { values: AdminRefund[], cursor }`, **`status` 가 없으면 `REQUESTED`**(필터 없는 전체 목록은 없다). 최신순(요청한 시각). 모르는 `status` 는 `400`. 줄은 주문 이름 · 금액 없이 `orderId` 만 갖는다.

### POST /refunds/{id}/approve — 요청대로(또는 고친 금액으로) 환불
선택 본문 `{ "amount": <최소 단위 정수>, "endSubscription": true }` — `endSubscription` 은 바로 환불과 같다(생략 = `true`, 승인에는 멱등 키가 없다). `amount` 는 없으면 요청 금액, 있으면 `0 < amount ≤ min(요청 금액, 환불 가능액)`(넘으면 결제사를 부르지 않고 `400 PAYMENT.REFUND_AMOUNT_EXCEEDED`). 결제사에는 고친 금액이 가고, 줄의 `amount` 는 승인 금액이며 처음 요청한 금액은 `requestedAmount`(고치지 않았으면 `null`)로 남는다 — 사용자 · 운영자의 환불 줄 응답 모두. **`Idempotency-Key` 없음** — 줄의 상태가 방어다(같은 요청 id 의 동시 승인은 서버가 한 건만 실행하고 나머지는 `409 PAYMENT.REFUND_NOT_ALLOWED`). 응답은 직접 환불과 같다: `200 SUCCEEDED` · `202 PENDING` · `402 PAYMENT.REFUND_DECLINED`(**요청 줄은 `FAILED` 로 닫힌다** — 다시 시도하려면 직접 환불) · `400 PAYMENT.REFUND_AMOUNT_EXCEEDED`(**요청 줄은 `REQUESTED` 로 남는다** — 거절하거나 직접 환불) · `409 PAYMENT.MODE_MISMATCH` · `409 PAYMENT.REFUND_UNSUPPORTED`(**요청 줄은 `REQUESTED` 로 남는다** — 거절한다) · `404 PAYMENT.REFUND_NOT_FOUND` · `503 PAYMENT.UNAVAILABLE`.
**`409 PAYMENT.REFUND_NOT_ALLOWED` 는 두 뜻이다**: 요청이 이미 결정됐다, 또는 주문이 환불할 수 없다(결제 안 됨 · `REFUND_MISMATCH` · 환불 전 확인의 불일치 — 결제사가 그 결제를 모르거나 받지 않았다고 하는 것 포함). 화면은 중립 문구로 말한다.

### POST /refunds/{id}/reject
본문 없음. `200 { value: AdminRefund }`(`REJECTED`). `REQUESTED` 일 때만 — 이미 결정된 줄은 `409 PAYMENT.REFUND_NOT_ALLOWED`, 없으면 `404 PAYMENT.REFUND_NOT_FOUND`.

### POST /orders/{id}/close — 막힌 구독 결제를 「결제 안 됨」으로 닫기
선택 본문 `{ "confirmedAtProvider": true }`(운영자가 결제사 관리 화면에서 직접 확인했다). 결제사가 끝내 답하지 않아 `CONFIRMING` 으로 남은 **구독 결제 주문**만 - 그중에서도 `CONFIRM_UNRESOLVED` 표시가 있거나 `admin.stale-after` 보다 오래 확인 중인 것만(막 나간 승인은 `409`). 닫기 전에 결제사에 한 번 더 묻는다 — 결제된 것으로 나오면 닫지 않고 정상 `PAID` 로 맞추고(지급 작업이 이어진다), 결제사가 모르거나 실패 · 만료로 답하면 `FAILED`(`failureCode` `OPERATOR_CLOSED`, 이력에 운영자)로 닫아 구독의 자리를 푼다. `200 { value: AdminOrder }`. `409 PAYMENT.ORDER_NOT_CLOSABLE`(구독 결제가 아니다 · `CONFIRMING` 이 아니다 · 결제사가 아직 처리 중이다) 주문 번호로 물을 수 없는 결제사(카카오페이)의 **결제 키 없는 주문**은 결제사가 「모른다」고 해도 결제가 없다는 뜻이 아니므로 `confirmedAtProvider: true` 없이는 `409 PAYMENT.ORDER_CLOSE_NEEDS_CONFIRMATION` 이고 아무것도 닫지 않는다(닫으면 구독이 같은 기간을 다시 과금한다). · `404` · `409 MODE_MISMATCH` · `502` · `503`(결제사에 물을 수 없으면 닫지 않는다).

### POST /orders/{id}/sync — 결제사와 맞추기
본문 없음. 결제사에 묻고 장부를 맞춘다(결제사 관리 화면의 취소 포함). `200 { value: AdminOrder }` — 맞춘 뒤의 주문(`reviewReason` 이 풀렸으면 `null`). 응답의 `reviewReason` 은 한 건 상세의 `order.reviewReason` 과 **같은 계산**(파생 사유 포함)이다.
승인 경로(`POST /refunds/{id}/approve`)의 본문은 금액 하나뿐이다 — 검증 코드가 나갈 자리가 없다.
순서(§ [modules/payment.md](modules/payment.md) 「sync」): 모드 확인 → 조회 → `MODE_MISMATCH` 해제 → `converge` → `PENDING` 환불 줄 정산 → 다시 `converge` → **미지급 · 미회수 작업 재투입** → 지급 쪽 이유 해제. 그래서 이 단추는 「결제사와 다시 맞추고, 끝나지 않은 지급 · 회수를 다시 돌린다」다.

| 오류 | 뜻 |
|---|---|
| `400 PAYMENT.CAPABILITY_UNSUPPORTED` | 그 결제사는 조회를 못 한다 |
| `404 PAYMENT.PROVIDER_PAYMENT_NOT_FOUND` | **결제사가 그 결제를 모른다**(조회 결과 없음). 장부는 바뀌지 않는다. (키 모드가 달랐다면 조회 전에 `409` 로 끝난다 — `CONFIRMING` · 결제된 주문에는 `MODE_MISMATCH` 표시를 찍고 `PENDING` · 끝난 주문에는 찍지 않는다. 이 `404` 는 모드가 같은 호출에서만 나므로 표시를 찍은 채 끝나는 일은 이 코드에 없다.) 사용자 응답에는 이 코드가 나가지 않는다. 화면은 "맞췄다"가 아니라 "결제사에 그 결제가 없다"로 말한다 |
| `404 PAYMENT.ORDER_NOT_FOUND` | |
| `409 PAYMENT.MODE_MISMATCH` | 주문의 키 모드와 지금 키의 모드가 다르다. `CONFIRMING` · 결제된 주문에는 `MODE_MISMATCH` 표시가 찍히고, `PENDING`(선점한 적 없다 — 만료 작업이 닫는다)과 **끝난 주문**(`FAILED` · `EXPIRED` · `CANCELED` · 열린 환불 줄이 없는 `REFUNDED`)에는 이 `409` 만 나가고 표시도 이력도 남지 않는다 |
| `502 PAYMENT.PROVIDER_ERROR` | 결제사가 오류로 답했다. `data.provider` · `data.providerCode` · `data.providerMessage` · `data.providerTrace` · `data.upstreamStatus` · `data.retryable` (이 코드는 운영자 `sync` 에서만 나간다). **결제사가 한 말은 `data` 에만 있고 `detail` 은 고정 문장**이다 |
| `503 PAYMENT.UNAVAILABLE` | 결제사를 고를 수 없거나 대답이 없다 |

`200` 이어도 이유를 말하지 못하는 경우가 하나 있다: 결제사가 계속 「받지 않았다」(진행 중 · 실패 · 만료)고 답하는 `PAID` 주문은 `REFUND_MISMATCH` 가 남고 `sync` 는 404 가 아니라 **200** 이다 — 환불 거절(`REFUND_NOT_ALLOWED`)의 「먼저 결제사와 맞추기」 안내가 이 경우에는 풀지 못한다(알려진 한계 — [modules/payment.md](modules/payment.md)).

### GET /webhook-events?status=&cursor=&size= — 수신함
`200 { values: WebhookEvent[], cursor }`. 줄:

```jsonc
{ "id": 123, "provider": "toss", "eventType": "PAYMENT_STATUS_CHANGED", "resourceType": "PAYMENT", "resourceId": "…",
  "orderId": "ord_…" /* | null */, "status": "RECEIVED", "deliveries": 1, "lastError": null,
  "receivedAt": "…Z", "processedAt": null }
```

- **본문(`payload`)은 주지 않는다.** `status`: `RECEIVED`(처리 작업이 기다린다) · `PROCESSED`(조회해서 맞췄다) · `IGNORED`(맞출 것이 없었다) · `FAILED`(이번 시도 실패 — 작업이 다시 한다) · `DEAD`(작업이 시도를 다 썼다).
- `resourceId` 는 본문이 댄 값이 아니라 **우리 주문의 결제 키**(아직 없으면 `order:<주문 id>`)다. `deliveries`: 같은 이벤트가 도착한 횟수.
- `lastError`: 우리 코드뿐이다 — `LOOKUP_UNAVAILABLE`(결제사에 물을 수 없었다) · `PROCESSING_FAILED`(맞추는 도중 실패) · `PROVIDER_UNAVAILABLE`(결제사 빈이 지금 없다) · `LOOKUP_UNSUPPORTED`(조회 능력이 없다 — 소용없다) · `MODE_MISMATCH`(주문의 키 모드가 다르다 — 소용없다) · `NO_PAYMENT`(결제사가 그 결제를 모른다) · `UNKNOWN_ORDER`(가리키는 주문이 없다) · `RETRIES_EXHAUSTED`(시도를 다 썼다).
- 정렬 · 커서: `status` 를 정하지 않으면 **줄 id 순**(커서도 id), 정하면 받은 시각 순(`(receivedAt, id)` — 시각은 마이크로초까지). 모르는 `status` 는 `400`.
- **표식 줄**이 보통 줄과 같이 나온다 — §8.2.

### POST /webhook-events/{id}/retry — 다시 처리
본문 없음. `200 { value: WebhookEvent }` — 줄을 `RECEIVED` 로 되돌리고 새 처리 작업을 넣는다(`RECEIVED` · `FAILED` · `DEAD` · `IGNORED` 줄 모두, **표식 줄도**). 이미 `PROCESSED` 인 줄은 줄 그대로 200(아무 일도 없다). 운영자는 인증된 사람이라 연타를 막지 않고 `webhook.redelivery-requeue-limit` 와도 무관하다(작업은 멱등). 없는 id 는 `404 PAYMENT.WEBHOOK_EVENT_NOT_FOUND`.

### 3.2 운영자 계약의 서버 약속 (프론트가 이 약속에 기대 움직인다)

| 번호 | 서버가 지키는 것 |
|---|---|
| C1 | 주문의 `refundableAmount` = 총액 − 환불된 금액 − `PENDING` 인 열린 환불. `REQUESTED` 요청은 줄이지 않는다 — 요청이 열려 있으면 직접 환불은 `409 REFUND_IN_PROGRESS`, 승인은 요청 금액으로 |
| C2 | 승인이 결제사 확정 거절(402)로 끝나면 그 요청 줄은 `FAILED` 로 닫힌다 — 화면은 확인을 막고 「직접 환불」로 안내 |
| C3 | 같은 `Idempotency-Key` 재전송은 그 환불 줄을 지금 상태 그대로 돌려준다(`FAILED` · `REJECTED` 도 `200` + 줄) — 화면은 응답 줄의 `status` 로 성공 · 실패를 가르고, 확정 판정을 받으면 키를 놓는다 |
| C4 | 환불 전 확인 조회가 실패해 **보내지 않은** 경우는 `503 PAYMENT.UNAVAILABLE`(확정 — 아무것도 나가지 않았다) |
| C5 | 없는 주문은 반드시 `404 PAYMENT.ORDER_NOT_FOUND`, 없는 환불 줄은 `404 PAYMENT.REFUND_NOT_FOUND` |
| C6 | 없는 웹훅 줄의 retry 는 `404 PAYMENT.WEBHOOK_EVENT_NOT_FOUND`, 이미 `PROCESSED` 인 줄의 retry 는 줄 그대로 `200` |
| C7 | 같은 요청 id 의 동시 승인은 서버가 한 건만 실행, 나머지는 `409 PAYMENT.REFUND_NOT_ALLOWED` |
| C8 | `GET /refunds` 는 최신순. 목록 줄에도 운영자 칸이 있다, `size` 를 받는다, 결정된 줄의 `409 PAYMENT.REFUND_NOT_ALLOWED` |
| ① | 환불 · 승인 경로에서 `PAYMENT.*` 오류는 **이 호출이 `PENDING` 줄을 남기지 않았을 때만** 낸다 — 프론트는 장부 코드면 멱등 키를 놓는다. (`402 REFUND_DECLINED` 는 줄을 `FAILED` 로 닫은 뒤에 나가므로 남은 `PENDING` 이 없다) |
| ② | 같은 키 재전송은 **결제사 조회 없이** 그 줄을 돌려준다(중복 확인이 환불 전 확인보다 앞) |
| ③ | 선점 전 실패 중 장부 코드가 아닌 것(`PROVIDER_NOT_FOUND` · `ROUTING_FAILED` · `CAPABILITY_UNSUPPORTED`)은 운영자 환불 · 승인 경로에서 `503 PAYMENT.UNAVAILABLE` 로 옮긴다("보내지 않았다"). **이 번역은 서비스의 환불 전 확인(`precheck`) 안에만 있다** — 선점(`PENDING` 줄) 뒤에는 코드 위치상 걸릴 수 없다. `sync` 는 앞의 둘만 옮기고 `CAPABILITY_UNSUPPORTED` 는 `400` 그대로 |
| ④ | 승인의 `409 REFUND_NOT_ALLOWED` 는 두 뜻(요청이 이미 결정됨 · 주문이 환불 불가) — 프론트 문구는 중립 |
| ⑤ | 승인이 `400 REFUND_AMOUNT_EXCEEDED` 면 요청 줄은 `REQUESTED` 로 남는다 |
| ⑥ | 운영자 환불 · 승인 호출은 서버가 조회 + 환불을 하므로 프론트의 제한 시간은 45초 |

추가로: 환불 전 확인 조회는 **한 번 · 재시도 없음**이고 제한 시간은 `skeleton.payment-toss.lookup-timeout`(기본 10초) — 프론트의 맞추기 유예(보낸 때부터 45초)보다 충분히 짧다. 유예가 넘어야 하는 것은 서버의 선점 전 구간이다. `POST /orders/{id}/sync` 와 직접 환불 · 승인은 모드가 다른 주문에 모두 `409 PAYMENT.MODE_MISMATCH`.

## 4. 상태 코드 규칙

| 상황 | 응답 |
|---|---|
| 승인이 끝났다 / 결과를 모른다 | `200`(`PAID`) / `202`(`CONFIRMING`) |
| 보낸 금액이 주문 금액과 다르다 | `400 AMOUNT_MISMATCH` — **선점 전**, 주문은 `PENDING` 그대로 |
| 결제사가 승인을 확정 거절 | `402 DECLINED` + `data.providerCode` + `data.category`(`LIMIT_OR_BALANCE` · `CARD_DECLINED` · `TEMPORARY` · `OTHER`). 토스: `REJECT_CARD_*` 등 카드 · 계좌 거절 → `CARD_DECLINED`, `EXCEED_MAX_*` 한도 → `LIMIT_OR_BALANCE`, 그 밖 → `OTHER` |
| 환불 확정 | `200`(`SUCCEEDED`) / `202`(`PENDING` — 결제사의 답을 모른다) / 확정 거절 `402 REFUND_DECLINED` + `data.category` + `data.providerCode` |
| 본인의 환불 요청 | `201`(새 줄) / `200`(같은 키 재전송) |
| 같은 `Idempotency-Key` 재전송 | 줄 그대로 `200`(주문 만들기 · 환불 요청 · 직접 환불). 같은 키에 다른 요청이면 `409 IDEMPOTENCY_CONFLICT` |
| 주문의 키 모드와 지금 키 모드가 다르다 | `409 MODE_MISMATCH` (승인 · 직접 환불 · 승인 · `sync`) |
| 환불 · 승인 경로의 `PAYMENT.*` | **`PENDING` 줄을 남기지 않았을 때만**(§3.2 ①). 이미 `PENDING` 이 된 뒤에는 오류 코드로 답하지 않고 `202`(모름) 또는 `402`(줄을 `FAILED` 로 닫은 뒤) |
| 선점 전에 장부 코드 아닌 실패(결제사를 고를 수 없음 · 능력 없음 · 환불 전 조회 실패) | 운영자 환불 · 승인은 `503 UNAVAILABLE`. **`503` 은 선점 뒤에는 절대 나가지 않는다** |
| 승인 · 환불 · 맞추기 중 서비스의 프로그래밍 오류(바깥 트랜잭션 안에서 불림) | 서버 오류 — 클라이언트 잘못이 아니다 |

## 5. 사용자 응답에 나가지 않는 칸

사용자 응답(`Order` · `Refund` · 한 건 `{ order, refunds }`)에는 아래가 **없다**. 운영자 응답에만 있거나(⊕) 어디에도 나가지 않는다(✕).

| 칸 | |
|---|---|
| 결제사 이름 `provider`(경로 - 구매자에게는 간편결제 브랜드 `easyPayBrand` 만 나간다) · 결제사 결제 번호 `providerPaymentId` · 결제사 환불 번호 `providerRefundId` | ⊕ 앞의 둘 |
| 검토 이유 `reviewReason` | ⊕ |
| 요청한 · 결정한 운영자 `requestedBy` · `decidedBy` · 이력의 `actorId` | ⊕ |
| 환불 줄의 `failureCode`(결제사 코드이거나 우리 코드) | ⊕ — 사용자에게는 `closedBy`(`USER` \| `SYSTEM`) 중립 칸만 |
| `accountId`(계정 id) · `accountErasedAt` | ⊕ 앞의 것 |
| `paidAmount` · `taxAmount` · `providerMode` | ✕ (`testMode` 만) |
| `expectedRemaining` · `dedupeKey` · `openKey` | ✕ 내부 키 |
| `confirmStartedAt` · `checkoutExpiresAt` · `closedAt` | ✕ |
| 수신함 칸(`payload` · `payloadHash` · `eventKey`) | ✕ — 운영자 수신함 응답에도 본문 · 해시 · 이벤트 키는 없다 |
| 이력 `events` | ⊕ 한 건 운영자 응답만 |

(`PaymentUserHttpTest` 가 위 칸이 사용자 응답에 없고 결제사 결제 번호가 응답 어디에도 글자로 나오지 않음을 고정한다.)
이름 · 이메일 · 전화 · **전체 카드 번호 · 유효기간 · 소유자명은 어떤 응답에도 없다**(저장하지도 않는다). 개인정보로 볼 수 있는 칸은 카드사와 끝 네 글자뿐이고 탈퇴 때 지워진다(§ [modules/payment.md](modules/payment.md) 「탈퇴 · 익명화 · 보존」).

## 6. 코드 값

### 6.1 환불 줄 `failureCode` (운영자 응답)
결제사의 거절 코드(식별자 모양만)이거나 우리 코드다. 화면은 아는 코드를 문장으로, 모르는 코드는 그대로 보인다.

| 코드 | 줄 상태 | 뜻 |
|---|---|---|
| `NOT_APPLIED` | `FAILED` | 조회해 보니 결제사에 적용되지 않았다 — 닫혔고 주문은 다시 환불할 수 있다 |
| `PROVIDER_PENDING` | `PENDING` | 결제사가 **접수했다(처리 중)**고 답했다 — 자동으로 닫지 않는다. 출구는 `provider-pending-timeout` 뒤 사람이 누른 `sync`. 줄이 닫히면 지워진다 |
| `RESULT_MISMATCH` | `PENDING` · `SUCCEEDED` | 결제사가 요청과 다른 금액을 취소했다고 답했다 — 사람이 `sync` 로 풀 때까지 남는다(`SUCCEEDED` 가 된 뒤에도 남을 수 있고, 그때는 실패로 그리지 않는다) |
| `ORDER_REFUNDED` | `WITHDRAWN` | 주문이 전액 환불돼 열린 요청을 시스템이 닫았다 |
| `LATE_RESULT` | 닫힌 줄(`FAILED`) | 환불됨 응답이 줄이 이미 닫힌 뒤에 도착했다 — 같은 번호 · 같은 금액이고 합계가 맞으면 줄이 `SUCCEEDED` 로 되살아나 이 표시는 남지 않는다. 되살릴 수 없을 때(금액이 다르다 · 합계가 넘친다)만 남고, 돈이 나갔을 수 있으니 다른 환불 줄과 환불된 금액을 확인한다 |
| `REFUND_DECLINED` | `FAILED` | 결제사가 **코드 없이** 확정 거절했다 |
| `PROVIDER_REJECTED` | `FAILED` | 결제사(Paddle)가 검토한 뒤 환불을 **거절했다** — 돈은 움직이지 않았고 주문은 다시 환불할 수 있다 |
| `CHARGEBACK_WARNING` | `SUCCEEDED` | 차지백 **경고**에서 온 줄(`reasonCode=CHARGEBACK`) — 이어서 같은 금액의 차지백이 와도 같은 이의라 두 번 세지 않는다 |
| (결제사 코드) | `FAILED` | 결제사의 확정 거절 코드 그대로 |

`PROVIDER_PENDING_EXPIRED` 는 **줄이 아니라 이력에만** 남는다(그렇게 닫힌 줄의 `failureCode` 는 `NOT_APPLIED`). 그렇게 닫은 주문에서 다시 환불하면 먼저 처리되던 요청이 늦게 적용됐을 수 있다는 경고가 맞다.
`SUCCEEDED` 줄에도 `failureCode` 가 있을 수 있다(예 `RESULT_MISMATCH`).

### 6.2 이력 `providerCode`

| 값 | 같이 오는 `type` | 뜻 |
|---|---|---|
| (결제사 거절 코드) · `DECLINED` | `CONFIRM_DECLINED`, `REFUND_FAILED` | 승인 · 환불의 확정 거절(코드를 못 읽으면 `DECLINED` / 환불은 `REFUND_DECLINED`) |
| `PROVIDER_EXPIRED` · `PROVIDER_ABORTED` | `CONVERGED` | 결제사 조회가 만료 · 중단이라 `CONFIRMING` 주문을 닫았다 |
| `REFUND_LANDED` | `CONVERGED` | 주문이 환불을 받아들였다(`refundId` 포함, `fromStatus`/`toStatus` 는 주문의 상태) |
| `NOT_APPLIED` | `REFUND_FAILED` | 결제사에 적용되지 않았다고 닫음 |
| `LATE_RESULT_APPLIED` | `REFUND_SUCCEEDED`(`fromStatus=FAILED`, `toStatus=SUCCEEDED`) | 닫혀 있던 줄을 결제사가 이뤄졌다고 알려 와 결제사 말대로 `SUCCEEDED` 로 고쳤다(같은 번호 · 같은 금액일 때) - 이어서 주문 이력에 `REFUND_LANDED` 가 붙는다 |
| `PROVIDER_PENDING_EXPIRED` | `REFUND_FAILED` | 접수된 환불이 기한(`provider-pending-timeout`)이 지나도 조회에 안 보여 사람이 누른 `sync` 가 닫음 — 줄의 `failureCode` 는 `NOT_APPLIED` |
| `PROVIDER_PENDING` | `REFUND_UNKNOWN` | 결제사가 환불을 접수했다고 답함 |
| `RESULT_MISMATCH` | `REFUND_UNKNOWN` | 결제사가 요청과 다른 금액을 취소했다고 답함 |
| `LATE_RESULT` | `REFUND_UNKNOWN` | **이력의 종류가 「환불 결과를 아직 몰라요」로 읽히지만 뜻은 「닫힌 줄에 환불됨 응답이 늦게 왔다」** — 프론트는 이 값으로 가른다 |
| `ORDER_REFUNDED` | `REFUND_WITHDRAWN`(행위자 `SYSTEM`) | 주문이 전액 환불돼 열린 요청을 닫음 |
| `AMOUNT_MISMATCH` · `ORDER_MISMATCH` · `REFUND_MISMATCH` · `MODE_MISMATCH` · `CONFIRM_UNRESOLVED` · `BUYER_COUNTRY_KR` · `FULFILMENT_UNRESOLVED` · `REFUND_APPLY_UNRESOLVED` | `REVIEW_FLAGGED` | 검토 이유가 찍힘(§7) |
| `REPLACED_<앞선 이유>` | `REVIEW_FLAGGED` | 돈 쪽 이유가 지급 쪽 이유를 덮어썼다(예 `REPLACED_FULFILMENT_UNRESOLVED`) |
| `MODE_MISMATCH_CLEARED` · `REFUND_MISMATCH_CLEARED` · `FULFILMENT_UNRESOLVED_CLEARED` · `REFUND_APPLY_UNRESOLVED_CLEARED` | `CONVERGED` | 표시가 풀렸다(`<이유>_CLEARED`) |

문장으로 읽는 값은 `PROVIDER_PENDING_EXPIRED` · `LATE_RESULT` 둘이고, 나머지는 참조 번호로 그대로 둔다.

> 카카오 창에서 취소 · 실패로 돌아온 뒤 구독을 다시 시작할 때는 **새 멱등 키**를 쓴다(같은 키는 유효시간 안에서 같은 창을 돌려준다).

## 7. 검토 이유 아홉 개 · 누가 어떻게 푸는가

`reviewReason` 은 한 칸이다. **돈이 어긋난 이유가 지급 쪽 이유를 덮어쓰고 반대는 안 되며**, 같은 급끼리는 먼저 찍힌 것이 남는다. 줄에 찍힌 이유가 있으면 **그것이 먼저** 보인다. 없을 때 줄과 환불 줄에서 파생한 것이 보인다(장부가 스스로 표시하지 못한 마지막 안전망).

**줄에 찍히는 일곱**(구독을 켠 앱에는 하나가 더 있다 — 아래 표 끝의 `SUBSCRIPTION_STALE_CHARGE`)

| 이유 | 뜻 | 누가 · 어떻게 푸는가 |
|---|---|---|
| `AMOUNT_MISMATCH` | 결제사가 받았다는 총액이 주문 금액과 다르다. **주문은 `PAID` 로 올리지 않는다** | 장부에는 푸는 단추가 없다. 사람이 결제사 쪽을 확인해 처리한다. 주문이 `PAID` 가 되는 전이에서만 비워진다 |
| `ORDER_MISMATCH` | 결제사가 다른 주문의 결제를 가리킨다(조회 결과의 주문 번호 · 결제 키가 이 주문과 다르다) | 위와 같다 |
| `REFUND_MISMATCH` | 결제사의 취소 합계 · 남은 금액이 장부와 다르다 / 풀리지 않은 `PENDING` 환불 / 다른 금액의 취소 / 늦게 온 성공 응답. **이 표시가 있으면 환불을 보내지 않는다**(`409 REFUND_NOT_ALLOWED`) | 숫자가 맞게 되면 웹훅 · 조회가 자동으로 푼다. 단 풀리지 않은 `PENDING` 줄 · `RESULT_MISMATCH` · `LATE_RESULT` 가 걸려 있으면 **사람이 누른 `sync`** 만 푼다. 결제사가 접수한 줄(`PROVIDER_PENDING`)이 열려 있으면 `sync` 도 안 푼다(기한 뒤 `sync` 가 그 줄을 닫으면 풀린다) |
| `MODE_MISMATCH` | 주문을 만든 키의 모드(테스트 · 실결제)와 지금 키의 모드가 다르다. **승인을 선점한 `CONFIRMING` · 결제된 주문에만** 찍힌다 — 선점한 적 없는 `PENDING` 은 `sync` · 웹훅도 표시를 찍지 않고(409 만) 표시 없이 마감 뒤 `EXPIRED` 로 닫히며(그 줄에 먼저 찍힌 표시가 있었다면 닫을 때 비운다), 끝난 주문에는 `sync` 도 찍지 않는다 | 키를 맞추고 `sync` — 지금 모드가 주문과 같으면 풀린다. 선점한 뒤 모드가 바뀐 `CONFIRMING` 은 결제사가 그 결제를 모르면 풀리지 않는다(알려진 한계 — [modules/payment.md](modules/payment.md)) |
| `CONFIRM_UNRESOLVED` | `CONFIRMING` 인데 조회 작업이 시도를 다 쓰고도 결제사의 끝 상태를 못 들었다 | 웹훅 · `sync` 가 결제사에서 `PAID` 로 맞추면 비워진다. **주문이 `FAILED` · `EXPIRED` 로 닫히면 비워진다**(돈이 안 나간 것이 확정돼 의문이 풀렸다 — 안 비우면 닫힌 주문이 「확인 필요」에 영구히 남는다). 돈 · 모드 이유는 닫아도 그대로다(단 `PENDING` 이 닫힐 때의 `MODE_MISMATCH` 는 예외 — 그 줄에서는 닫을 때 비운다) |
| `BUYER_COUNTRY_KR` | 해외 결제사(Paddle)로 **첫 결제**한 구매자의 주소 나라(결제사 API 로 물은 `country_code`)가 한국이다(갱신 결제에는 다시 찍지 않는다). 막지 않았다 — 주문은 `PAID` 이고 돈은 받았다. 주소를 못 물으면 표시 없이 반영한다 | 장부에는 푸는 단추가 없다. 운영자가 보고 필요하면 환불한다 |
| `FULFILMENT_UNRESOLVED` | 돈은 받았는데 지급 작업이 죽었다(`fulfilled` 가 거짓) | 「결제사와 맞추기」가 지급 작업을 **다시 넣고** 푼다. 지급이 끝나면 스스로 비워진다 |
| `SUBSCRIPTION_STALE_CHARGE` | (구독) 구독이 기다리는 과금 주문이 아닌 구독 주문이 늦게 `PAID` 가 됐다 — 구독 기간은 그대로이고 돈만 받았다. 돈 쪽 이유라 지급 쪽 이유를 덮는다 | 장부에는 푸는 단추가 없다. 운영자가 그 주문을 환불한다(`POST /orders/{id}/refunds`) |
| `SUBSCRIPTION_CANCEL_REFUND_UNRESOLVED` | (구독) 중도 해지의 환불을 결제사에 보내지 못했거나(작업이 시도를 다 씀 · 금액 초과 · 모드 불일치 · 환불할 수 없는 상태) 결제사가 거절했다 — 구독은 이미 끝났다. 돈 쪽 이유 | 장부에는 푸는 단추가 없다. 운영자가 직접 환불한다 |
| `REFUND_APPLY_UNRESOLVED` | 환불은 `SUCCEEDED` 인데 앱의 회수(`onRefunded`) 작업이 죽었다 | 「결제사와 맞추기」가 회수 작업을 다시 넣고 푼다. 회수가 끝나면 스스로 비워진다 |

**파생 둘** — 줄에 찍힌 이유가 없을 때, `skeleton.payment.admin.stale-after`(기본 1시간)보다 오래된 것:

| 이유 | 뜻 |
|---|---|
| `STALE_CONFIRMING` | `CONFIRMING` 인 채 `confirm_started_at` 이 `stale-after` 보다 오래됐다 |
| `STALE_REFUND` | `PENDING` 환불 줄이 있는데 보낸 지(`sent_at`) `stale-after` 가 지났다 |

파생에는 이 둘 외에 **표시가 없는 지급 · 회수 안전망**이 있다. 프로세스가 죽어 죽은 작업의 표시가 안 찍힌 경우를 위해, 줄에 이유가 없어도 `stale-after` 가 지났으면 아래를 보인다 — 뜻은 위 줄의 같은 이름과 같다.

- `FULFILMENT_UNRESOLVED`: `PAID` · `PARTIALLY_REFUNDED` 인데 지급 시각이 비었고, 결제된 지 `stale-after` 가 지났다(결제 시각이 비었어도).
- `REFUND_APPLY_UNRESOLVED`: 환불은 `SUCCEEDED` 인데 회수 시각이 비었고, 끝난 지 `stale-after` 가 지났다.

우선순위(줄에 이유가 없을 때): `STALE_CONFIRMING` → `STALE_REFUND` → `FULFILMENT_UNRESOLVED` → `REFUND_APPLY_UNRESOLVED`. 파생 이유는 `review=true` 목록의 필터이기도 하다(갈래마다 자기 인덱스로 한 번씩 묻고 합친다; 환불 줄에서 주문으로 거슬러 올라가는 두 갈래도 한 문장에서 필터 · 커서 · 개수를 걸어 읽는다 — 줄 수 상한이 없다).

**프론트가 아는 값은 이 아홉**(백엔드 일곱 + 파생 `STALE_CONFIRMING` · `STALE_REFUND`)이고 모르는 값은 중립 글자로 그린다 — 서버가 새 이유를 더할 때는 순차 배포에 주의한다(아는 이유 목록에 없는 값을 읽으면 서버가 던진다).

## 8. 웹훅 수신 경로

### 8.1 주소와 응답
`POST <base-path>/webhooks/{provider}` · `…/{provider}/{token}`. 인증이 없다(공개 경로 — POST 두 모양뿐. 그 아래 다른 경로는 두지 않는다). 진위는 **서명 없는 결제사(토스의 결제 이벤트)면 주소의 비밀 조각(`skeleton.payment.webhook.path-token`)**, 서명 있는 결제사면 그 모듈의 서명 확인이다. 서명 있는 결제사는 조각을 보지 않는다.
**웹훅은 신호일 뿐이고 사실은 늘 결제사 조회에서 온다** — 본문의 상태 · 금액은 어디에도 쓰지 않는다. 결제사 모듈이 결제 키 · 주문 번호만 꺼내고, 모르는 주문은 저장하지 않는다.

| 응답 | 뜻 |
|---|---|
| `200 { meta }` | 받았다 · 중복 · 모르는 주문 · 다루지 않는 이벤트 · 주문 단위 상한을 넘음 — **구별하지 않는다**(위조자가 아는 주문인지 알아내지 못하게) |
| `404 PAYMENT.WEBHOOK_NOT_FOUND` | 모르는 결제사 · 웹훅 능력이 없는 결제사 · 꺼진 결제사 · 비밀 조각이 없거나 틀림 — **같은 응답**(어느 조각이 맞았는지 알리지 않는다). 설정된 조각이 비어 있으면 서명 없는 결제사의 경로는 늘 404 |
| `401 PAYMENT.WEBHOOK_REJECTED` | 진위 거절(서명이 틀림) · 읽을 수 없는 본문 |
| `413 PAYMENT.WEBHOOK_TOO_LARGE` | 본문이 `skeleton.payment.webhook.max-body-bytes`(기본 64 KiB, 최대 1 MiB) 초과 — **주소가 맞은 요청만** 이 말을 듣고 본문은 더 읽지 않는다 |
| `429 COMMON.TOO_MANY_REQUESTS` | 호출자 IP 마다 요청 수 한도(`webhook.rate-limit`). **`Retry-After` 헤더**와 `data.retryAfterSeconds`. 주소를 확인하기 **전에** 센다 |
| `500` | 저장 실패 — 결제사가 다시 보낸다 |
| `405` | POST 만 선다 |

순서: 한도 → 주소 → 본문 크기 → 해석 · 저장. 수신은 **DB 쓰기 한 번**(수신함 줄 + 처리 작업, 한 트랜잭션)이고 결제사를 부르지 않는다(토스는 10초 안에 200 을 받지 못하면 다시 보낸다는 것이 코드 주석의 주장이며 실측 전이다).
주소의 비밀 조각은 우리 로그에 남지 않는다 — 요청 로그의 경로에서 `…/webhooks/<provider>/` 뒤의 것을 가린다. 웹훅 주소: `https://<도메인>/api/v1/payments/webhooks/toss/<token>`.

### 8.2 표식 줄 (`LIMIT_MARKER`)
호출자가 정하는 전달 id 로 줄 · 작업 · 결제사 조회를 마음대로 늘리지 못하도록, 한 주문에 `webhook.per-order-limit.window`(기본 10분) 안에 저장된 새 줄이 `capacity`(기본 30)를 넘으면 새 이벤트는 저장하지 않고 `200` 으로만 답한다. 같은 이벤트의 재전송은 세지 않는다.
**신호는 버리지 않는다.** 상한에 걸리면 주문당 창마다 **표식 줄 하나**와 **창 끝에 도는 처리 작업(= 결제사 조회 한 번)** 이 걸린다. 운영자 수신함 목록에 이렇게 보인다:

- `resourceType = "LIMIT_MARKER"`, `eventType = "LIMIT_REACHED"`. 수신함 표의 `event_key` 열은 `limited:<주문 id>:<창 번호>`(창 번호 = 받은 시각 ÷ 창 길이의 몫)이지만 **응답에는 `eventKey` 칸이 없다** — 표식 줄은 `resourceType` · `eventType` 으로 알아본다.
- 뜻: 「이 주문의 웹훅이 창 상한에 걸려 창 끝에 조회 한 번으로 미뤄졌다」. `deliveries` 는 그 창에서 상한에 걸린 신호의 수.
- 표식 줄은 진짜 배달이 아니라 **우리가 만든 줄**이다 — 상한 계산에 세지 않는다. 결제사 본문은 이 종류의 줄을 만들 수 없다(`event_key` 의 `limited:` 접두사는 진짜 이벤트 키로 받지 않는다).
- 다른 줄처럼 `PROCESSED`/`IGNORED` 로 끝나고, 운영자가 `retry` 할 수도 있다.

## 9. 요청 수 한도

| 경로 | 한도 | 저장소 |
|---|---|---|
| `POST /orders` (주문 만들기) | **계정마다** `skeleton.payment.http.order-rate-limit.account-capacity`(기본 10) · **호출자 IP 마다** `ip-capacity`(기본 30) — `window`(기본 10분) 안에서. 둘 중 하나라도 넘으면 `429 COMMON.TOO_MANY_REQUESTS`(`Retry-After` 헤더와 본문 `data.retryAfterSeconds`). **계정을 먼저 판정하고**, 계정이 한도를 넘었으면 IP 통은 깎지 않는다(한 계정의 거절된 호출이 같은 NAT 의 다른 사용자를 막지 못하게). 본문이 JSON 으로 **읽힌 뒤**, 본문 칸 검증 · 서비스 호출 **전에** 센다 — 같은 키 재전송도 센다(깨진 JSON 은 세기 전에 `400 MALFORMED_REQUEST`) | 플랫폼의 `RateLimitStore` — `redis-rate-limit` 이 있으면 Redis(인스턴스끼리 공유), 없으면 **인스턴스별 메모리**(n 대면 한도가 n 배) |
| `POST …/webhooks/…` | 호출자 IP 마다 `webhook.rate-limit.capacity`(기본 120) / `window`(기본 1분) — 주소 확인 전에 센다. `Retry-After` 헤더 있음 | 위와 같다 |

계정당 주문 수가 웹훅 조회 유발의 전체 상한을 정한다(주문 하나 = 상한 × 조회 한 번의 창구). 이 한도는 플랫폼의 `skeleton.web.rate-limit`(전 경로 한도, 기본 꺼짐)와 따로 돈다.

## 10. 오류 코드

`PAYMENT.*` 전부. 결제사의 코드는 최상위 `code` 로 올리지 않고 `data.providerCode` 에 둔다. 프론트의 오류 코드 목록(`PAYMENT_LEDGER_ERROR_CODES`)과 `docs/errors.md` 와 같이 본다.

| 코드 | 상태 | 어디서 |
|---|---|---|
| `PAYMENT.ORDER_NOT_FOUND` | 404 | 남의 주문과 없는 주문(같은 응답) |
| `PAYMENT.REFUND_NOT_FOUND` | 404 | 남의 환불 줄과 없는 줄 |
| `PAYMENT.ITEM_NOT_PURCHASABLE` | 422 | 주문 만들기 — 카탈로그가 거절 |
| `PAYMENT.IDEMPOTENCY_CONFLICT` | 409 | 같은 `Idempotency-Key` 에 다른 요청 |
| `PAYMENT.AMOUNT_MISMATCH` | 400 | 승인 — 금액 불일치(결제사 호출 전) |
| `PAYMENT.ORDER_NOT_PAYABLE` | 409 | 승인 — 지금 상태에서는 결제할 수 없는 주문 |
| `PAYMENT.MODE_MISMATCH` | 409 | 키의 모드(테스트 · 실결제) 불일치 — 승인 · 직접 환불 · 승인 · `sync` |
| `PAYMENT.DECLINED` | 402 | 승인 확정 거절. `data.providerCode` · `data.category` |
| `PAYMENT.DISCOUNT_NOT_APPLICABLE` | 400 | 주문 · 견적 — 이 구매에 쓸 수 없는 할인(범위 밖 · 최소 금액 미달 · 통화 불일치 · 「첫 결제만」인데 결제한 적 있음 · 모르는 수단 · 한 주문에 둘 이상 · 깎는 것이 없음) |
| `PAYMENT.DISCOUNT_UNAVAILABLE` | 409 | 주문 · 견적 — 쿠폰이 이미 다른 주문이 쥐었거나 썼거나 만료됐다 |
| `PAYMENT.DISCOUNT_NOT_SUPPORTED` | 400 | 주문 · 견적 — 결제사가 금액을 직접 정하는 경로(거래를 먼저 만드는 결제사 · Paddle)는 할인을 받지 않는다 |
| `PAYMENT.DISCOUNT_BELOW_MINIMUM` | 400 | 주문 — 0 < 할인 뒤 금액 < 결제사 최소 금액. `data.minimumAmount` |
| `PAYMENT.NOTHING_TO_REFUND` | 409 | 환불 요청 · 직접 환불 · 승인 — 결제사 없이 끝난 주문(`settlement` FREE · EXEMPT)은 돌려줄 돈이 없다. 다른 환불 검사보다 먼저 이 코드로 거절한다 |
| `PAYMENT.REFUND_NOT_ALLOWED` | 409 | 환불할 수 없는 상태 · 이미 결정된 요청 · 「먼저 결제사와 맞추기」 |
| `PAYMENT.REFUND_IN_PROGRESS` | 409 | 이 주문에 열린 환불이 있다 |
| `PAYMENT.REFUND_AMOUNT_EXCEEDED` | 400 | 환불할 수 있는 금액 초과 |
| `PAYMENT.REFUND_DECLINED` | 402 | 환불 확정 거절. `data.category` · `data.providerCode` |
| `PAYMENT.SUBSCRIPTION_NOT_FOUND` | 404 | 구독 — 남의 구독과 없는 구독(같은 응답) |
| `PAYMENT.ALREADY_SUBSCRIBED` | 409 | 구독 — 이미 살아 있는 구독이 있다(계정당 하나) · 카드 등록 뒤 선점에 졌다 |
| `PAYMENT.SUBSCRIPTION_NOT_ACTIVATABLE` | 409 | 구독 — 이미 끝난 구독으로 카드 등록 · 첫 결제를 시도했다 |
| `PAYMENT.SUBSCRIPTION_NOT_CANCELABLE` | 409 | 구독 — 카드를 내지 않은(`PENDING`) 구독은 해지할 수 없다 |
| `PAYMENT.ORDER_CLOSE_NEEDS_CONFIRMATION` | 409 | 운영자 — 막힌 구독 결제 닫기: 주문 번호로 결제를 물을 수 없는 결제사(카카오페이)의 결제 키 없는 주문은 「없다」는 대답이 결제가 없다는 뜻이 아니다(응답이 유실된 승인이 돈을 받았을 수 있다) - 본문 `confirmedAtProvider: true` 없이는 닫지 않는다 |
| `PAYMENT.ORDER_NOT_CLOSABLE` | 409 | 운영자 — 막힌 구독 결제 닫기: 구독 결제가 아니다 · `CONFIRMING` 이 아니다 · 결제사가 아직 처리 중이다 |
| `PAYMENT.SUBSCRIPTION_CANCEL_NOT_ALLOWED` | 409 | 구독 중도 해지 - 이용 중(`ACTIVE` · `CANCELING`)이 아니다 · 이 구독의 나라 정책이 중도 해지를 닫았다 |
| `PAYMENT.SUBSCRIPTION_CHARGE_IN_PROGRESS` | 409 | 구독 중도 해지 - 갱신 · 변경 결제가 아직 오가는 중이다(끝난 뒤 다시) |
| `PAYMENT.PLAN_CHANGE_NOT_ALLOWED` | 409 | 구독 플랜 변경 — 꺼져 있다 · 이용 중(`ACTIVE`)이 아니다 · 위 등급이 아니다 · 주기 · 통화가 다르다 · 과금 중인 주문이 있다 |
| `PAYMENT.PLAN_CHANGE_CHARGE_CHANGED` | 409 | 구독 플랜 변경 — 서버가 다시 계산한 금액이 호출자가 본 금액보다 크다 |
| `PAYMENT.UNAVAILABLE` | 503 | 결제를 받을 수 없다 · **아무것도 보내지 않았다**(환불 전 확인 실패) |
| `PAYMENT.PROVIDER_NOT_FOUND` | 400 | 주문의 결제사 빈이 지금 없다 (사용자 승인 경로. 운영자 환불 · 승인 · `sync` 와 주문 만들기 재전송에서는 `503 UNAVAILABLE`) |
| `PAYMENT.ROUTING_FAILED` | 400 | 결제사를 고르지 못했다 (운영자 환불 · 승인 · `sync` 에서는 `503 UNAVAILABLE`) |
| `PAYMENT.CAPABILITY_UNSUPPORTED` | 400 | 그 결제사에 없는 능력 (운영자 환불 · 승인에서는 `503 UNAVAILABLE`) |
| `PAYMENT.PROVIDER_ERROR` | 502 | 운영자 `sync` — 결제사가 오류로 답함 (`detail` 은 고정 문장, 결제사의 말은 `data`) |
| `PAYMENT.PROVIDER_PAYMENT_NOT_FOUND` | 404 | 운영자 `sync` — 결제사가 그 결제를 모른다 (장부는 그대로) |
| `PAYMENT.WEBHOOK_REJECTED` | 401 | 웹훅 진위 거절 · 읽을 수 없는 본문 |
| `PAYMENT.WEBHOOK_NOT_FOUND` | 404 | 웹훅 주소가 맞지 않음(모르는 결제사 · 비밀 조각) |
| `PAYMENT.WEBHOOK_TOO_LARGE` | 413 | 웹훅 본문 초과 |
| `PAYMENT.WEBHOOK_EVENT_NOT_FOUND` | 404 | 운영자 `retry` — 없는 수신함 줄 |

이 문서의 경로가 쓰는 플랫폼 코드: `COMMON.PARAMETER_VALIDATION_FAILED`(400 — **타입이 틀린 쿼리 · 경로 변수**, 예 `size=abc`; 운영자 경로에서는 역할 검사가 먼저라 역할 없는 호출자는 이 코드가 아니라 `403`) · `COMMON.MALFORMED_REQUEST`(400 — **깨진 JSON 본문**) · `COMMON.VALIDATION_FAILED`(400 — 본문 · 헤더 · 쿼리 · 커서(시각이 1970~9999년 밖이거나 id 글자가 낯설면 거절); **결제사에 보내기 전에 모양이 틀린 결제 키 · 주문 번호도 이 코드**), `COMMON.UNAUTHORIZED`(401), `COMMON.FORBIDDEN`(403), `COMMON.NOT_FOUND`(404 — 꺼진 운영자 경로), `COMMON.TOO_MANY_REQUESTS`(429).

## 11. 프론트가 맞출 것

프론트(`@skeleton/payment`)가 가정한 모양과 **다른 점**. 나머지 칸 · 경로 · 상태 코드 · 오류 코드는 가정과 같다. 계약에 없는 칸을 더하지 않았다(응답 칸은 위 §1 이 전부다).

1. **이력 `id` 는 주문별 순번이다**(1 부터, 오래된 순) — 전역 id 가 아니다. 목록 키는 주문 id 와 같이 쓴다.
2. **이력에 `actorId` 칸이 더 있다**(`string | null` — 행위자가 사람이면 그 계정 · 운영자 id). 타입에 더한다.
3. 파생 사유(`STALE_CONFIRMING` · `STALE_REFUND`)는 **목록 줄뿐 아니라 한 건 상세와 `sync` 응답에도** 온다. 줄에 이유가 없어도 오래 지급 · 회수가 안 끝난 주문은 `FULFILMENT_UNRESOLVED` · `REFUND_APPLY_UNRESOLVED` 로 온다.
4. 운영자 환불 줄에도 `closedBy` 가 온다(사용자 쪽과 같은 값). 없을 때의 추정(`ORDER_REFUNDED` → `SYSTEM`)은 더는 필요 없다.
5. 운영자 목록은 **탈퇴한 계정의 주문을 기본으로 숨긴다**(§3.1) — 쿼리 `includeErased=true` 가 새로 있다. 「전체」 탭에서 탈퇴자의 주문이 안 보이는 것은 오류가 아니다. `q` 로 찾거나 「확인 필요」(`review=true`)에서는 보인다.
6. 수신함 `retry` 는 `FAILED` · `DEAD` 뿐 아니라 `RECEIVED` · `IGNORED` · 표식 줄에도 받는다(화면은 `FAILED` · `DEAD` 에만 단추를 두어도 된다). 표식 줄(`resourceType=LIMIT_MARKER`, `eventType=LIMIT_REACHED`)이 수신함 목록에 나온다 — 화면 문구가 필요하다(§8.2).
7. 주문 만들기의 `429` 에는 `Retry-After` 헤더와 `data.retryAfterSeconds` 가 같이 온다(웹훅 경로의 `429` 도 같다).
8. 환불 줄의 `origin` 에 `PROVIDER`, `reasonCode` 에 `PROVIDER_CONSOLE` 이 올 수 있다(결제사 관리 화면에서 취소한 것을 장부가 맞춰 넣은 줄). 운영자만 보는 줄이지만 사용자 한 건 응답에도 나올 수 있다. **`reasonCode` 에 `CHARGEBACK`(차지백 - 구매자가 카드사에 이의를 제기해 결제사가 돈을 가져갔다), `status` 에 `REVERSED`(분쟁에서 이겨 돌아옴), 이력 `type` 에 `CHARGEBACK_REVERSED` 도 올 수 있다**(Paddle - 화면은 모르는 코드도 중립 글자로 그린다).
9. 같은 키로 다시 보낸 직접 환불이 `FAILED` · `REJECTED` 줄이면 `200` 이다 — 오류가 아니므로 응답 줄의 `status` 로 가른다(가정과 같다).

## 12. 시험이 이 계약의 증거

- 사용자 경로: `modules/payment/src/test/kotlin/dev/sumin/skeleton/payment/web/PaymentUserHttpTest.kt` (상태 코드 · 오류 코드 · 사용자 응답에 나가지 않는 칸 · 커서) · `PaymentOrderRateLimitHttpTest.kt` · `PaymentHttpVariantsTest.kt`(켜짐 · 꺼짐 조합: 카탈로그 없으면 주문 만들기 경로 없음, `http.enabled=false`, 운영자 경로 기본 꺼짐).
- 운영자 경로: `PaymentAdminHttpTest.kt` · `PaymentAdminDisabledHttpTest.kt`(꺼진 경로는 모두 404) · `PaymentHttpAccessTest.kt`(등록된 매핑 전부에 접근 규칙 — 운영자 아홉 · 사용자 여덟) · `PaymentAdminContractHttpTest.kt`(멱등 키 · 응답 칸 · 칸 순서) · `PaymentAdminControllerAssemblyHttpTest.kt`(컨트롤러를 상속해 갈아 끼운 조립 · 인터셉터가 없는 조립에서도 아홉 핸들러가 닫힌다) · `PaymentOpenApiDocumentHttpTest.kt`(운영자 경로를 켠 채 OpenAPI 문서가 만들어진다).
- 웹훅: `PaymentWebhookWebTest.kt` · `PaymentWebhookRateLimitWebTest.kt` · `BoundedBodyTest.kt` · `WebhookLogMaskingTest.kt`.
- 탈퇴 · 내보내기: `PaymentAccountErasureTest.kt`.

## 12. 구독 (`skeleton.payment.subscription.enabled=true` 인 앱에만 — 꺼져 있으면 경로가 없다)

기준 경로는 위와 같다(`<http.base-path>`, 기본 `/api/v1/payments`). 로그인 필요, 남의 구독 · 없는 구독은 같은 `404 PAYMENT.SUBSCRIPTION_NOT_FOUND`, 본문에 금액은 없다(금액의 출처는 앱의 플랜). **빌링키 · 고객 키는 어떤 응답에도 없다** — 카드 등록 창 값(`billingAuth`)의 `customerKey` 만 예외다(창이 필요로 하고 공개해도 되는 값). 설계: `docs/superpowers/specs/2026-10-09-subscription-design.md`.

### 12.1 `Subscription`

| 칸 | 뜻 |
|---|---|
| `id` | `sub_…` (시간순 ID — `ord_` · `rfd_` 와 같은 방식) |
| `status` | `PENDING`(카드 등록 · 첫 결제 전 또는 확인 중) · `ACTIVE` · `PAST_DUE`(갱신 거절, 재시도 중 — 이용 불가) · `CANCELING`(해지 예약, 기간 끝까지 이용) · `ENDED` |
| `planCode` · `planName` | 만들 때 박힌 플랜 코드, 앱 플랜 목록의 이름(사라졌으면 코드) |
| `amount` `{amount, currency}` · `period` | 박힌 금액 · 주기(ISO-8601: `P1M`, `PT3M`) |
| `currentPeriodStart` · `currentPeriodEnd` | 지금 기간. `currentPeriodEnd` = 다음 결제일(`CANCELING` 이면 이용이 끝나는 날) |
| `pendingPlanCode` | 플랜 변경의 결제가 확인 중이면 바뀔 플랜 코드(`planCode` 는 아직 옛 플랜), 아니면 `null` |
| `attempt` | 이번 기간의 시도 번호 — `PAST_DUE` 에서 몇 번째 재시도인지 |
| `endReason` | `ENDED` 일 때: `FIRST_PAYMENT_DECLINED` · `USER_CANCELED` · `USER_CANCELED_NOW`(중도 해지) · `PAYMENT_FAILED` · `ACCOUNT_ERASED` · `RENEWAL_BLOCKED` · `REFUNDED` |
| `card` `{issuer, last4}` · `testMode` · `createdAt` | 화면 표시용 카드(끝나면 `null`) |
| `discount` | 할인이 붙은 구독이면 `{ label, stages: [{type: "FREE"\|"PERCENT"\|"FIXED", value, maxDiscount\|null, periods\|null}], periodsUsed, nextAmount, remainingPeriods\|null }`, 아니면(끝난 할인 포함) `null`. 칸은 늘 온다. `stages` 는 시작 때 복사한 단계(쿠폰 정의를 고쳐도 그대로), `nextAmount` 는 다음 결제액(최소 단위, 구독 통화), `remainingPeriods` 는 남은 할인 횟수(영원이면 `null`). `amount` 는 늘 **정가**(할인 전 박힌 금액)다 |

### 12.2 경로

| 경로 | 하는 일 · 응답 |
|---|---|
| `GET /subscriptions/plans` | `{plans: [{code, name, amount, period, level, productCode, regularAmount}]}` (`level` 이 높을수록 윗등급, `productCode` 는 같은 상품의 가격들이 같은 값, `regularAmount` 는 행사가가 붙은 가격의 정가 · 아니면 `null`). 이 요청의 통화(`GET /config` 의 `currency`)의 플랜만 — 그 통화 플랜이 없으면 전부 |
| `POST /subscriptions` `{planCode, provider?, returnOrigin?, discounts?}` + `Idempotency-Key`(필수) — `discounts: [{type:"coupon", ref}]`(첫 판은 한 장; 쿠폰 범위에 `SUBSCRIPTION` · 그 플랜이 있어야 한다)는 단계를 구독에 복사하고 첫 결제액이 할인된 금액이 된다(첫 기간이 무료면 카드만 등록받고 결제사에 돈을 묻지 않는다; 쿠폰은 첫 결제에서 쥔다 - 그 사이 팔렸으면 `activate` 가 `409 DISCOUNT_UNAVAILABLE`); 결제사가 갱신까지 하는 구독은 `400 DISCOUNT_NOT_SUPPORTED`, 쓸 수 없는 쿠폰은 `400 DISCOUNT_NOT_APPLICABLE` — 구매자 나라는 서버가 정한다(`GET /config` 의 `country`, 대문자로 저장; 본문의 `country` 는 무시). `provider` 는 `GET /config` 의 **`subscriptionChoices` 안에 있을 때만** 받는다(아니면 서버가 정한 결제사; 구매자가 고른 결제사는 플랜 통화도 받아야 한다 - 아니면 `400 ROUTING_FAILED`; 서버가 정한 결제사도 받는 통화를 적어 둔 결제사라면 같다). `returnOrigin` 은 결제를 시작한 화면의 origin - 돌아올 주소를 그 origin 으로 맞추는 결제사(카카오페이 · 네이버페이)가 허용 목록 안에서만 쓴다. 서버가 결제사를 먼저 불러 **예약**해야 창이 열리는 결제사는 여기서 예약하고(그 결제사가 못 답하면 `503`), 같은 키로 카드를 내기 전에 다시 보내면 **다시 예약해 새 창 값**을 돌려준다(앞 창의 결제는 모름이 된다 - 알려진 한계). 응답의 `subscription.country` 로 돌려준다 응답의 `subscription.country` 로 돌려준다 | `201 {subscription (PENDING), billingAuth}` · 같은 키로 다시: `200` 같은 줄(카드를 내기 전이면 같은 `billingAuth`, 낸 뒤면 `null`) · `400`(헤더 없음) · `409 ALREADY_SUBSCRIBED` · `409 IDEMPOTENCY_CONFLICT`(다른 플랜) · `422 ITEM_NOT_PURCHASABLE`(모르는 플랜) · `429`(주문 만들기와 같은 한도) · `503 UNAVAILABLE`. `billingAuth` = `{provider, kind, testMode, clientKey, customerKey, attributes}` — 프론트가 `kind`(토스: `TOSS_BILLING_AUTH`, 카카오페이: `KAKAOPAY_BILLING_REDIRECT`, 네이버페이: `NAVERPAY_BILLING_REDIRECT`)로 갈라 등록 창을 연다. **`clientKey` 는 `string \| null`** — 리다이렉트 결제사는 공개 키가 없다. 구독 응답에는 간편결제 정기의 브랜드 `easyPayBrand`(`KAKAOPAY` · `NAVERPAY` · 그 밖은 `null`)가 늘 있다 |
| `POST /subscriptions/{id}/activate` `{providerPayload}` — 토스 `{authKey}` · 카카오페이 `{pg_token}`(첫 결제의 인증 - **첫 결제가 곧 등록**이라 승인 응답의 SID 가 빌링키가 된다) · 네이버페이 `{reserveId, tempReceiptId}`(등록은 돈이 안 움직이고 첫 결제는 이어서). 카카오페이 · 네이버페이는 `billingAuth` 가 `kind: KAKAOPAY_BILLING_REDIRECT`(`attributes` = `{pcUrl, mobileUrl, appUrl?}`) / `NAVERPAY_BILLING_REDIRECT`(`attributes` = `{windowUrl}`), `clientKey: null` 이고 프론트가 **구매자를 그 주소로 보낸다**(돌아오는 주소는 서버 설정 `return-url` + `kind=…_BILLING_REDIRECT&provider=<id>&sub=<구독 id>`). 돌아온 값의 모양이 결제사가 받을 모양이 아니거나 네이버페이의 `reserveId` 가 이 구독에 박힌 것과 다르면 자리를 선점하기 전에 `400` | 빌링키 발급 → 첫 결제. `200 ACTIVE`(재전송도) · `202 PENDING`(첫 결제를 결제사가 아직 답하지 않았다 — `GET /subscriptions/me` 로 확인) · `402 DECLINED`(`data.providerCode` · `data.category`, 구독은 끝난다) · `409 ALREADY_SUBSCRIBED` · `409 SUBSCRIPTION_NOT_ACTIVATABLE`(이미 끝난 구독) |
| `GET /subscriptions/me` | `{subscription (없으면 null), orders: [최근 결제 5건 — 주문 `Order` 모양]}`. 끝난 구독도 `endReason` 과 함께 남는다 — 갱신 실패 · 종료의 유일한 통지다. 시작하지 않은 `PENDING` 은 보이지 않는다 |
| `POST /subscriptions/{id}/cancel` | `ACTIVE` → `CANCELING`, `PAST_DUE` 는 바로 `ENDED`. 다시 눌러도 같은 답. `409 SUBSCRIPTION_NOT_CANCELABLE`(`PENDING`) |
| `GET /subscriptions/{id}/change-preview?planCode=` | 위 등급으로 바꾸면 지금 얼마가 결제되는가: `{planCode, planName, charge, amount, period, restartsPeriod}` — `charge` 가 지금 결제될 금액(0 이면 과금 없이 바뀐다), `amount` 는 새 플랜 전체 가격, `restartsPeriod` 면 오늘부터 새 주기. `409 PLAN_CHANGE_NOT_ALLOWED` · `422 ITEM_NOT_PURCHASABLE` |
| `POST /subscriptions/{id}/change` `{planCode, expectedCharge}` + `Idempotency-Key`(필수) | 위 등급으로 바로 바꾼다. 결제가 `PAID` 가 된 뒤에만 플랜이 바뀐다. `200` 바뀜 · `202` 결제 확인 중(`pendingPlanCode`) · `402 DECLINED`(플랜 그대로) · `409 PLAN_CHANGE_CHARGE_CHANGED`(서버가 다시 계산한 금액이 `expectedCharge` 보다 크다 — 미리보기부터 다시) · `409 PLAN_CHANGE_NOT_ALLOWED`. 과금 방식은 앱 설정 `skeleton.payment.subscription.plan-change.{enabled,charge,min-charge}`(`charge`: `FULL_RESTART` 전액 + 새 주기 · `PRORATED_DIFFERENCE` 남은 기간 차액만, 기본값 없음) |
| `GET /subscriptions/{id}/cancel-preview` | 지금 해지하면 얼마가 돌아오는가: `{open, refund, withdrawal, lines[{code: PAID(+) · USED(-) · PENALTY(-), amount}]}`. `open=false` 면 이 구독의 나라 정책이 중도 해지를 닫았다(기간 끝 해지만, `refund` 0). `409 SUBSCRIPTION_CANCEL_NOT_ALLOWED`(이용 중이 아님) |
| `POST /subscriptions/{id}/cancel-now` | 구독을 즉시 `ENDED`(`endReason=USER_CANCELED_NOW`)로 끝내고 이번 기간에 낸 주문마다 환불한다(기존 환불 장부 · 빌링키 삭제). `200 {subscription, refund, withdrawal, lines, refunds[{orderId, amount, status: SUCCEEDED · PENDING · FAILED · NOT_SENT}]}` — 다시 눌러도 같은 답 · 환불은 한 번. `NOT_SENT` 는 서버가 작업으로 다시 보낸다, `FAILED` 는 결제사가 거절했다(운영자가 본다). `409 SUBSCRIPTION_CANCEL_NOT_ALLOWED`(정책이 닫음 · 이용 중이 아님) · `409 SUBSCRIPTION_CHARGE_IN_PROGRESS`(갱신 · 변경 결제가 오가는 중). 정책은 설정 `skeleton.payment.subscription.cancel.{default-country,policies,korea.{penalty-percent,withdrawal-days}}` |

구독 주문(`Order`)은 `kind` 가 `SUBSCRIPTION_INITIAL` · `SUBSCRIPTION_RENEWAL` · `SUBSCRIPTION_CHANGE`, `itemType=subscription`, `itemRef`=플랜 코드이고 기존 `/orders/{id}` · 운영자 주문 목록에 그대로 보인다.
