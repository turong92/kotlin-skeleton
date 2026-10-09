# payment-toss

`payment` 의 제공자로 토스페이먼츠(**결제창 + API 개별 연동 키** `test_ck_…`/`test_sk_…`)를 구현한다. 외부 호출은 `platform` 의 `ExternalHttpClient` 를 쓴다.
한 빈(`TossPaymentProvider`)이 능력을 모두 맡아 라우터에는 결제사가 하나로 보인다: 승인 · 취소 · 환불 · 조회 · 결제창 값 · 웹훅 해석.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment-toss"))` |
| 함께 오는 모듈 | `platform`, `payment` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.payment-toss` — [docs/config/modules/payment-toss.yml](../config/modules/payment-toss.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 로 켠다. 켜도 시크릿 키가 비면 제공자 빈이 없다 — 보호 환경(`skeleton.env=stage\|prod`)에서는 기동 실패, 그 밖은 기동은 되고 배포 가드가 경고한다. |
| 부팅에 필요한 것 | 없음. 켤 때 시크릿 키와 인터넷이 필요하다. 결제창을 열려면 클라이언트 키(`client-key`)도 필요하다. |
| 교체 지점 | `TossPaymentProvider` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/payment` |
| 테스트 | `modules/payment-toss/src/test` |

## 하는 일 (스펙 `docs/superpowers/specs/2026-10-08-payment-ledger-design.md` §6.6)

| 능력 | 토스 | 메모 |
|---|---|---|
| 승인 `PaymentConfirmGateway` | `POST /v1/payments/confirm` | `Idempotency-Key` = 호출자가 준 키, 없으면 주문 번호(`merchantReferenceId`). 결과는 **응답의 값만** 쓴다 — 결제 키 · 총액 · 통화 중 하나라도 없으면 요청 값으로 메우지 않고 읽을 수 없는 응답(모름). 카드(`card`) · 승인 시각(`paidAt`)도 응답에서 꺼내 싣는다 |
| 환불 `PaymentRefundGateway` | `POST /v1/payments/{paymentKey}/cancel` | `Idempotency-Key` = 요청의 멱등 키(**없으면 헤더 없이 나간다** — 거절하지 않는다. 기존 `PaymentService` 경로 호환이고, 장부는 늘 키를 싣는다. 키 없이 다시 보내면 토스는 새 요청으로 처리해 두 번 환불될 수 있다). **응답 상태를 본다**: `CANCELED`/`PARTIAL_CANCELED` 이고 **이 호출의 취소가 `DONE` 으로 확인되면** `REFUNDED`, 진행 중 → `PENDING`, `ABORTED`/`EXPIRED` → `FAILED`, 그 밖(`DONE` 포함) → `UNKNOWN`. **이 호출의 취소 = `cancels[]` 중 거래 키가 결제의 `lastTransactionKey` 와 같은 항목**(위치로 추측하지 않는다 — 못 찾으면 `UNKNOWN`, 거래 키는 비우고 금액은 요청한 값). 결과의 `remaining` = `balanceAmount`, `providerOperationId` = 그 취소의 거래 키, `amount` = 그 취소 금액. 일부 환불이면 `attributes["partial"]="true"` |
| 조회 `PaymentLookupGateway` | `GET /v1/payments/{paymentKey}` · `GET /v1/payments/orders/{orderId}` | `404 NOT_FOUND_PAYMENT` → 결제 없음(null). **조회의 그 밖 오류는 늘 모름**이다(4xx 여도 확정 거절로 표시하지 않는다 — 조회는 돈이 움직였는지 말하지 않는다). 응답이 물은 결제 키 · 주문 번호와 다르면 읽을 수 없는 응답. 상태와 환불 목록이 어긋나도(환불 상태인데 `DONE` 취소가 없다 · 결제됨인데 `DONE` 취소가 있다) 마찬가지. 통화가 없으면 설정에서 짐작하지 않는다. 조회는 요청 단위 제한 `lookup-timeout`(10초) |
| 결제창 `PaymentCheckoutGateway` | — | `kind=TOSS_PAYMENT_WINDOW`, 클라이언트 키 · `testMode` · 주문 값. 구매자 이름 · 이메일 · 전화번호는 넘기지 않는다. 비회원 구매자 키는 SDK 상수라 서버가 문자열로 주지 않는다. **클라이언트 키는 `ck` 모양이고 시크릿 키와 같은 모드일 때만** 내려간다(그 밖은 null) |
| 웹훅 해석 `PaymentWebhookGateway` | `PAYMENT_STATUS_CHANGED` | `signed=false`(주소의 비밀 조각이 필수). **결제 키 · 주문 번호만** 꺼낸다 — 본문의 상태 · 금액은 읽지 않는다. 이벤트 키 = 헤더 `tosspayments-webhook-transmission-id`, 없으면 본문 sha256 |
| 자동결제 `PaymentBillingGateway` | 발급 `POST /v1/billing/authorizations/issue` · 승인 `POST /v1/billing/{billingKey}` · 삭제 `DELETE /v1/billing/{billingKey}` | 구독용(`payment` 의 구독 서비스가 쓴다). 카드 등록 창 값 `kind=TOSS_BILLING_AUTH`(클라이언트 키는 결제창과 같은 규칙). 승인은 `Idempotency-Key` = 주문 번호이고 응답을 결제 승인과 **같은 규칙**으로 읽는다. 삭제는 이미 없는 키(`INVALID_BILL_KEY_REQUEST` · `NOT_FOUND_BILLING`)를 정상으로 읽는다. 빌링키는 응답 · 로그 · `toString` 에 나오지 않는다. 진짜 토스로 잰 것은 [real-provider-setup §11.7](../real-provider-setup.md) |
| 모드 | 시크릿 키 접두사 `test_` → TEST · `live_` → LIVE | 주문 줄에 박고 가드가 본다 |

상태 대응: `READY` · `IN_PROGRESS` · `WAITING_FOR_DEPOSIT` → 진행 중 / `DONE` → 받았다 / `PARTIAL_CANCELED` → 일부 환불 / `CANCELED` → 전액 환불 / `ABORTED` → 실패 / `EXPIRED` → 만료.
**모르는 상태는 추측하지 않고 읽을 수 없는 응답으로 던진다**(모름으로 분류된다).

결제 수단(`method`)은 대응표로 중립 종류 코드(`CARD` · `EASY_PAY` · `VIRTUAL_ACCOUNT` · `TRANSFER` · `MOBILE_PHONE` …)로 옮겨 장부에 간다 — 한글 라벨(「카드」 · 「간편결제」 …)과 영문 값을 둘 다 받고, 표에 없는 값은 받은 그대로 넘기지 않고 비운다(장부는 영문으로 시작하는 `[A-Z][A-Z0-9_]` 코드만 받는다 — 숫자만인 값은 버린다). 토스가 실제로 보내는 값은 아직 못 쟀다([real-provider-setup §11.6](../real-provider-setup.md) 의 못 잰 가정).

## 실패 분류 — 확정 / 모름

`PaymentFailures.outcomeOf` 가 읽는 값은 오류 변환기가 정한다. **허용 목록 방식**: 아래 코드(4xx)만 `definitive=true` = **확정**(토스가 거절했고 돈이 움직이지 않았다)이고, **그 밖은 전부 모름** — 다시 보내지 말고 조회한다. 토스가 새 코드를 더해도 모름이다.

- 타임아웃 · 네트워크 · 5xx · 429 · 해석할 수 없는 응답(깨진 JSON · 빈 응답 · 모르는 상태 → 코드 `UNREADABLE_RESPONSE`)
- 4xx 여도 앞선 요청이 적용됐을 수 있는 코드: `ALREADY_PROCESSED_PAYMENT`(승인 호출에서 받으면 돈이 이미 움직였다) · `ALREADY_PROCESSING_REQUEST` · `IDEMPOTENT_REQUEST_PROCESSING`(앞선 요청이 처리 중) · `ALREADY_CANCELED_PAYMENT` · `ALREADY_REFUND_PAYMENT`(환불 호출에서 받으면 이미 환불됐다) · `FORBIDDEN_CONSECUTIVE_REQUEST` · `PROVIDER_ERROR` · `CARD_PROCESSING_ERROR` 등

**확정 거절 허용 목록**(`TossPaymentErrorMapper.DEFINITIVE_CODES`; 근거: [토스페이먼츠 오류 코드 문서](https://docs.tosspayments.com/reference/error-codes)의 「결제 승인」 · 「결제 취소」 표 설명 문장, 2026-10-08):

| 묶음 | 코드 |
|---|---|
| 요청 · 인증 | `INVALID_REQUEST` · `INVALID_API_KEY` · `UNAUTHORIZED_KEY` · `INCORRECT_BASIC_AUTH_FORMAT` · `FORBIDDEN_REQUEST` |
| 없는 결제 | `NOT_FOUND_PAYMENT` · `NOT_FOUND_PAYMENT_SESSION` |
| 승인 거절 · 한도 | `REJECT_CARD_PAYMENT` · `REJECT_CARD_COMPANY` · `REJECT_ACCOUNT_PAYMENT` · `INVALID_REJECT_CARD` · `INVALID_STOPPED_CARD` · `INVALID_CARD_LOST_OR_STOLEN` · `INVALID_CARD_NUMBER` · `INVALID_CARD_EXPIRATION` · `EXCEED_MAX_AMOUNT` · `EXCEED_MAX_PAYMENT_AMOUNT` · `EXCEED_MAX_DAILY_PAYMENT_COUNT` · `EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT` · `BELOW_MINIMUM_AMOUNT` · `FDS_ERROR` |
| 자동결제(실측) | `NOT_FOUND_BILLING` · `INVALID_BILL_KEY_REQUEST` |
| 환불 불가(요청 자체의 성질) | `NOT_ALLOWED_PARTIAL_REFUND` · `NOT_ALLOWED_PARTIAL_REFUND_WAITING_DEPOSIT` · `EXCEED_MAX_REFUND_DUE` |

환불 호출에서 `NOT_CANCELABLE_AMOUNT` · `NOT_CANCELABLE_PAYMENT` · `EXCEED_CANCEL_LIMIT` 는 **확정이 아니다** — 앞선 환불이 잔액 · 상태 · 횟수를 이미 바꿔서 나올 수 있어서 모름으로 둔다(조회가 가린다). `REFUND_REJECTED` 도 사유 없는 코드라 확정에서 뺐다(헷갈리면 모름 — 확정 목록은 26개로 고정되어 있고 시험이 집합을 지킨다).

**승인 거절의 분류(`confirmDeclineCategory`)**: 확정 거절 코드를 `DECLINED` 응답의 `data.category` 로 옮긴다 — `REJECT_CARD_PAYMENT` · `REJECT_CARD_COMPANY` · `REJECT_ACCOUNT_PAYMENT` · `INVALID_REJECT_CARD` · `INVALID_STOPPED_CARD` · `INVALID_CARD_LOST_OR_STOLEN` · `INVALID_CARD_NUMBER` · `INVALID_CARD_EXPIRATION` · `FDS_ERROR` 등 카드 · 계좌 거절 → `CARD_DECLINED`, `EXCEED_MAX_AMOUNT` · `EXCEED_MAX_PAYMENT_AMOUNT` · `EXCEED_MAX_DAILY_PAYMENT_COUNT` · `EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT` 한도 → `LIMIT_OR_BALANCE`, 그 밖(요청 · 인증 오류, 모르는 코드) → `OTHER`. 일시 장애는 확정 거절이 아니라 모름이라 여기에 오지 않는다. 코드는 `data.providerCode`(참조 번호)로 따로 나가고 화면 문구는 분류로 고른다.

**키 거절**(401 계열 `INVALID_API_KEY` · `UNAUTHORIZED_KEY`)은 분류는 위대로 확정 실패 그대로이고, 거기에 더해 `PaymentProviderCredentialAlert` 로 주인 경보를 한 줄 낸다(`alert` 모듈 + 장부가 있을 때 `PAYMENT_PROVIDER_CREDENTIALS_REJECTED`, 결제사 id 만 싣고 한 시간에 한 번으로 접힌다) — 키가 폐기된 배포는 모든 결제가 고객에게 「거절」로만 보이기 때문이다. 조회의 같은 거절에도 낸다. `FORBIDDEN_REQUEST` 는 분류(확정 거절)만 그대로이고 경보는 내지 않는다 — 결제 키와 주문 번호가 최초 요청과 다를 때도 올 수 있다는 추정이 있어(실측 전) 로그인 사용자의 요청이 거짓 경보를 낼 수 있다.
코드를 더할 때는 문서의 설명이 "아무 일도 일어나지 않았다"를 말하는지 먼저 본다. `retryable` 은 "일시 오류일 수 있다"(429 · 5xx · `PROVIDER_ERROR` 등)는 원래 뜻이고 확정/모름과 무관하다.

## 개인정보 — 응답 본문을 싣지 않는다

- 응답은 모듈이 선언한 칸만 읽는다. 구매자 이름 · 이메일 · 전화 · 카드 상세는 선언하지 않았으므로 버려진다.
- 카드는 `card.issuerCode`(중립 대문자 영문 코드로 옮긴다, 표에 없으면 받은 코드 그대로) 와 `card.number` 의 **마지막 네 글자**(숫자 또는 `*`)만 남긴다.
- 오류는 **코드만** 예외에 담는다(`message` · `rawBody` 는 늘 null). 읽지 못한 응답은 원인 예외를 달지 않는다(디코더의 메시지가 본문 조각을 담을 수 있다).
- `TossPlantedValuesTest` 가 가짜 서버가 심은 이름 · 이메일 · 전화 · 전체 카드 번호가 결과 · 예외 · 로그에 없음을 본다.
- 서드파티의 DEBUG/TRACE 로그(스프링 코덱 · reactor-netty wire 로그)는 본문을 찍는다 — 운영에서 켜지 않는다.

## 키 가드 (`TossKeyGuard`)

켠 순간부터 배포 가드 하나(`payment-toss`)가 선다. 메시지에는 속성 · 환경변수 이름만 있고 키 값이나 접두사 뒤의 글자는 없다. 표 전체와 조합: `TossKeyGuardTest`.

| 상황 | 결과 |
|---|---|
| 켰는데 시크릿 키가 비었다 | 제공자 빈이 없다. **보호 환경(`stage`·`prod`)에서는 기동 실패**(비밀을 빠뜨린 배포가 정상으로 뜨지 않게 — 스펙 §9.3 의 경고에서 주인 결정으로 바꿨다), 그 밖의 환경(local · test · 프로필 없음)은 기동은 되고 경고만 |
| `skeleton.env=stage\|prod` 인데 `base-url` 이 토스 주소(`https://api.tosspayments.com`)가 아니다 | 경고(주소 값은 적지 않는다) — 로컬의 가짜 서버는 정상이라 보호 환경에서만 본다 |
| 클라이언트 키와 시크릿 키의 모드가 다르다(`test_` ↔ `live_`) | **기동 실패**(어느 환경이든) |
| 키 종류가 다르다(`ck`↔`gsk` · `gck`↔`sk`) · 결제위젯 키 묶음(`gck` · `gsk`) · 시크릿 키를 클라이언트 키 자리에 | **기동 실패** — 이 모듈은 결제창(API 개별 연동 키)만 연동한다 |
| `skeleton.env=local` 이거나 프로필 `local` · `test` 인데 실결제 키 | **기동 실패** |
| `skeleton.env=stage\|prod` 인데 테스트 키 | **기동 실패.** `allow-test-keys-in-protected-env=true` 면 경고 |
| 결제 장부(`PaymentLedgerStore` 빈)가 있는데 클라이언트 키가 비었다 | 보호 환경에서 기동 실패, 그 밖은 경고 |
| 장부 + 작업 워커(`skeleton.job-queue.worker-enabled`)가 꺼져 있다 — **환경과 무관** | 경고(시작 때 한 줄, 막지는 않는다) — 조회 · 지급 · 웹훅 처리가 돌지 않는다 |
| 장부가 있는데 `skeleton.http.clients.payment-toss.response-timeout` 이 10초보다 짧다 | 경고 |
| 장부가 있는데 `skeleton.payment.reconcile.first-delay` 가 `response-timeout` 보다 **길지 않다**(같거나 짧다) | **기동 실패**(어느 환경이든) — 조회 작업이 아직 진행 중인 승인과 겹치지 않게(스펙 §9.2). 장부가 없으면 보지 않는다 |
| 장부가 있는데 `skeleton.payment.refund.settle-delay` 가 `response-timeout` 보다 **길지 않다**(같거나 짧다) | **기동 실패**(어느 환경이든) — 환불 정산 작업이 아직 진행 중인 환불 요청을 「적용되지 않았다」로 닫아 이중 환불이 되지 않게. 장부가 없으면 보지 않는다 |

장부 유무는 `PaymentLedgerStore` 타입의 빈이 있는가로 가려낸다(타입 참조). 장부가 없는 조립에서는 장부 줄이 모두 꺼진다.

결제 키 · 주문 번호는 결제사를 부르기 전에 모양(영문 · 숫자 · `-` · `_`, 키 200자 · 주문 번호 64자까지)을 확인한다 — `/` · `..` · `?` · `#` 가 든 값이 다른 경로를 부르지 못한다. 틀리면 호출자 잘못이고 결제사는 부르지 않는다 — `400 COMMON.VALIDATION_FAILED`(`TossIdentifierRejectedException`; 별도의 `PAYMENT.*` 코드는 없다). 웹훅의 결제 키도 같은 규칙이다.
장부는 승인을 **선점하기 전에** 같은 규칙을 먼저 묻는다(`PaymentConfirmGateway.acceptsPaymentKey`, 토스는 이 모듈의 규칙 그대로) — 틀린 결제 키는 선점 없이 400(`providerPaymentId` 필드 오류)이고 주문은 `PENDING` 그대로, 결제사 호출도 조회 작업도 생기지 않는다.
선점 뒤에 이 예외가 나면 주문이 `CONFIRMING` 에 갇히므로 장부 경로에서는 선점 전 확인이 유일한 입구다.
**`acceptsPaymentKey` 는 주문 줄 락 안에서 불린다 — 문자열 검사만 하고 외부 호출 · I/O 를 하지 않는다**(락을 쥔 채 기다리면 그 주문의 승인 · 환불 · 웹훅 수신이 함께 기다린다).
**키 모양 가정이 틀리면**(진짜 토스의 결제 키가 위 규칙 밖이면) 모든 승인이 400 으로 거절될 뿐 아니라 **만료 작업도 죽는다** — 조회 응답의 결제 키도 같은 규칙으로 읽어서(규칙 밖이면 읽을 수 없는 응답 = 모름), 주문 번호로 묻는 만료 작업이 매번 실패해 재시도만 하다 죽는다. 그래서 이 가정은 아직 못 쟀다 — 결제 승인이 있어야 잰다([real-provider-setup §11.6](../real-provider-setup.md) 의 못 잰 가정, 아래 검증 기록).

## 응답 제한 시간

승인 · 환불의 제한 시간은 이 모듈의 키가 아니라 앱 yml 의 `skeleton.http.clients.payment-toss.response-timeout` 이다. 모듈은 플랫폼 기본값(5초)을 바꾸지 않는다 — 장부를 쓰는 앱은 올린다(샘플은 30초).
조회만 요청 단위(`lookup-timeout`)로 따로 정한다.

## 환경변수

| 값 | 이름 |
|---|---|
| 켜기 | `<P>_PAYMENT_TOSS_ENABLED=true` |
| 시크릿 키 | `<P>_PAYMENT_TOSS_SECRET_KEY` (워크벤치 · 샘플은 별칭 `TOSS_PAYMENTS_SECRET_KEY`도 받는다) |
| 클라이언트 키(비밀 아님) | `<P>_PAYMENT_TOSS_CLIENT_KEY` (별칭 `TOSS_PAYMENTS_CLIENT_KEY`) — 시크릿 키와 **같은 묶음**의 키 |

## 검증 기록

자동 시험은 가짜 토스 HTTP 서버(`modules/payment-toss/src/test/.../support/FakeTossServer.kt`)로만 돌고 진짜 토스를 부르지 않는다.
진짜 토스(테스트 시크릿 키)로 돈이 움직이지 않는 호출만 2026-10-09 에 재 봤다 — **재 본 것과 못 잰 가정은 [real-provider-setup §11.6](../real-provider-setup.md) 에 날짜와 함께 있다**(`scripts/check-real-providers.sh --toss-only`). 요약:

- **잰 것**: 없는 결제(주문 번호 · 결제 키)의 조회는 `404 NOT_FOUND_PAYMENT`, 없는 결제의 취소도 같은 코드(`Idempotency-Key` 헤더를 받는다), 틀린 키는 `401 UNAUTHORIZED_KEY`. 오류 응답의 추적 번호는 헤더 `x-tosspayments-trace-id` 로만 오고(본문에 없다) 이 이름을 `TOSS_TRACE_HEADERS` 에 더했다. 테스트 시크릿 키로 없는 결제의 취소에 `TossPayments-Test-Code: NOT_ALLOWED_PARTIAL_REFUND` 헤더를 붙이면 `403` 과 같은 코드가 돌아온다 — 토스는 테스트 코드를 결제 존재 확인보다 **먼저** 적용한다(결제 없이 거절 코드를 재현할 수 있다).
- **못 잰 것(결제 승인이 있어야 한다)**: 결제 키의 문자 집합(영문 · 숫자 · `-` · `_` — 틀리면 **모든 결제가 `400` 으로 거절된다**) · 웹훅 본문의 결제 키 · 주문 번호 위치와 전달 id 헤더 · 테스트 키로 웹훅이 오는지 · `cancels[]` 의 `cancelStatus` · `transactionKey` · `lastTransactionKey` · `balanceAmount` 의 성립 · 결제 수단 값과 카드사 코드 대응표(문화상품권류 3개는 추정) · 같은 멱등 키 재응답 본문 · 조회의 상점 단위 한도. 각각 틀리면 무엇이 신호인지는 §11.6 의 표에 있다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
