# 결제 장부 — 주문 · 단건 결제 · 환불 · 웹훅 수신함, 그리고 구독으로 자라는 자리 — 설계 초안

- 날짜: 2026-10-08
- 요청: 주인 sumin. 지휘 세션이 넘긴 범위 제안을 레포와 토스페이먼츠 공식 문서로 검증 · 반박한 결과다
- 기준 커밋: kotlin-skeleton `22a3ef6` · react-skeleton `b010116` (둘 다 main) · 구현 기반: kotlin `feat/email-required` `80bbee2` · react `feat/author-profiles` `8f1b0ab`
- 상태: **승인 — 1단계 구현 중(주인 결정 반영 2026-10-08).** 맨 끝 「정해진 것」이 결정이고, 본문은 그에 맞춰 고쳐 썼다(스펙 초안의 추천과 다른 곳은 나-5 · 나-6). §14 단계 순서로 구현한다
- 경로 줄임: `PM/` = `modules/payment/src/main/kotlin/dev/sumin/skeleton/payment/` · `PT/` = `modules/payment-toss/src/main/kotlin/dev/sumin/skeleton/payment/toss/` · `P/` = `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/` · `JQ/` = `modules/job-queue-jdbc/src/main/kotlin/dev/sumin/skeleton/jobqueue/jdbc/` · `R:` = react-skeleton 레포
- 설계 참고: 다른 서비스의 결제 운영 경험에서 온 교훈(구조와 사고 사례만 — 코드는 옮기지 않았다). 본문에서 「운영 교훈」이라고 적은 곳이 그것이다

## 1. 목적과 결정

지금 `payment` 는 결제사를 부르는 얇은 파사드다 — 표가 없고 HTTP 가 없다(`docs/modules/payment.md` 「왜 HTTP 엔드포인트가 없나」). 그래서 "누가 무엇을 얼마에 샀는가"가 어디에도 남지 않고, 금액 검증 · 중복 방지 · 환불 · 웹훅을 앱이 매번 처음부터 짠다. 이 설계는 그 빈자리를 **결제 장부**로 채운다: 주문 한 줄이 결제 한 번이고, 그 줄의 상태가 돈의 사실이다.

최종 목표(주인): "도메인 바꿔서 딱 켜기만 해도 서비스 구색 하나 나와있는거." → 샘플 앱에서 상품 → 결제 → 결과 → 내 결제 내역 → 환불까지 눌러 볼 수 있어야 한다.

| 항목 | 결정 | 근거 |
|---|---|---|
| 표를 결제사별로 나누나 | **공통은 공통 표, 결제사 고유는 결제사 모듈** | 주인의 질문("범용이 나아? 따로가 나아? 공통은 공통 특별한건 특별하게?")에 지휘 세션이 이렇게 답했고 이의가 없었다. 장부 표에는 결제사 이름이 든 열이 없다(§5) |
| 결제는 누구에게 묶이나 | **계정.** 작성자 프로필(여러 닉네임)과 무관하다 | 프로필은 공개 신원이고 결제는 사적 사실이다. 장부의 어떤 값도 프로필 id 를 담지 않고, 어떤 공개 응답에도 결제 사실이 나가지 않는다 |
| 금액의 출처 | **서버뿐** — 앱이 구현하는 `PaymentCatalog` 가 값을 매기고 주문 줄에 박는다 | 브라우저가 돌려보내는 `amount` 는 주소에서 온 값이다. `payment.md` 가 HTTP 를 열지 않은 이유가 이것이었다. 샘플은 **서버가 가진 금액 목록**(1,000 · 3,000 · 5,000원)에서만 고르고, 브라우저가 임의 금액을 정하지 못한다 |
| 카드 정보 | 결제사 모듈이 준 **카드사 코드 + 가려진 번호의 끝 네 글자**까지만 주문 줄에 저장하고, 계정이 지워지면 그 두 칸을 NULL 로 지운다 | 내역에 「현대카드 ····1234」를 보이기 위해서다. 그 이상(전체 번호 · 유효기간 · 소유자명 · 할부)은 어떤 칸에도 두지 않는다(§5.1 · §8.2) |
| 샘플 상품 | **「응원하기(후원) 1회」** — 1,000 · 3,000 · 5,000원 | 결제 → 결과 → 내 응원 내역 → 환불의 여정을 가장 작은 도메인(응원 기록 표 하나)으로 보인다 |
| 결제사가 "모름"일 때 | **다시 보내지 않고 물어본다** — 결제사의 조회 API 가 진실이다 | 운영 교훈: 환불 타임아웃 뒤의 이중 환불, 청구 타임아웃 뒤 "우리는 실패 · 결제사는 성공" |
| 웹훅 | 받은 것을 믿지 않는다. **신호로만 쓰고 조회로 확정한다** | 토스의 결제 웹훅에는 서명이 없다(§17). 서명이 있는 결제사도 같은 경로를 탄다 |
| 토스 연동 방식 | **결제창(API 개별 연동 키 `test_ck_` · `test_sk_`)** | 주인이 받은 키가 `test_sk_` 이고, 2단계의 자동결제(빌링)도 같은 키 묶음을 쓴다. 결제위젯은 다른 키 묶음(`test_gck_` · `test_gsk_`)이다 (§17) |
| 스켈레톤 기본 | `payment-jdbc` 를 끼우지 않으면 **지금과 같다**(스테이트리스 파사드, 표 없음, HTTP 없음) | 모듈 기본값은 중립, 조립식 |
| 사업자등록 | 없다 → 실결제 키를 받을 수 없다. 1단계는 **테스트 키로 끝까지 검증**한다 | 테스트 키는 전자결제 신청 전에도 쓸 수 있다(§17). 테스트 키가 실수로 실서비스처럼 보이지 않게 하는 장치는 §9 |

## 2. 범위와 비목표

세 단계로 나눈다. 이 문서가 구현을 지시하는 것은 **1단계뿐**이고, 2 · 3단계는 1단계의 표가 그대로 자란다는 것을 보이는 데까지다(§12).

**1단계 (구현 대상)**
- `payment`: 결제사 능력 인터페이스(기능별), 장부 서비스(주문 · 환불 · 웹훅 수신함), 앱이 끼어드는 자리(`PaymentCatalog` · `PaymentOrderListener`), HTTP(사용자 · 운영자 · 웹훅), 계정 탈퇴 고리(카드 두 칸 지우기 포함).
- 새 모듈 `payment-jdbc`: 표 4개(두 방언, 새 V 파일), 저장소, 지연 작업을 `job-queue-jdbc` 에 싣는 어댑터.
- `payment-toss`: 결제 조회(카드사 코드 · 끝 네 글자 포함), 환불 결과(남은 금액 · 거래 키), 웹훅 해석, 클라이언트 키, 키 가드.
- `apps/sample`: 데모 상품 하나(「응원하기(후원) 1회」 — 서버가 가진 금액 목록 1,000 · 3,000 · 5,000원, 정해진 것 6)와 그 기록 · 회수(`onPaid` 가 응원 기록을 남기고 `onRefunded` 가 그 기록을 환불됨으로 바꾼다).
- react: `@skeleton/payment` 에 장부 API · 훅 · 결제창 열기 · 화면 부품, `apps/sample` 에 응원하기 → 결제창 → 결과 → 내 응원 내역 → 한 건 · 환불 요청, 운영자 환불 화면.

**2단계 (설계까지 — §12.1)**: 구독. 결제 수단(빌링키) · 요금제 · 정기 결제 실행기(우리가 청구) / 결제사 관리 구독 · 유예 · 연체 · 해지 · 플랜 변경.

**3단계 (자리만 — §12.2)**: 해외 결제사 모듈(판매 대행 방식), 통화 · 세금.

**비목표** (만들지 않는다)
- 쿠폰 · 할인 · 포인트 · 적립금 — 주문 줄의 금액은 **최종 청구액 하나**다. 할인 내역이 필요한 앱은 자기 표에 주문 id 로 둔다.
- 장바구니 · 여러 상품 한 번에 결제 · 배송 · 재고.
- 가상계좌 · 계좌이체 · 현금영수증 · 에스크로 — 1단계는 카드 · 간편결제(결제창이 한 화면에서 처리하는 것)만. 가상계좌는 입금 대기 상태와 환불 계좌 수집(개인정보)이 따라온다.
- 영수증 보관 — 영수증 주소는 저장하지 않는다. 필요해지면 결제사에서 그때 받아 넘긴다.
- 카드 번호 전체 · 유효기간 · 소유자명 · 할부의 저장 — 카드에 관해 남기는 것은 카드사 코드와 끝 네 글자뿐이다(§5.1).
- 정산(매출 집계 · 수수료 · 세금계산서) · 매출 대시보드.
- 결제 기록 보존 기간이 지난 줄의 자동 삭제(§8 — 기간은 앱이 정하는 정책이고 1단계에는 실행기가 없다).
- `payment-stripe` 를 장부에 올리기 — 한국 사업자는 가맹할 수 없다. 모듈은 지금 그대로 둔다(정해진 것 9).
- `account` 없이 쓰는 앱을 위한 별도 권한 체계 — 호출자는 다른 모듈처럼 `Authentication.name` 이다.

## 3. 사고 모델 — 무엇이 잘못될 수 있고 무엇이 막는가

지키는 것: **① 돈이 나갔으면 장부가 안다 ② 장부가 「결제됨」이면 돈이 실제로 들어왔다 ③ 같은 돈을 두 번 받거나 두 번 돌려주지 않는다 ④ 산 것은 정확히 한 번 지급된다.** 번호는 §13 시험과 §14 작업이 가리킨다. "지금 상태"는 기준 커밋을 읽어 확인했다.

| # | 사고 | 지금 상태 | 방어 | 이것을 잡는 시험 (§13) |
|---|---|---|---|---|
| 1 | **이중 결제 — 같은 주문을 두 번 승인** (두 탭 · 재시도 · 뒤로 가기) | 장부가 없다. `PaymentService.confirm` 은 부를 때마다 결제사로 간다(`PM/PaymentService.kt:11-34`) | 승인은 **선점 → 호출 → 확정** 세 걸음(§6.2). 선점은 주문 줄 락 안에서 `PENDING → CONFIRMING` 한 번만 성공한다. 결제사에는 주문마다 같은 멱등 키. `(provider, provider_payment_id)` 유니크 — 한 결제 키가 두 주문에 붙지 못한다 | `OrderConfirmConcurrencyDbTest` `sixteen simultaneous confirms of one order call the provider exactly once` (두 방언) |
| 2 | **이중 결제 — 주문이 두 개 생김** (「결제하기」 두 번 누름) | — | `POST /orders` 는 `Idempotency-Key` 필수이고, 그 값이 줄의 `dedupe_key`(유니크)가 된다 — 메모리 `IdempotencyStore`(인스턴스별)에 기대지 않는다 | `OrderCreateDbTest` `the same key creates one order across two instances` (두 방언) |
| 3 | **금액 변조** — 주소의 `amount` 를 고친다, 다른 주문의 결제 키를 끼운다 | 요청의 `amount` 를 그대로 결제사에 넘긴다(`PT/TossPaymentProvider.kt:115-122`) | 금액은 주문 줄의 값만 쓴다. 요청의 `amount` 는 **비교만** 하고(다르면 400, 결제사를 부르지 않는다) 승인 응답의 총액도 다시 비교한다. 다르면 「결제됨」으로 올리지 않고 확인 필요로 남긴다. 샘플은 금액이 서버의 목록에서만 나오고, 목록에 없는 `itemRef` 는 `ITEM_NOT_PURCHASABLE` | `OrderConfirmTest` `a tampered amount never reaches the provider` · `a provider total that differs from the order is not marked paid` |
| 4 | **남의 주문** — 다른 계정의 주문 id 로 승인 · 조회 · 환불 요청 | — | 모든 사용자 경로는 `account_id = 호출자` 조건으로 읽는다. 남의 것 · 없는 것은 같은 `404 PAYMENT.ORDER_NOT_FOUND` | `PaymentWebTest` `another account's order is indistinguishable from a missing one` |
| 5 | **승인 타임아웃 뒤 "모름"** — 우리는 응답을 못 받았는데 결제사는 승인했다 | 예외가 그대로 나가고 아무 기록이 없다. 5xx · 타임아웃 · 네트워크 오류가 구별 없이 `retryable` 한 칸이다(`PT/TossPaymentErrorMapper.kt:31`) | 실패를 **확정**(결제사가 거절 코드를 말했다)과 **모름**(타임아웃 · 5xx · 네트워크)으로 나눈다. 모름이면 주문은 `CONFIRMING` 에 머물고 응답은 `202`, 조회 작업이 결제사가 **끝 상태**를 말할 때까지 묻는다. 승인을 다시 보내지 않는다 | `OrderReconcileTest` `a confirm that timed out is settled as paid when the provider says DONE` · `…as failed when the provider says ABORTED` · `…stays confirming while the provider is still in progress` (가짜 결제사가 응답을 버린다) |
| 6 | **결제는 됐는데 지급이 안 됨** — 「결제됨」 뒤 앱의 지급 코드가 터진다 | — | 「결제됨」 커밋과 지급을 **떼어 놓는다**: 확정 트랜잭션이 지급 작업을 같은 트랜잭션에서 큐에 넣고, 지급은 주문 줄 락 안에서 `fulfilled_at` 이 비었을 때만 한 번 돈다. 실패는 재시도되고 다 쓰면 죽은 작업 경보 | `FulfillmentTest` `a listener that throws does not undo PAID and is retried` · `two workers fulfil one order exactly once` (두 방언) |
| 7 | **웹훅 위조** — 누구나 주소를 알면 `DONE` 을 보낼 수 있다 | 웹훅 수신이 없다 | 웹훅 본문의 상태를 **쓰지 않는다** — 결제 키 · 주문 번호만 꺼내 결제사에 조회하고, 조회 결과로 전이한다. 모르는 주문은 저장하지 않고 200. 서명 없는 결제사는 주소에 비밀 조각(`path-token`)을 요구한다. 요청 수 한도 | `WebhookInboxTest` `a forged DONE for an unpaid order changes nothing` · `an unknown order id is acknowledged and not stored` · `a wrong path token is 404` |
| 8 | **웹훅 중복** — 같은 이벤트가 여러 번 온다(재전송 최대 7회, §17) | — | `(provider, event_key)` 유니크로 한 줄. 처리 자체가 "조회해서 맞춘다"라 몇 번 돌아도 결과가 같다 | `WebhookInboxDbTest` `the same delivery sixteen times stores one row` (두 방언) · `WebhookInboxTest` `processing twice changes nothing` |
| 9 | **웹훅 순서 뒤바뀜** — 취소가 승인보다 먼저, 실패가 성공보다 늦게 | — | 순서를 믿지 않는다(위 7 과 같은 이유로 자동 해결 — 늘 지금 상태를 조회한다). **「결제됨」을 늦은 실패 신호로 되돌리지 않는다.** (결제사가 먼저 만드는 자원 — 2 · 3단계의 구독 청구 — 은 추측하지 않고 재시도 가능한 실패로 둔다, §6.5) | `WebhookInboxTest` `a late failure after success keeps the order paid` · `a cancel that arrives before the confirm settles ends as refunded` |
| 10 | **웹훅 유실** — 결제사가 못 보냈거나 우리가 죽어 있었다 | — | 웹훅은 지름길일 뿐이다. `CONFIRMING` · 열린 환불 · 오래된 `PENDING` 은 웹훅 없이도 조회 작업이 끝낸다. 받은 뒤 처리가 실패한 줄은 큐가 재시도한다 | `OrderReconcileTest` 전부(웹훅 없이 돈다) · `WebhookInboxTest` `a delivery whose handler failed is retried and then succeeds` |
| 11 | **재전송이 재시도 대기에 삼켜짐** — 원인을 고치고 결제사에서 다시 보냈는데 대기 시간 때문에 조용히 무시된다 (운영 교훈) | — | 이미 있는 줄에 같은 이벤트가 **다시 도착하면** 그 줄이 끝나지 않은 한 대기 · 시도 예산을 건너뛰고 새 작업을 바로 넣는다 | `WebhookInboxTest` `a redelivery of a dead event runs immediately` |
| 12 | **환불 타임아웃 → 이중 환불** | 환불은 부를 때마다 결제사로 간다. 응답 상태를 보지 않고 `REFUNDED` 로 돌려준다(`PT/TossPaymentProvider.kt:74-91`) | 환불 줄을 먼저 `PENDING` 으로 만들고(주문당 열린 환불은 하나 — 유니크 열) 결제사에는 환불 줄 id 를 멱등 키로 보낸다. 모름이면 **다시 보내지 않고** 결제사의 누적 환불액을 조회해 맞춘다(§6.4) | `RefundReconcileTest` `a refund that timed out but was applied is settled once` · `…that was not applied is failed and the order is refundable again` · `RefundConcurrencyDbTest` `two operators refunding one order: one wins` (두 방언) |
| 13 | **환불 초과** — 부분 환불 합이 결제액을 넘는다 | 검사 없음 | 주문 줄 락 안에서 `amount - refunded_amount - 열린 환불` 과 비교. DB 에 `check (refunded_amount <= amount)` | `RefundTest` `the sum of partial refunds never exceeds the paid amount` · DB 제약 시험 |
| 14 | **결제사 관리 화면에서 직접 취소** — 장부가 모르는 환불 | — | 웹훅 또는 운영자의 「결제사와 맞추기」가 조회 → 결제사의 취소 목록과 장부의 환불 줄을 맞춘다. 모르는 취소는 `origin=PROVIDER` 줄로 들어온다 | `RefundSyncTest` `a cancel made in the provider console appears as one refund row - also after a second sync` |
| 15 | **결제창 이탈** — 창을 닫았다 · 인증만 하고 돌아오지 않았다 | — | 토스는 인증 뒤 10분 안에 승인하지 않으면 만료된다(§17) — 돈이 나가지 않는다. `PENDING` 은 `checkout-ttl` 뒤 만료 작업이 결제사에 **한 번 묻고** 닫는다(결제가 없으면 `EXPIRED`) | `OrderExpiryTest` `an abandoned order expires after the provider says there is no payment` |
| 16 | **이탈 뒤 지연 결제** — 닫은 주문에 나중에 돈이 들어온다 (결제사가 결제를 끝내는 방식 · 승인 응답 유실) | — | `FAILED` · `EXPIRED` · `CANCELED` → `PAID` 는 **결제사 조회 증거로만** 열린다. 돈이 들어왔는데 장부가 닫혀 있는 것이 가장 나쁜 상태다 (운영 교훈: "돈만 빠지고 아무것도 반영되지 않는다") | `OrderReconcileTest` `an expired order is reopened to paid by provider evidence` · `…never by a client call` |
| 17 | **테스트 키 · 실결제 키 혼용** | 키 종류를 보지 않는다 | ① 클라이언트 키와 시크릿 키의 종류 · 모드가 어긋나면 기동 실패 ② 로컬에서 실결제 키는 기동 실패, 보호 환경에서 테스트 키는 명시적 허락 없이는 기동 실패(§9) ③ 주문 줄에 만든 때의 모드를 박고, 지금 키의 모드와 다르면 승인 · 환불을 거절한다 ④ 화면에 「테스트 결제」 표시 | `TossKeyGuardTest` (조합표 전부) · `OrderConfirmTest` `an order created in test mode is refused under a live key` |
| 18 | **동시 요청** — 승인과 만료, 환불과 환불, 웹훅과 승인 응답이 겹친다 | — | 모든 전이는 주문 줄 `select … for update` 뒤 상태를 **다시 읽고** 판단한다. 외부 호출은 락 밖. Redis 락은 쓰지 않는다(§6.1) | `…ConcurrencyDbTest` 묶음 (두 방언): 승인 ↔ 만료 · 승인 응답 ↔ 웹훅 · 환불 ↔ 환불 · 지급 ↔ 지급 |
| 19 | **결제사 응답 원문 속 개인정보** — 카드 번호 일부 · 구매자 이름 · 이메일 · 전화번호가 DB · 로그에 눌러앉는다 | 오류 본문 2,048자를 예외에 싣는다(`PT/TossPaymentErrorMapper.kt:32`) | 장부는 **원문을 저장하지 않는다.** 꺼내 쓰는 칸은 허용 목록(상태 · 금액 · 통화 · 수단 종류 · 카드사 코드 · 카드 번호의 끝 네 글자 · 거래 키 · 시각)뿐이고, 카드 번호는 결제사가 가려서 준 값의 **마지막 네 글자**(숫자 또는 `*`)만 받고 앞부분은 버린다. 실패 사유는 결제사 **코드**와 우리가 쓴 문장만 남긴다 — 임의 예외 메시지를 DB 에 쓰지 않는다 (운영 교훈) | `PlantedPiiScanDbTest` — 가짜 결제사가 응답 · 웹훅에 심은 이름 · 이메일 · 전화 · **전체 카드 번호**가 `payment_*` 의 어느 열에도 없고 `card_last4` 는 4자 이하이며 숫자 · `*` 뿐이다 (두 방언) |
| 20 | **탈퇴한 계정의 결제 기록** | — | 줄은 남는다. 줄의 개인정보는 `card_issuer` · `card_last4` 둘뿐이고 계정이 지워지면 그 계정의 모든 주문 줄에서 NULL 로 지운다(§8). 탈퇴 뒤에도 운영자가 주문 번호로 환불할 수 있다 | `PaymentErasureDbTest` `after erasure the orders remain and are refundable` · `…clears the card issuer and last four digits on every order of the account, and again harmlessly` (두 방언) · 샘플의 전체 표 스캔(`AccountJourneyIntegrationTest`)에 결제 줄 추가 — 탈퇴 뒤 두 열이 비어 있음을 본다 |

## 4. 구조 — 모듈 경계와 SPI

```
platform   AccountErasureListener · DeployGuard · ExternalHttpClient · PublicEndpointContributor (모두 있음)
   ▲
 payment ── 결제사 능력 인터페이스 · 라우터 · 장부 서비스 · 포트(저장소 · 작업) · HTTP · 웹훅 수신함 · 탈퇴 고리
   ▲    ▲
   │    └── payment-toss ── 승인 · 환불 · 조회 · 웹훅 해석 · 클라이언트 키 · 키 가드
   │
 payment-jdbc ── 표 4개 · 저장소 · 작업 어댑터 ──implementation──▶ persistence-jdbc · job-queue-jdbc

 앱 ── PaymentCatalog(무엇을 얼마에) · PaymentOrderListener(지급 · 회수) 를 구현한다
```

`payment` 는 `payment-jdbc` 도 `job-queue-jdbc` 도 `account` 도 모른다(선택 의존은 `alert` 하나 — §4.4). `payment-toss` 는 장부를 모른다(능력만 구현한다). 결제사 모듈끼리는 서로를 모른다.

### 4.1 결제사 능력 — 기능별로 쪼갠다

결제사마다 할 수 있는 일이 다르다: 토스는 "브라우저가 받아 온 결제 키를 서버가 승인"하고, 판매 대행 방식 결제사는 "결제사 화면에서 결제가 끝나고 웹훅으로 알린다". 지금의 `PaymentProvider`(`PM/PaymentContracts.kt:94-106`)는 승인 · 취소 · 환불을 한 인터페이스에 묶어 이 차이를 담지 못한다. 기능 하나에 인터페이스 하나로 나눈다(운영 교훈에서 가져온 것).

```kotlin
/** 라우터가 아는 공통 뿌리 */
interface PaymentGateway {
    val providerId: String
    val supportedCurrencies: Set<String> get() = emptySet()
    val supportedCountries: Set<String> get() = emptySet()
    /** 지금 끼운 키가 테스트용인가 실결제용인가 — 주문 줄에 박고 가드가 본다 */
    val mode: PaymentMode get() = PaymentMode.UNKNOWN          // TEST | LIVE | UNKNOWN
}

/** 브라우저가 받아 온 결제 키를 서버가 승인한다 (토스) */
interface PaymentConfirmGateway : PaymentGateway { fun confirm(request: PaymentConfirmRequest): PaymentOperationResult }

/** 전액 · 부분 환불. 결과의 remaining 은 환불 뒤 결제사에 남은 금액 */
interface PaymentRefundGateway : PaymentGateway { fun refund(request: PaymentRefundRequest): PaymentOperationResult }

/** 결제사가 아는 지금 상태 — 장부의 진실 확인. 결제가 없으면 null */
interface PaymentLookupGateway : PaymentGateway { fun lookup(query: PaymentLookupQuery): PaymentSnapshot? }

/** 결제창을 여는 데 프론트가 필요한 것 (공개해도 되는 값만) */
interface PaymentCheckoutGateway : PaymentGateway { fun checkout(order: CheckoutOrder): CheckoutInstruction }

/** 웹훅 한 건을 진위 확인하고 "어느 자원의 어떤 일"인지만 꺼낸다. 진위가 틀리면 던진다 */
interface PaymentWebhookGateway : PaymentGateway {
    val signed: Boolean                                        // false 면 주소의 비밀 조각이 필수다 (§6.5)
    fun parse(delivery: WebhookDelivery): WebhookEnvelope
}

data class PaymentLookupQuery(val providerPaymentId: String?, val orderId: String?)       // 둘 중 하나는 있어야 한다
data class PaymentSnapshot(
    val providerPaymentId: String, val orderId: String?, val status: ProviderPaymentStatus,  // IN_PROGRESS | PAID | PARTIALLY_REFUNDED | REFUNDED | FAILED | EXPIRED
    val total: PaymentAmount, val remaining: PaymentAmount, val method: String?, val paidAt: Instant?,
    val refunds: List<ProviderRefund>,                         // (providerRefundId, amount, at) — 결제사가 아는 취소 전부
    val card: PaymentCard? = null,                             // 카드 결제일 때만. 결제사 모듈이 코드로 옮기고 끝 네 글자만 남겨 넘긴다
)
/** issuer: 결제사 중립의 대문자 영문 카드사 코드(예 HYUNDAI) — 모듈의 대응표에 없으면 결제사가 준 코드 그대로. last4: 가려진 번호의 마지막 네 글자(숫자 또는 `*` 만) */
data class PaymentCard(val issuer: String?, val last4: String?)
data class WebhookEnvelope(
    val eventKey: String, val eventType: String, val resourceType: String, val resourceId: String,
    val orderId: String? = null,
    val storedPayload: String? = null,                         // 처리에 원문이 꼭 필요한 결제사만. 토스는 null (§5.4)
)
```

**기존 계약과의 호환** — `PaymentProvider` 를 지우지 않고 위 인터페이스의 합으로 만든다.

```kotlin
interface PaymentProvider : PaymentConfirmGateway, PaymentRefundGateway {   // 멤버 시그니처는 그대로다
    fun cancel(request: PaymentCancelRequest): PaymentOperationResult
}
```

- 지금 구현(`TossPaymentProvider` · `StripePaymentProvider` · 앱의 구현)은 이미 모든 멤버에 `override` 를 달고 있어 **고치지 않고 컴파일된다.** `PaymentService` · `PaymentProviderRouter.resolve` · `PaymentFailureHandler` · 워크벤치 컨트롤러 · `@skeleton/payment` 의 기존 타입은 그대로다.
- `PaymentProviderRouter` 는 `List<PaymentGateway>` 를 받도록 넓히고 `gateway(providerId, 능력 타입)` 을 더한다. 그 결제사가 그 능력을 구현하지 않으면 `PAYMENT.CAPABILITY_UNSUPPORTED`. 같은 `providerId` 가 둘이면 지금처럼 기동 실패.
- `PaymentOperationResult` 에 기본값이 있는 칸 하나를 더한다: `remaining: PaymentAmount? = null`. 환불 거래 키는 이미 있는 `providerOperationId` 를 쓴다.
- 실패 분류는 한 곳: `PaymentFailures.outcomeOf(exception): DEFINITIVE | UNKNOWN`. **허용 목록 방식이다**: 결제사 모듈이 "돈이 움직이지 않았다고 결제사가 말했다"고 **명시한**(`PaymentProviderError.definitive = true`, 기본 false) 4xx 거절만 확정이고, 나머지 — `ExternalHttpTimeoutException` · `ExternalHttpNetworkException` · 5xx · 응답을 해석하지 못함 · 표시 없는 4xx · 모르는 예외 — 는 전부 모름이다. 처음 초안은 "4xx + 코드 + `retryable=false` 이면 확정"이라는 **거부 목록**이었다: 앞선 요청이 적용됐을 수 있는 4xx 코드(토스의 `ALREADY_PROCESSED_PAYMENT` · `ALREADY_CANCELED_PAYMENT` · `IDEMPOTENT_REQUEST_PROCESSING` — §17)를 모듈이 빠짐없이 `retryable` 로 표시해야 했고, 빠뜨리면 「우리는 실패 · 결제사는 성공」이 남았다. 허용 목록은 표시를 빠뜨리면 모름으로 기운다. **헷갈리면 모름이다** — 모름은 조회 한 번이 더 들 뿐이고, 확정으로 잘못 읽으면 돈이 어긋난다. 결제사 모듈의 의무는 `PaymentProviderError.definitive` 의 KDoc 에 있다. `retryable` 은 기존 뜻("일시 오류일 수 있다")대로 두고 분류에 쓰지 않는다.
- **`ExternalHttpClient` 는 연결이 끊겨도 POST 를 다시 보내지 않는다 — 실측했다**(2026-10-08, Reactor Netty 1.3.7, `ExternalHttpClientResendTest`). 날 소켓 서버가 요청을 읽고 응답 없이 끊을 때 서버가 읽은 요청 수: 새 연결에서 FIN 으로 끊기 **1건** · 새 연결에서 RST 로 끊기 **1건** · 이미 쓴 연결(재사용)에서 끊기 **1건**(새 연결 0개). 이전 초안의 근거("`P/http/` 에 재시도 코드가 없다")는 코드 읽기였고 `HttpClient.create()` 에 `disableRetry(true)` 가 없어서 Reactor Netty 의 자체 재전송이 걱정이었으나 위 세 경우에는 일어나지 않았다. **확인하지 못한 것**: 요청을 쓰는 도중에 끊기는 경우 · 다른 Reactor Netty 버전. 그래서 결제 호출에서 재전송이 *절대* 없게 하려면 플랫폼 기본값은 두고 **클라이언트별 옵션**(예: `skeleton.http.clients.<이름>.retry-on-disconnect=false` → `HttpClient.disableRetry(true)`)을 더하는 것을 제안한다 — 이 단계에서는 더하지 않았다.

### 4.2 앱이 끼어드는 자리 (`payment` 안의 계약)

```kotlin
/** 무엇을 얼마에 파는가 — 금액의 유일한 출처. 살 수 없으면 PaymentException(ITEM_NOT_PURCHASABLE) */
fun interface PaymentCatalog { fun quote(request: PurchaseRequest): PurchaseQuote }
data class PurchaseRequest(val accountId: String, val itemType: String, val itemRef: String, val quantity: Int, val country: String?)
data class PurchaseQuote(val amount: PaymentAmount, val orderName: String)   // orderName: 결제창에 보이는 이름 (개인정보를 넣지 않는다)

/** 결제 · 환불이 확정된 뒤 불린다. 주문 줄 락 안에서 한 번 — 그래도 드물게 다시 불릴 수 있으니 멱등하게 쓴다 */
interface PaymentOrderListener {
    fun onPaid(order: PaymentOrder) {}
    fun onRefunded(order: PaymentOrder, refund: PaymentRefund) {}
}

/** 운영자 경로를 쓸 수 있는가. 기본: 인증 토큰의 역할(skeleton.payment.admin.role). 앱이 빈을 두면 갈아 끼운다 */
fun interface PaymentOperatorPolicy { fun isOperator(authentication: Authentication): Boolean }
```

- `PaymentCatalog` 빈이 없으면 주문을 만드는 HTTP 는 열리지 않는다(나머지 장부 기능은 `PaymentOrders` 서비스로 직접 쓸 수 있다).
- 금액은 늘 `quote` 가 정한다. 샘플은 서버가 가진 목록(1,000 · 3,000 · 5,000원)에서 `itemRef` 로 찾을 뿐이고, 목록에 없는 `itemRef` · `quantity != 1` 은 `ITEM_NOT_PURCHASABLE` 이다 — 브라우저가 금액을 정할 길이 없다.
- 운영자 판정은 board 의 `moderator-role` 과 같은 방식(토큰의 역할)이 기본이다. 토큰이 낡았을 수 있다(≤15분) — `account` 를 쓰는 앱은 이 빈을 `AccountCallers.requireAdmin`(매 호출 저장소의 역할을 다시 읽는다)으로 바꾼다. 샘플이 그렇게 한다.
- 결제 알림(받은편지함 · 메일)은 모듈이 보내지 않는다 — 앱의 `PaymentOrderListener` 가 `NotificationPublisher` 를 부른다(샘플에 예시).

### 4.3 `payment` 의 포트와 `payment-jdbc`

- `payment` 가 정의하는 포트: `PaymentLedgerStore`(주문 · 환불 · 이력 · 웹훅 줄, 트랜잭션 열기와 주문 줄 잠그기) · `PaymentTasks`(지연 작업 넣기).
- 장부 빈(서비스 · 컨트롤러 · 탈퇴 고리)은 `@ConditionalOnBean(PaymentLedgerStore::class)` — **`payment-jdbc` 가 없으면 `payment` 는 지금과 한 글자도 다르지 않다.**
- 메모리 구현은 없다(board · legal 과 같은 이유 — 재시작에 돈의 기록이 사라지면 안 된다).
- `payment-jdbc`: PostgreSQL · MySQL 구현 + 마이그레이션 + `PaymentTasks` 를 `job-queue-jdbc` 에 싣는 어댑터(작업 종류마다 `JobHandler` 하나 — `payment` 의 `PaymentTaskRunner` 를 부른다). 루트 `build.gradle.kts` 의 `dbTestModules` 에 더한다.

### 4.4 이미 있는 모듈에서 재사용하는 것

| 모듈 | 쓰는 것 | 어떻게 |
|---|---|---|
| `job-queue-jdbc` | 지연 작업 · 재시도 · 백오프 · `SKIP LOCKED` 분배 · 죽은 작업 | `payment-jdbc` 가 `implementation` 의존. 조회 · 만료 · 지급 · 웹훅 처리를 모두 작업으로 싣는다 — **자체 폴링 워커 · 임대 · 백오프 열을 만들지 않는다.** 재시도해도 안 되는 것은 `PermanentJobFailureException` 으로 즉시 죽인다(`JQ/JobQueue.kt:40`) |
| `alert` | 주인 경보 | 두 길. ① 죽은 작업은 `alert` 가 이미 `JOB_DEAD` 로 알린다(`alert` 의 `AlertJobQueue` — 손댈 것 없음). ② 작업 밖에서 생기는 "확인 필요"(`review_reason` 이 찍히는 순간 — 예: 승인 응답의 금액이 다르다)는 `payment` 가 `compileOnly` 로 `OwnerAlerts.emit(PAYMENT_REVIEW_NEEDED, key = 주문 id)` 를 부른다. `AlertKind` 는 열린 인터페이스라 `payment` 가 자기 종류를 낸다. `alert` 가 없으면 아무 일도 없다 — `src/noOptionalTest` 로 증명(board 가 `notification` 에 하는 것과 같은 방식) |
| `idempotency` | 헤더 이름 `Idempotency-Key` 와 프론트의 `newIdempotencyKey()` | 모듈은 쓰지 않는다 — 기본 저장소가 메모리(인스턴스별)라 돈에는 못 쓴다. 같은 헤더 값을 줄의 유니크 열에 넣어 DB 가 막는다 |
| `platform` | `ExternalHttpClient` · `DeployGuard` · `AccountErasureListener` · `PublicEndpointContributor` · 요청 수 한도 | 그대로 |
| `notification` | — | 모듈은 쓰지 않는다(앱의 리스너가 쓴다) |
| `crypto` | — | 1단계에는 암호화할 값이 없다. 2단계의 빌링키 저장이 `TextEncryptor` 를 쓴다(§12.1) |
| `scheduler` | — | 쓰지 않는다. 1단계에는 주기 작업이 없다(모든 뒷일이 "그 주문의 지연 작업"이다) |

### 4.5 버린 대안

| 대안 | 버린 이유 |
|---|---|
| 지금의 `PaymentProvider` 에 `lookup` · `parseWebhook` · `charge` … 를 더하기 | 결제사 화면에서 결제가 끝나는 결제사는 `confirm` 이 없다. 못 하는 일을 예외로 던지는 큰 인터페이스가 된다 |
| 새 모듈 `payment-ledger`(+ `-jdbc`)로 떼기 | 모듈이 둘 더 늘고 설정 접두사가 갈린다. `payment` 만 끼운 앱이 지금과 같다는 것은 `payment-jdbc` 유무로 이미 보장된다 |
| 장부를 앱에 맡기고 모듈은 계약만 (지금 상태) | §3 의 방어를 앱마다 다시 짠다. "켜기만 해도 구색"이 안 된다 |
| 웹훅 수신함을 결제와 무관한 공용 모듈로 | 지금 웹훅을 받는 것은 결제뿐이다. 진위 확인 · 조회 확정이 결제 규칙과 붙어 있다 |
| 수신함 전용 폴링 워커 + 임대 + 백오프 열 + 소진 경보 열 (운영 경험의 형태) | `job-queue-jdbc` 가 같은 것을 이미 한다. 수신함 줄에는 "받았다 · 끝났다 · 왜 실패했다"만 남긴다 |
| 결제 준비 스냅샷을 이력 표의 JSON 한 줄로 두고 완료 때 복원 | 주문 줄이 곧 스냅샷이다(금액 · 통화 · 상품 · 결제사 · 모드가 만들 때 박힌다). 따로 둘 문맥이 없다 — 쿠폰 · 할인이 비목표이기 때문이다 |
| 주문 상태에 `REFUND_PENDING` 두기 | 환불이 실패하면 되돌릴 "이전 상태"를 기억해야 한다. 열린 환불은 환불 줄의 상태로 두고 주문의 상태는 돈의 사실만 말하게 한다(§5.2) |
| 시작 시 분산 락(Redis) | 주문 줄 락과 유니크 열로 같은 것이 된다. Redis 없는 앱에서도 돈다 |
| 가짜 결제사 모듈(`payment-fake`)을 `main` 에 두기 | 실수로 배포될 수 있는 "돈 안 받고 결제됨" 코드를 제품 코드에 넣지 않는다. 시험은 시험 쪽의 가짜 결제사 서버로 한다(§13) — 소셜 로그인 e2e 와 같은 방식 |
| 구매자 이름 · 이메일을 주문 줄에 저장, 결제사 응답 원문을 이력에 저장 | 탈퇴 익명화와 충돌하고(§8), 원문에는 무엇이 섞여 올지 통제할 수 없다 |
| 플랜을 바꿀 때마다 구독 줄을 새로 만들기 (2단계) | "살아 있는 구독이 둘"인 상태가 가능해진다. 구독은 한 줄이고 변경은 이력 표로(§12.1) |

## 5. 데이터 모델 (두 방언, 새 V 파일만)

**`payment-jdbc`** — `V<UTC>__payment_ledger.sql` 하나(`./gradlew newMigration -Pname=payment_ledger -Pmodule=modules/payment-jdbc`, 잠금은 `perl scripts/migrations-lock.pl`). 이미 잠긴 파일은 건드리지 않는다. 아래는 PostgreSQL 판이다 — **실제 V 파일(`modules/payment-jdbc/src/main/resources/db/migration/postgresql/V20261009124513__payment_ledger.sql`)을 그대로 옮긴 것**이다(B4 리뷰 · B6 · B7 에서 인덱스 · 열 정렬이 스펙 초안과 달라졌다. 어긋나면 V 파일이 정본이다).

두 방언 공통 규칙: 금액은 모두 `bigint` **통화 최소 단위** + 통화 열(환산하지 않는다 · 통화가 다른 금액은 더하지 않는다) · 시각은 PG `timestamptz` / MySQL `datetime(6)` UTC(`SqlDialect.instantParam` · `readInstant` 로만) · JSON 열 없음 · 부분 인덱스 없음("조건부 유니크"는 NULL 이 겹치지 않는 유니크 열로) · MySQL 판은 `create index if not exists` 대신 표 안의 인덱스 + `[jooq ignore]` 표식(`board-jdbc` 의 MySQL 판과 같은 방식) · 결제사가 준 값과 우리 키를 담는 열(`id` · `provider_payment_id` · `provider_refund_id` · `dedupe_key` · `open_key` · `event_key`)은 MySQL 에서 `utf8mb4_bin`(대소문자를 구분한다).

### 5.1 `payment_orders` — 주문 장부 (결제 한 번 = 한 줄)

```sql
create table if not exists payment_orders (
    id                  varchar(40)  primary key,         -- ord_ + 32자 16진수 (SecureRandom 16바이트). 결제사에 보내는 주문 번호이기도 하다. 순번 · 시각을 담지 않는다
    account_id          varchar(128) not null,            -- 산 계정. FK 없음 (다른 모듈의 표)
    kind                varchar(24)  not null,            -- ONE_TIME | SUBSCRIPTION_INITIAL | SUBSCRIPTION_RENEWAL
    status              varchar(24)  not null,            -- PENDING | CONFIRMING | PAID | PARTIALLY_REFUNDED | REFUNDED | FAILED | EXPIRED | CANCELED
    provider            varchar(40)  not null,            -- 만들 때 서버가 고른 결제사. 바뀌지 않는다
    provider_mode       varchar(8)   not null,            -- TEST | LIVE | UNKNOWN — 만들 때 끼워져 있던 키의 모드
    provider_payment_id varchar(200),                     -- 결제사의 결제 번호 (토스 paymentKey, 최대 200자). 선점 때 박힌다
    currency            char(3)      not null,            -- ISO 4217
    amount              bigint       not null,            -- 청구하려는 금액 (통화 최소 단위)
    paid_amount         bigint,                           -- 결제사가 실제로 받았다고 말한 총액
    tax_amount          bigint,                           -- paid_amount 안의 세금. NULL = 모른다 · 해당 없음
    refunded_amount     bigint       not null default 0,  -- 성공한 환불의 합 — payment_refunds 의 비정규화. 같은 트랜잭션에서만 바뀐다
    item_type           varchar(40)  not null,            -- 앱이 정하는 상품 종류 코드
    item_ref            varchar(128) not null,            -- 앱이 정하는 상품 참조
    quantity            int          not null default 1,
    order_name          varchar(100) not null,            -- 결제창 · 내역에 보이는 이름 (앱 카탈로그가 정한다, 사용자 입력이 아니다)
    origin_type         varchar(24)  not null,            -- CHECKOUT | SUBSCRIPTION — 이 주문을 만든 것
    origin_ref          varchar(64),                      -- 만든 것의 id (구독 id). CHECKOUT 은 NULL
    dedupe_key          varchar(100),                     -- 같은 주문을 두 번 만들지 않는 키 (NULL 은 서로 겹치지 않는다) — 유니크는 아래 uq_payment_orders_dedupe
    method              varchar(40),                      -- 결제 수단 종류 코드 (CARD · EASY_PAY …)
    card_issuer         varchar(40),                      -- 카드사 코드 (결제사 중립 대문자 영문). 카드가 아니면 NULL. 지워지는 개인정보 — 계정이 지워지면 NULL
    card_last4          varchar(4),                       -- 가려서 받은 카드 번호의 마지막 네 글자 — 숫자 또는 `*` 만. 지워지는 개인정보 — 계정이 지워지면 NULL
    failure_code        varchar(64),                      -- 결제사의 거절 코드 또는 우리 코드. 문장은 저장하지 않는다
    review_reason       varchar(64),                      -- NULL 이 아니면 사람이 봐야 한다 (돈이 어긋난 이유: AMOUNT_MISMATCH · ORDER_MISMATCH · REFUND_MISMATCH · MODE_MISMATCH · CONFIRM_UNRESOLVED / 지급 쪽 이유: FULFILMENT_UNRESOLVED · REFUND_APPLY_UNRESOLVED — 앞쪽이 뒤쪽을 덮어쓴다)
    checkout_expires_at timestamptz  not null,                    -- 결제창이 열려 있는 마지막 시각. 2단계의 구독 갱신 주문(결제창 없음)은 만든 시각을 채운다
    confirm_started_at  timestamptz,
    paid_at             timestamptz,
    fulfilled_at        timestamptz,                      -- 앱의 onPaid 가 끝난 시각. NULL 이면 아직
    closed_at           timestamptz,                      -- FAILED · EXPIRED · CANCELED 가 된 시각
    account_erased_at   timestamptz,                      -- 이 계정이 지워진 시각 — 탈퇴자 기록의 구분 · 보존 기간의 기준
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    constraint uq_payment_orders_provider_payment unique (provider, provider_payment_id),   -- 한 결제가 두 주문에 붙지 못한다. NULL 은 서로 겹치지 않는다
    constraint uq_payment_orders_dedupe unique (dedupe_key),
    constraint ck_payment_orders_amounts check (amount >= 0 and refunded_amount >= 0 and refunded_amount <= coalesce(paid_amount, amount))
);
create index if not exists idx_payment_orders_account on payment_orders (account_id, created_at, id);          -- 내 결제 내역 (거꾸로 훑는다)
create index if not exists idx_payment_orders_status  on payment_orders (status, created_at, id);              -- 운영자 목록
create index if not exists idx_payment_orders_origin  on payment_orders (origin_type, origin_ref, created_at); -- 한 구독의 결제 이력
create index if not exists idx_payment_orders_review  on payment_orders (review_reason, created_at, id);       -- 확인 필요 목록 (커서 = created_at, id)
create index if not exists idx_payment_orders_created on payment_orders (created_at, id);                      -- 운영자 기본 목록 (필터 없음, 최신순 커서)
create index if not exists idx_payment_orders_unfulfilled on payment_orders (fulfilled_at, status, created_at, id);   -- 확인 필요: 돈은 받았는데 지급이 안 끝난 줄 (fulfilled_at is null and status = PAID — 소수. 열 순서: status 로 시작하면 옵티마이저가 상태별 목록 질의에도 이 인덱스를 골라 정렬을 따로 한다)

-- 환불 한 건 = 한 줄
```

**저장하지 않는 것**: 카드 번호(끝 4자리 밖의 어떤 부분도) · 유효기간 · 소유자명 · 할부 · 구매자 이름 · 이메일 · 전화번호 · 주소 · 영수증 주소 · 결제사 응답 원문 · 자유 문장.

**저장하는 개인정보는 `card_issuer` · `card_last4` 둘뿐이다.** 서비스가 결제사 스냅샷을 줄에 옮길 때 `card_last4` 를 다시 걸러(마지막 네 글자, 숫자 · `*` 가 아니면 NULL) 결제사 모듈이 실수해도 네 글자를 넘는 값이 들어가지 못하게 한다(`varchar(4)` 가 마지막 방어). 내역 화면은 「현대카드 ····1234」 모양으로 보이고, 카드가 아니면 「간편결제」 같은 수단 종류만 보인다(§11.2). 이 두 칸은 **`PAID` 가 되는 `converge` 에서 한 번** 채워지고 이후 바뀌지 않는다.

### 5.2 주문 상태 전이

```
 PENDING ──승인 요청(본인 · 금액 일치)──▶ CONFIRMING ──결제사: 승인──▶ PAID ──환불 성공──▶ PARTIALLY_REFUNDED ──▶ REFUNDED
    │                                        │   └─결제사: 거절(확정)──▶ FAILED                 └──────── 전액 ────────────▶
    ├─본인이 그만둠──▶ CANCELED               └─모름──▶ CONFIRMING (조회 작업이 끝낸다)
    └─시간 지남 + 결제사에 결제 없음──▶ EXPIRED

 FAILED · EXPIRED · CANCELED ──결제사 조회 증거(돈이 들어왔다 · 금액 일치)──▶ PAID      ← 클라이언트 호출로는 열리지 않는다
```

| 지금 | 다음 | 누가 · 조건 |
|---|---|---|
| `PENDING` | `CONFIRMING` | 본인의 승인 요청. 금액 · 모드 일치, `checkout_expires_at` 전. 결제 키를 줄에 박는다 |
| `PENDING` | `CANCELED` | 본인이 결제창에서 그만둠 |
| `PENDING` | `EXPIRED` | 만료 작업 — 결제사에 물어 결제가 없거나 끝난 실패일 때만 |
| `PENDING` · `CONFIRMING` | `PAID` | 승인 응답 또는 조회가 "받았다"고 하고 총액이 `amount` 와 같다 |
| `CONFIRMING` | `FAILED` | 결제사가 거절 코드로 답했다(확정) · 조회가 실패 · 만료라고 한다 |
| `CONFIRMING` | `CONFIRMING` | 모름 — 상태를 바꾸지 않는다 |
| `FAILED` · `EXPIRED` · `CANCELED` | `PAID` | **결제사 조회 증거로만.** 이력에 `REOPENED_PAID` |
| `PAID` · `PARTIALLY_REFUNDED` | `PARTIALLY_REFUNDED` · `REFUNDED` | 환불 줄이 `SUCCEEDED` 가 될 때 `refunded_amount` 로 계산한다 |
| `PAID` 이후 | `FAILED` · `EXPIRED` · `CANCELED` | **없다.** 늦게 온 실패 신호는 무시한다. 돈이 돌아갔으면 그것은 환불이다 |
| `REFUNDED` | — | 끝 |

- 한 주문은 한 번만 승인을 시도한다. `FAILED` 뒤 "다시 결제"는 **새 주문**이다(결제사의 멱등 키와 주문 번호를 다시 쓰지 않는다).
- "환불 중"은 주문 상태가 아니다 — 열린 환불 줄이 있다는 사실이고 응답의 `refundPending` 으로 나간다.

### 5.3 `payment_refunds` — 환불 한 건 = 한 줄

```sql
create table if not exists payment_refunds (
    id                 varchar(40)  primary key,          -- rfd_ + 32자 16진수. 결제사에 보내는 멱등 키이기도 하다
    order_id           varchar(40)  not null references payment_orders (id),
    status             varchar(16)  not null,             -- REQUESTED | PENDING | SUCCEEDED | FAILED | REJECTED | WITHDRAWN
    origin             varchar(16)  not null,             -- USER | OPERATOR | PROVIDER (결제사 쪽에서 일어난 취소를 맞춰 넣은 줄)
    amount             bigint       not null,
    currency           char(3)      not null,
    reason_code        varchar(40)  not null,             -- CUSTOMER_REQUEST | DUPLICATE | NOT_DELIVERED | OPERATOR | PROVIDER_CONSOLE. 자유 문장은 받지 않는다
    open_key           varchar(40),                       -- 열려 있는 동안(REQUESTED · PENDING) = order_id, 닫히면 NULL → 주문당 열린 환불은 하나 (유니크는 아래 uq_payment_refunds_open)
    dedupe_key         varchar(100),                      -- sha256(호출자 + "\n" + Idempotency-Key) — 같은 요청이 늦게 다시 와도 한 줄 (유니크는 아래 uq_payment_refunds_dedupe)
    provider_refund_id varchar(200),                      -- 결제사의 취소 거래 번호 (토스 cancels[].transactionKey)
    expected_remaining bigint,                            -- 보내기 직전에 계산한 "성공하면 결제사에 남을 금액"
    requested_by       varchar(128),                      -- 요청한 계정 (본인 또는 운영자; `TOMBSTONE` 이면 그 계정이 산 주문의 줄에서만 바뀐다). PROVIDER 는 NULL
    decided_by         varchar(128),                      -- 승인 · 거절한 운영자 (운영자로서 남긴 값은 탈퇴로 바뀌지 않는다)
    failure_code       varchar(64),
    requested_at       timestamptz  not null,
    sent_at            timestamptz,
    completed_at       timestamptz,
    applied_at         timestamptz,                       -- 앱의 onRefunded 가 끝난 시각
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    constraint uq_payment_refunds_provider unique (order_id, provider_refund_id),   -- 결제사의 취소 하나를 두 번 맞춰 넣지 못한다
    constraint uq_payment_refunds_open unique (open_key),
    constraint uq_payment_refunds_dedupe unique (dedupe_key),
    constraint ck_payment_refunds_amount check (amount > 0)
);
create index if not exists idx_payment_refunds_order  on payment_refunds (order_id, created_at);
create index if not exists idx_payment_refunds_status on payment_refunds (status, requested_at, id);   -- 운영자의 환불 요청 목록 (커서)
create index if not exists idx_payment_refunds_unapplied on payment_refunds (applied_at, status, requested_at, id);   -- 확인 필요: 환불은 됐는데 회수가 안 끝난 줄 (applied_at is null and status = SUCCEEDED — 소수. 열 순서는 위와 같은 이유)
-- PostgreSQL 확장 통계(`create statistics`) 두 줄은 진행 문서의 결정으로 빠졌다 — 실측에서 계획을 한 번도 바꾸지 못했다.

-- 이력 — 추가만 한다. "이 주문이 왜 이 상태인가"를 로그 없이 답한다. 자유 문장 열이 없다.
```

| 지금 | 다음 | 누가 · 조건 |
|---|---|---|
| (없음) | `REQUESTED` | 본인의 환불 요청 — 돈은 움직이지 않는다 |
| (없음) · `REQUESTED` | `PENDING` | 운영자가 환불하거나 요청을 승인. **결제사를 부르기 전에** 커밋한다 |
| `REQUESTED` | `REJECTED` · `WITHDRAWN` | 운영자가 거절 · 본인이 거둠 |
| `PENDING` | `SUCCEEDED` | 결제사가 환불됐다고 했다(응답 또는 조회). 주문의 `refunded_amount` · 상태를 같은 트랜잭션에서 올린다 |
| `PENDING` | `FAILED` | 결제사가 거절 코드로 답했다 · 조회해 보니 적용되지 않았다 |
| `PENDING` | `PENDING` | 모름 |
| (없음) | `SUCCEEDED` (`origin=PROVIDER`) | 조회에서 장부가 모르는 결제사 취소를 발견 |

### 5.4 `payment_events` · `payment_webhook_events`

```sql
create table if not exists payment_events (
    id                bigint generated always as identity primary key,
    order_id          varchar(40)  not null,
    refund_id         varchar(40),
    type              varchar(40)  not null,              -- ORDER_CREATED · CONFIRM_STARTED · … · REVIEW_FLAGGED
    from_status       varchar(24),
    to_status         varchar(24),
    actor             varchar(16)  not null,              -- USER | OPERATOR | SYSTEM | PROVIDER
    actor_id          varchar(128),                       -- 계정 id (`TOMBSTONE` 이면 그 계정이 산 주문의 이력에서만 바뀐다)
    provider_code     varchar(64),                        -- 결제사의 오류 · 상태 코드
    provider_trace_id varchar(128),                       -- 결제사에 문의할 때 대는 추적 번호
    trace_id          varchar(64),                        -- 우리 traceId — 서버 로그와 잇는다
    created_at        timestamptz  not null
);
create index if not exists idx_payment_events_order on payment_events (order_id, id);

-- 웹훅 수신함 — 받은 사실과 처리 결과. 자유 문장 열이 없다.
create table if not exists payment_webhook_events (
    id            bigint generated always as identity primary key,
    provider      varchar(40)  not null,
    event_key     varchar(200) not null,                  -- 결제사의 전달 id. 없으면 본문의 sha256
    event_type    varchar(64)  not null,
    resource_type varchar(24)  not null,                  -- PAYMENT | BILLING_METHOD | SUBSCRIPTION
    resource_id   varchar(200) not null,
    order_id      varchar(40),
    payload       text,                                   -- 기본 NULL — 서명된 본문이 곧 사실인 결제사만 필요한 만큼 남긴다
    payload_hash  char(64)     not null,
    status        varchar(16)  not null,                  -- RECEIVED | PROCESSED | IGNORED | FAILED | DEAD
    deliveries    int          not null default 0,        -- 같은 event_key 가 도착한 횟수
    last_error    varchar(64),                            -- 우리 코드 (MODE_MISMATCH · LOOKUP_UNAVAILABLE …). 예외 메시지를 넣지 않는다
    received_at   timestamptz  not null,
    processed_at  timestamptz,
    constraint uq_payment_webhook_events unique (provider, event_key)
);
create index if not exists idx_payment_webhook_events_status   on payment_webhook_events (status, received_at, id);
create index if not exists idx_payment_webhook_events_resource on payment_webhook_events (provider, resource_type, resource_id);
create index if not exists idx_payment_webhook_events_order    on payment_webhook_events (order_id, received_at);   -- 주문 단위 창 상한을 센다 (주문 줄 락 안에서)
```

- **두 표에 자유 문장 열이 없다** — 무엇이 섞여 들어올 자리가 없다.
- 수신함의 `payload` 는 **토스에서는 늘 NULL** 이다: 토스의 결제 웹훅 본문은 결제 객체 전체이고(가린 카드 번호 등이 들어 있다) 서명이 없어 어차피 믿지 않는다. 처리에 필요한 것은 결제 키와 주문 번호뿐이고 진실은 조회로 얻는다. 서명된 본문이 곧 사실인 결제사(3단계)만 `WebhookEnvelope.storedPayload` 로 필요한 만큼 남기고, 그 모듈이 개인정보 칸을 지운 뒤 넘긴다.
- 열 분류 시험(§13): `information_schema` 에서 `payment_*` 의 모든 열을 읽어 "식별자 · 코드 · 금액 · 시각 · 원문 · **지워지는 개인정보**" 분류표와 맞춘다. 분류되지 않은 열이 생기면 실패한다 — 새 열이 조용히 개인정보를 담지 못하게. **"지워지는 개인정보" 분류에 속하는 열은 `payment_orders.card_issuer` · `card_last4` 둘뿐**이어야 하고, 이 분류의 열은 탈퇴 고리가 NULL 로 지운다는 시험(§13.2)과 짝이다.

## 6. 동작 규칙 — 흐름 · 트랜잭션 경계 · 락

### 6.1 세 가지 원칙

1. **외부 호출 중에는 DB 트랜잭션을 잡지 않는다.** 결제사가 30초 걸려도 연결 하나를 물고 있지 않는다. 그래서 모든 외부 호출은 **선점(커밋) → 호출 → 확정(커밋)** 세 걸음이다. 선점이 커밋된 뒤 프로세스가 죽어도, 선점할 때 같은 트랜잭션에서 넣어 둔 지연 작업이 뒤를 잇는다(`JobQueue.enqueue` 는 부르는 쪽 트랜잭션에 참여한다 — `JQ/JobQueue.kt:24`).
2. **락은 DB 의 주문 줄 하나다.** 모든 전이는 `select … from payment_orders where id = ? for update` 로 줄을 잡고 **상태를 다시 읽은 뒤** 판단한다. 환불도 주문 줄을 먼저 잡는다(환불 줄만 잡지 않는다 — 락 순서는 늘 주문 → 환불). Redis 락 · 분산 락은 없다. "하나만 있어야 한다"는 유니크 열이 지킨다: `dedupe_key`(주문 · 환불) · `open_key`(열린 환불) · `(provider, provider_payment_id)` · `(order_id, provider_refund_id)` · `(provider, event_key)`.
3. **결제사와 장부를 맞추는 코드는 한 곳이다 — `PaymentConvergence.converge(주문 줄, PaymentSnapshot)`.** 승인 응답의 확정, 조회 작업, 웹훅 처리, 운영자의 「결제사와 맞추기」, 환불 전 확인, 환불 조회가 **모두 이 함수로 끝난다.** 같은 규칙을 길마다 따로 구현하다 한 길에서 빠지는 사고(운영 교훈: 금액 대조가 한 경로에만 없었다)를 구조로 막는다.

`converge` 가 하는 일(주문 줄 락 안, 몇 번 불려도 결과가 같다):

1. 줄의 `provider_mode` 와 지금 결제사의 모드가 다르면 아무것도 바꾸지 않고 `review_reason=MODE_MISMATCH`.
2. 스냅샷이 "받았다"인데 줄이 아직 `PAID` 가 아니면: 총액 · 통화가 줄과 같을 때만 `PAID`(`paid_amount` · `paid_at` · `method` · `card_issuer` · `card_last4`) + 지급 작업. 다르면 `review_reason=AMOUNT_MISMATCH` — 「결제됨」으로 올리지 않는다.
3. 스냅샷이 실패 · 만료이고 줄이 `CONFIRMING`(또는 만료 작업이 부른 `PENDING`)이면 `FAILED` / `EXPIRED`. 줄이 이미 `PAID` 이후면 무시한다.
4. 스냅샷의 환불 목록을 줄의 환불 줄과 맞춘다: 장부에 없는 결제사 환불 → 금액이 같은 열린 `PENDING` 환불이 있으면 그 줄을 `SUCCEEDED` 로, 없으면 `origin=PROVIDER` 줄을 새로. `refunded_amount` 와 상태를 다시 계산하고 회수 작업을 넣는다.
5. 끝으로 `paid_amount - refunded_amount` 가 스냅샷의 남은 금액과 다르면 `review_reason=REFUND_MISMATCH`.
6. 바뀐 것이 있으면 이력 한 줄.

### 6.2 단건 결제

```
브라우저                     우리 서버                                        결제사
 「결제하기」 ─ POST /orders ─▶ PaymentCatalog.quote → 줄(PENDING) + 만료 작업      
             ◀─ 주문 + 결제창에 넘길 값 ─
 결제창 열기 ───────────────────────────────────────────────────────────────▶ 인증
             ◀──────────── successUrl?paymentKey&orderId&amount ─────────────
 결과 화면 ─ POST /orders/{id}/confirm ─▶ ① 선점: 락 → PENDING→CONFIRMING + 조회 작업   (커밋)
                                        ② 승인 호출 (멱등 키 = 주문 id) ──────────▶ 승인
                                        ③ 확정: 락 → converge → PAID + 지급 작업      (커밋)
                                        ④ 지급: 락 → fulfilled_at 비었으면 onPaid     (커밋)
             ◀─ 200 PAID · 402 거절 · 202 확인 중 ─
```

- **만들기**: 결제사는 서버가 고른다(`PaymentProviderRouter` — 통화 · 국가 · 기본값. 요청이 결제사를 지정하지 못한다). 금액 · 통화 · 상품 · 결제사 · 모드가 줄에 박히고 이후 바뀌지 않는다. 같은 `Idempotency-Key` 면 같은 줄을 돌려준다(본문이 다르면 `409`).
- **선점(①)**: 락 안에서 — 주인 확인, `PENDING` 인지, 만료 전인지, 요청의 `amount` 가 줄과 같은지, 줄의 모드가 지금 키의 모드와 같은지. 통과하면 `CONFIRMING` + 결제 키 + `confirm_started_at`, 그리고 조회 작업(`runAt = 지금 + reconcile.first-delay`)을 같은 트랜잭션에. 이미 `CONFIRMING` 이면 결제사를 부르지 않고 `202`, 이미 `PAID` 면 `200`(같은 결제 키일 때) — 그 밖은 `409 PAYMENT.ORDER_NOT_PAYABLE`.
- **호출(②)**: 락 밖. 금액은 줄의 값. 결제사 멱등 키는 주문 id(같은 주문의 재전송이 생겨도 결제사가 한 번으로 본다).
- **확정(③)**: 응답이 승인이면 응답에서 만든 스냅샷으로 `converge`. 확정 거절이면 `FAILED` + `failure_code`(결제사 코드). **모름이면 아무것도 하지 않는다** — `CONFIRMING` 그대로 `202`.
- **지급(④)**: 요청 스레드가 확정 직후 한 번 바로 시도한다(결과 화면이 곧바로 "지급됨"을 보게). 실패해도 「결제됨」은 그대로이고 큐의 지급 작업이 재시도한다. 리스너는 주문 줄 락 + `fulfilled_at is null` 조건 안에서 불리므로 같은 DB 를 쓰는 지급은 정확히 한 번이다.
- **그만둠**: 결제창의 `failUrl` 로 돌아오면 프론트가 `POST /orders/{id}/abandon` — `PENDING` 일 때만 `CANCELED`.

### 6.3 뒷일 — 지연 작업 (모두 `job-queue-jdbc`, 처리기는 멱등)

| 작업 | 넣는 때 (같은 트랜잭션) | 하는 일 | 끝나지 않으면 |
|---|---|---|---|
| `payment-order-expire` | 주문을 만들 때. `runAt = checkout_expires_at` | 줄이 아직 `PENDING` 이면 결제사에 **주문 번호로** 조회 → `converge`. 결제가 없거나 실패면 `EXPIRED`. 아직 진행 중이면 던진다(백오프 뒤 다시) | 죽은 작업 → 경보. 줄은 `PENDING` 그대로(아무도 결제할 수 없다 — 만료 시각이 지났다) |
| `payment-order-reconcile` | 선점할 때. `runAt = 지금 + first-delay` | 줄이 아직 `CONFIRMING` 이면 결제 키로 조회 → `converge`. 결제사가 아직 진행 중이면 던진다. **끝 상태를 들을 때까지 줄을 풀지 않는다** | `review_reason=CONFIRM_UNRESOLVED` + 죽은 작업 → 경보 |
| `payment-order-fulfil` | `PAID` 가 될 때 | 락 → `fulfilled_at` 이 비었으면 `PaymentOrderListener.onPaid` → `fulfilled_at` | 죽은 작업 → 경보 (돈은 받았는데 지급이 안 됐다 — 가장 먼저 봐야 하는 경보) |
| `payment-refund-reconcile` | 환불을 `PENDING` 으로 올릴 때. `runAt = 지금 + settle-delay` | 환불이 아직 `PENDING` 이면 §6.4 의 조회 정산 | `review_reason=REFUND_MISMATCH` + 죽은 작업 → 경보. 환불 줄은 열린 채라 그 주문의 다음 환불을 막는다 |
| `payment-refund-apply` | 환불이 `SUCCEEDED` 가 될 때 | 락 → `applied_at` 이 비었으면 `onRefunded` → `applied_at` | 죽은 작업 → 경보 |
| `payment-webhook` | 웹훅을 받을 때 | §6.5 | 수신함 줄 `DEAD` + 죽은 작업 → 경보 |

- 조회 작업의 시도 수는 `reconcile.max-attempts`(기본 12 — 큐의 기본 백오프 30초 · 2배 · 최대 1시간으로 약 4시간. 토스의 인증 만료 30분을 넉넉히 넘는다).
- 재시도해도 소용없는 것(모드 불일치 · 금액 불일치 · 그 능력이 없는 결제사)은 `PermanentJobFailureException` 으로 바로 죽인다 — 재시도와 경보만 쌓이지 않게(운영 교훈).
- 처리기가 던지는 메시지는 **우리가 쓴 문장과 id 뿐**이다(작업 줄의 `last_error` 에 남는다). 결제사 응답 본문을 예외 메시지에 싣지 않는다.

### 6.4 환불

```
 본인: 환불 요청 ─▶ 락 → 환불 줄 REQUESTED (돈은 그대로)
 운영자: 승인 또는 직접 환불
   ⓪ 환불 전 확인: 결제사 조회 → converge        (장부가 모르는 취소 · 늦게 도착한 앞선 환불을 먼저 맞춘다)
   ① 선점: 락 → 환불 가능액 다시 계산 → 환불 줄 PENDING + expected_remaining + 조회 작업      (커밋)
   ② 환불 호출 (멱등 키 = 환불 줄 id, 금액 = 줄의 값) ───────────────────────────────▶ 결제사
   ③ 확정: 락 → 성공: SUCCEEDED + refunded_amount + 주문 상태 + 회수 작업 / 확정 거절: FAILED / 모름: 그대로   (커밋)
```

- **환불 가능액** = `paid_amount - refunded_amount` (열린 환불이 있으면 새 환불 자체를 받지 않는다 — `open_key`). 금액을 생략하면 남은 전액.
- **누가**: 본인은 요청만 만들 수 있다(`refund.requests-enabled`). 돈을 움직이는 것은 운영자뿐이다. 요청 없이 운영자가 바로 환불할 수도 있다.
- **모름일 때 — 다시 보내지 않는다.** `payment-refund-reconcile` 이 조회해 `converge` 를 돌린 뒤:
  - 결제사의 남은 금액 = `expected_remaining` → 적용됐다. `converge` 의 4번이 이미 이 줄을 `SUCCEEDED` 로 올렸다.
  - 결제사의 남은 금액 = `expected_remaining + amount` → 적용되지 않았다. `FAILED`(`failure_code=NOT_APPLIED`), 주문은 다시 환불할 수 있다. 운영자가 다시 누르면 **새 환불 줄 · 새 멱등 키**다.
  - 그 밖 → `review_reason=REFUND_MISMATCH`, 사람이 본다.
- `settle-delay`(기본 2분)는 결제사 응답 제한 시간보다 충분히 길어야 한다 — "적용 안 됨"으로 닫은 뒤 앞선 요청이 늦게 적용되는 경우를 줄인다. 그래도 생기면 다음 환불의 ⓪ 과 웹훅이 `origin=PROVIDER` 줄로 잡는다(§15).
- 결제사에 보내는 환불 사유는 `reason_code` 의 고정 문구다(토스는 사유가 필수 · 최대 200자). 사용자가 쓴 글을 결제사로 보내지 않는다.
- 환불 거절 코드는 종류로 묶어 응답한다(이미 환불됨 · 금액 초과 · 부분 환불 불가 · 기간 지남 · 일시 장애 · 그 밖) — 결제사 코드는 `data.providerCode` 로 함께.

### 6.5 웹훅 수신함

```
 결제사 ─ POST /payments/webhooks/{provider}[/{token}] ─▶ ① 진위: 비밀 조각 · 서명     (틀리면 404 / 401, 저장 없음)
                                                        ② 해석: 자원 종류 · id · 이벤트 키만 꺼낸다
                                                        ③ 아는 주문인가                (아니면 200, 저장 없음)
                                                        ④ 줄 넣기 + 처리 작업          (한 트랜잭션, 커밋 뒤 200)
                         payment-webhook 작업 ─▶ ⑤ 결제사에 조회 ─▶ converge ─▶ PROCESSED
```

- **응답은 DB 쓰기 한 번 뒤 바로 200** 이다(토스는 10초 안에 200 을 받지 못하면 다시 보낸다 — §17). 처리는 작업이 한다.
- **본문의 상태 · 금액은 쓰지 않는다.** 결제 키와 주문 번호만 꺼내고, 사실은 ⑤ 의 조회에서 온다. 위조된 웹훅이 할 수 있는 일은 "조회 한 번을 일으키는 것"뿐이고, 그것도 ③ 이 아는 주문으로 좁히고 요청 수 한도가 막는다.
- **진위(①)**: `signed=false` 인 결제사(토스의 결제 이벤트)는 주소 끝의 `{token}` 이 설정값과 같아야 한다(상수 시간 비교). 보호 환경에서 이 값이 비어 있으면 기동 실패(§9). 서명이 있는 결제사는 그 모듈이 원문 바이트로 서명을 확인한다.
- **중복(④)**: `(provider, event_key)` 가 이미 있으면 `deliveries` 만 올린다. 그 줄이 `FAILED` · `DEAD` 면 **새 처리 작업을 바로 넣는다** — 결제사의 재전송은 자동 재시도의 대기 · 예산과 무관하다(§3 11). 단 줄의 `deliveries` 가 `webhook.redelivery-requeue-limit`(기본 10) 이하일 때만이다 — 실패한 작업은 백오프에 살아 있어 상한 없이 얹으면 줄당 작업이 끝없이 늘기 때문이다. 운영자의 「다시 처리」는 이 상한과 무관하고 `PROCESSED` 가 아닌 모든 줄(`RECEIVED` 포함: 작업이 죽었는데 표시가 안 찍힌 줄)에 새 작업을 넣는다. 새 줄인지는 넣은 뒤 `deliveries` 를 올린 값으로 판정한다(`SqlDialect.insertIgnore` 는 넣었는지를 행 수로 알려 주지 않는다).
- **주문 단위 상한(④ 앞)**: 호출자가 전달 id 를 정하므로 새 이벤트 키는 주문마다 창(`webhook.per-order-limit`, 기본 10분에 30줄)의 상한을 탄다. 수신은 **주문 줄을 먼저 잠그고**(락 순서: 주문 → 수신함 줄) `order_id` 로 세어 같은 트랜잭션에서 넣는다 — 여러 대가 동시에 받아도 정확히 상한이다. 상한에 걸린 신호는 버리지 않는다: 주문당 창마다 수신함에 **표식 줄 하나**(`event_key=limited:<주문 id>:<창 번호>`, `resource_type=LIMIT_MARKER`, `deliveries` = 그 창에서 걸린 수; 표식 줄은 상한에 세지 않는다)와 **창 끝으로 미룬 처리 작업 하나**를 건다. 그 작업이 결제사에 조회해 맞추므로 창이 가득 찬 동안 온 진짜 신호(콘솔의 취소)도 창 끝에는 장부에 닿는다.
- **처리(⑤)**: 조회 결과가 없으면 — 줄의 모드와 지금 키의 모드가 다르면 `MODE_MISMATCH` 로 **즉시 죽인다**(실결제 웹훅이 개발 주소에도 등록된 설정 오류를 드러낸다 — 운영 교훈), 같으면 `IGNORED`.
- **순서**: 1단계의 결제 이벤트는 주문이 늘 먼저 있다(우리가 주문을 만든 뒤 결제창을 연다). 그래서 모르는 주문은 버린다. 결제사가 먼저 만드는 자원(2 · 3단계: 구독의 청구)은 다르다 — 그 처리기는 "아직 구독이 없다"를 추측으로 메우지 않고 던져서 재시도하게 한다. 수신함은 자원 종류별 처리기(`PaymentWebhookHandler`)를 받는 구조로 만들어 둔다.
- 토스에서 다루는 이벤트: `PAYMENT_STATUS_CHANGED`(결제 키 · 주문 번호를 꺼낸다). 그 밖(`DEPOSIT_CALLBACK` · `BILLING_DELETED` …)은 1단계에서 `IGNORED`.

### 6.6 토스 대응표 (`payment-toss`)

| 우리 | 토스 |
|---|---|
| 결제창 | SDK v2 `TossPayments(clientKey).payment({ customerKey: ANONYMOUS }).requestPayment({ method: 'CARD', amount: { currency, value }, orderId, orderName, successUrl, failUrl })` — **구매자 이름 · 이메일 · 전화번호는 넘기지 않는다**(선택 칸이다) |
| 승인 | `POST /v1/payments/confirm` `{ paymentKey, orderId, amount }` + `Idempotency-Key: <주문 id>` |
| 조회 | `GET /v1/payments/{paymentKey}` · `GET /v1/payments/orders/{orderId}`. `404 NOT_FOUND_PAYMENT` → 결제 없음 |
| 환불 | `POST /v1/payments/{paymentKey}/cancel` `{ cancelReason, cancelAmount }` + `Idempotency-Key: <환불 id>` |
| 스냅샷 상태 | `READY` · `IN_PROGRESS` · `WAITING_FOR_DEPOSIT` → 진행 중 / `DONE` → 받았다 / `PARTIAL_CANCELED` → 일부 환불 / `CANCELED` → 전액 환불 / `ABORTED` → 실패 / `EXPIRED` → 만료 |
| 총액 · 남은 금액 · 환불 목록 | `totalAmount` · `balanceAmount` · `cancels[]`(`transactionKey` · `cancelAmount` · `canceledAt`, `cancelStatus=DONE` 인 것만) |
| 카드 | `card.issuerCode` → 카드사 코드(결제사 중립의 대문자 영문 코드, 예 `HYUNDAI`. 모듈의 대응표에 없으면 토스가 준 코드 그대로) · `card.number` → **끝 네 글자만**(숫자 또는 `*` 외의 글자가 섞이면 버린다). 앞부분 · 유효기간 · 할부 등 그 밖의 카드 칸은 읽지 않는다. 카드 결제가 아니면 `card` 없음 |
| 모드 | 시크릿 키의 접두사: `test_` → TEST · `live_` → LIVE |
| 웹훅 이벤트 키 | 헤더 `tosspayments-webhook-transmission-id` (없으면 본문 sha256) |
| 응답 제한 시간 | 플랫폼의 클라이언트별 설정 `skeleton.http.clients.payment-toss.response-timeout` 을 쓴다(새 키를 만들지 않는다). **기본은 5초**(`P/http/OutboundHttpProperties.kt:10`)이고 요청마다 주는 `timeout()` 으로는 이 값을 넘길 수 없다 — 연결 쪽 제한이 클라이언트 단위로 따로 걸린다(`P/http/DefaultExternalHttpClient.kt:273-280`). 5초면 정상 승인도 "모름"이 되기 쉬우므로 장부를 쓰는 앱은 yml 에서 30초로 올린다(샘플이 그렇게 한다, 가드가 짧으면 경고한다). 조회는 요청 단위로 10초 |

2단계의 자동결제는 같은 키 묶음으로 `POST /v1/billing/authorizations/issue` · `POST /v1/billing/{billingKey}` 를 쓴다(§12.1).

## 7. HTTP 계약

`payment-jdbc` 가 없으면 아무 경로도 열리지 않는다(지금과 같다). 있으면 `skeleton.payment.http.base-path`(기본 `/api/v1/payments`) 아래에 열린다 — 다른 모듈의 옵트인 HTTP 와 같은 방식(`AutoConfiguration` 의 `@Bean` + `@ConditionalOnMissingBean`, 호출자는 `Authentication.name`, 익명 거절, `http.enabled=false` 로 끈다). 응답은 플랫폼 표준 봉투(`{ value | values, meta }`), 목록은 커서. `docs/modules/payment.md` 의 「왜 HTTP 엔드포인트가 없나」 절은 "세 조건이 이 설계로 채워졌다"로 바꿔 쓴다.

**공통 모양**

```jsonc
// 주문
{ "id": "ord_…", "status": "PAID", "kind": "ONE_TIME", "itemType": "cheer", "itemRef": "cheer-3000", "quantity": 1,
  "orderName": "응원하기 3,000원", "amount": { "amount": 3000, "currency": "KRW" }, "refundedAmount": 0, "refundableAmount": 3000,
  "refundPending": false, "method": "CARD", "card": { "issuer": "HYUNDAI", "last4": "1234" } /* | null */, "testMode": true, "failureCode": null,
  "createdAt": "…Z", "paidAt": "…Z", "fulfilled": true }
// 환불
{ "id": "rfd_…", "orderId": "ord_…", "status": "REQUESTED", "amount": { "amount": 3000, "currency": "KRW" },
  "reasonCode": "CUSTOMER_REQUEST", "origin": "USER", "requestedAt": "…Z", "completedAt": null }
```

사용자 응답에는 결제사 이름 · 결제사 결제 번호 · `review_reason` · 운영자 id 가 없다. 운영자 응답에만 있다. `card`(`issuer` · `last4`)는 사용자 · 운영자 응답 **둘 다**에 있고, 카드 결제가 아니거나 계정이 지워져 두 칸이 비워졌으면 `null` 이다(`issuer` 와 `last4` 는 각각 `null` 일 수도 있다).

**사용자** (로그인 필요)

| 메서드 · 경로 | 하는 일 | 응답 · 오류 |
|---|---|---|
| `GET /config` | 결제를 쓸 수 있는가 | `{ available, testMode, refundRequestsEnabled }` — 결제사 빈이 없으면 `available: false` |
| `POST /orders` `{ itemType, itemRef, quantity? }` + `Idempotency-Key` | 주문을 만들고 결제창에 넘길 값을 받는다 | `201 { order, checkout }` · 같은 키 다시 → `200` 같은 것 · 헤더 없음 `400` · 같은 키 다른 본문 `409 PAYMENT.IDEMPOTENCY_CONFLICT` · `422 PAYMENT.ITEM_NOT_PURCHASABLE` · `503 PAYMENT.UNAVAILABLE`. `PaymentCatalog` 빈이 없으면 이 경로는 없다 |
| `POST /orders/{id}/confirm` `{ providerPaymentId, amount }` | 결제창에서 돌아온 결제를 승인한다 | `200 order(PAID)` · `202 order(CONFIRMING)` · `400 PAYMENT.AMOUNT_MISMATCH` · `402 PAYMENT.DECLINED`(`data.providerCode` · `data.category`) · `409 PAYMENT.ORDER_NOT_PAYABLE` · `409 PAYMENT.MODE_MISMATCH` |
| `POST /orders/{id}/abandon` | 결제창에서 그만뒀다 | `200 order` (이미 끝난 주문이면 지금 상태 그대로 — 오류가 아니다) |
| `GET /orders/{id}` | 한 건. 결과 화면이 `CONFIRMING` 동안 이것을 되풀이해 부른다 | `200 { order, refunds }` |
| `GET /orders?cursor=&size=` | 내 결제 내역(최신순). `PENDING` 은 빼고 준다(결제하지 않은 시도) | `200 values + cursor` |
| `POST /orders/{id}/refund-requests` `{ reasonCode, amount? }` + `Idempotency-Key` | 환불을 요청한다 | `201 refund(REQUESTED)` · `409 PAYMENT.REFUND_NOT_ALLOWED`(결제된 주문이 아님 · 꺼져 있음) · `409 PAYMENT.REFUND_IN_PROGRESS` · `400 PAYMENT.REFUND_AMOUNT_EXCEEDED` |
| `POST /refunds/{id}/withdraw` | 내 요청을 거둔다(`REQUESTED` 일 때만) | `200 refund` · `409 PAYMENT.REFUND_NOT_ALLOWED` |
| 남의 것 · 없는 것 | | 모두 같은 `404 PAYMENT.ORDER_NOT_FOUND` · `404 PAYMENT.REFUND_NOT_FOUND` |

`checkout` — 결제사 모듈이 만들고 프론트가 `kind` 로 갈라 연다. 공개해도 되는 값만 담는다.

```jsonc
{ "provider": "toss", "kind": "TOSS_PAYMENT_WINDOW", "clientKey": "test_ck_…", "testMode": true,
  "orderId": "ord_…", "orderName": "응원하기 3,000원", "amount": { "amount": 3000, "currency": "KRW" } }
```

돌아올 주소(`successUrl` · `failUrl`)는 프론트가 정해 결제창에 넘긴다 — 서버는 받지도 저장하지도 않는다.

**운영자** (`skeleton.payment.admin.enabled`, 기본 꺼짐. 기본 경로 `/api/v1/admin/payments`. `PaymentOperatorPolicy` 를 매 호출 확인)

| 메서드 · 경로 | 하는 일 |
|---|---|
| `GET /orders?status=&accountId=&q=&review=&cursor=` | 목록. `q` 는 주문 id 또는 결제사 결제 번호의 정확한 값. `review=true` 는 확인 필요만 |
| `GET /orders/{id}` | 한 건 + 환불 + 이력(`payment_events`) + 결제사 · 결제사 결제 번호 · `reviewReason` |
| `POST /orders/{id}/refunds` `{ amount?, reasonCode }` + `Idempotency-Key` | 바로 환불. `200 refund(SUCCEEDED)` · `202 refund(PENDING)` · `402 PAYMENT.REFUND_DECLINED` · `409 PAYMENT.REFUND_IN_PROGRESS` · `400 PAYMENT.REFUND_AMOUNT_EXCEEDED` |
| `GET /refunds?status=REQUESTED&cursor=` | 환불 요청 목록 |
| `POST /refunds/{id}/approve` · `POST /refunds/{id}/reject` | 요청을 승인(→ 위와 같은 환불 실행) · 거절 |
| `POST /orders/{id}/sync` | 「결제사와 맞추기」 — 조회 → `converge`. 확인 필요가 풀리면 `reviewReason` 이 비워진다 |
| `GET /webhook-events?status=&cursor=` · `POST /webhook-events/{id}/retry` | 수신함 보기 · 다시 처리 |

**웹훅** (공개 경로 — `PublicEndpointContributor`. 인증 없음, §6.5 의 진위 확인)

| 메서드 · 경로 | 응답 |
|---|---|
| `POST /webhooks/{provider}` · `POST /webhooks/{provider}/{token}` | `200`(받았다 · 중복 · 모르는 주문 · 다루지 않는 이벤트 — 구별하지 않는다) · `404`(모르는 결제사 · 비밀 조각 틀림) · `401`(서명 틀림) · `413`(본문이 `webhook.max-body-bytes` 초과) · `500`(저장 실패 — 결제사가 다시 보낸다) |

**오류 코드** (`PaymentErrorCode` 에 더한다. 지금의 셋 `PROVIDER_NOT_FOUND` · `ROUTING_FAILED` · `PROVIDER_ERROR` 는 그대로): `ORDER_NOT_FOUND` 404 · `REFUND_NOT_FOUND` 404 · `ITEM_NOT_PURCHASABLE` 422 · `IDEMPOTENCY_CONFLICT` 409 · `AMOUNT_MISMATCH` 400 · `ORDER_NOT_PAYABLE` 409 · `MODE_MISMATCH` 409 · `DECLINED` 402 · `REFUND_NOT_ALLOWED` 409 · `REFUND_IN_PROGRESS` 409 · `REFUND_AMOUNT_EXCEEDED` 400 · `REFUND_DECLINED` 402 · `CAPABILITY_UNSUPPORTED` 400 · `UNAVAILABLE` 503. `docs/errors.md` 와 프론트 `packages/payment/src/errors.ts` 에 같이 더한다.

계약 문서는 `docs/payment-http-contract.md` 로 따로 쓴다(프론트가 읽는 정본). OpenAPI 기여는 `payment` 의 `openapi/` 에.

## 8. 계정 탈퇴 · 익명화와 결제 기록 보존

### 8.1 법령 — 확인한 것과 못 한 것

법률 자문이 아니다. 근거 조문과 기간만 적는다. 판단은 사업자(앱을 운영하는 쪽)의 몫이다.

| 근거 | 내용 | 확인 |
|---|---|---|
| 전자상거래 등에서의 소비자보호에 관한 법률 **제6조 제1항** | 사업자는 표시 · 광고, 계약 내용 및 그 이행 등 **거래에 관한 기록을 상당한 기간 보존**해야 하고, 소비자가 쉽게 **열람 · 보존할 수 있는 방법**을 제공해야 한다 | 조문 확인(2013년 개정본의 문장 — 현행 문장과 같은지는 미확인) |
| 같은 법 **제6조 제2항** | 보존해야 할 거래 기록과 그에 관련된 개인정보(거래의 주체를 식별할 수 있는 정보로 한정)는 소비자가 **개인정보 이용 동의를 철회해도 보존할 수 있다** | 조문 확인(위와 같은 판 — 괄호 안 예시 문구가 현행과 같은지는 미확인) |
| 같은 법 **시행령 제6조 제1항** | 보존 기간 — 표시 · 광고 **6개월** / 계약 또는 청약철회 등 **5년** / **대금결제 및 재화등의 공급 5년** / 소비자의 불만 또는 분쟁처리 **3년** | 확인(법령 열람 사이트의 현행본) |
| 같은 시행령 제6조 제2항 | 열람 방법: 사이버몰에서 소비자가 볼 수 있게 하거나 요청하면 열람 · 복사하게 한다. **동의를 철회한 소비자의 기록은 그렇지 않은 기록과 따로 보존한다** | 확인(같은 출처의 요약 — 조문 원문 대조는 못 함) |
| 개인정보 보호법 제21조 | 보유 기간이 지나면 지체 없이 파기하되 **다른 법령에 따라 보존해야 하면 예외**, 이때 다른 개인정보와 분리해 저장 · 관리 | 2차 자료의 요약으로만 확인 — **조문 원문 미확인** |
| 국세기본법(장부 · 증빙 보존) · 부가가치세법 · 전자금융거래법 | — | **미확인.** 전자금융거래법의 기록 보존은 전자금융업자(결제사)의 의무로 알고 있으나 확인하지 않았다 |

이 스켈레톤에 주는 뜻: ① 결제 줄은 탈퇴해도 **지우면 안 되는 쪽이 기본**이다(대금결제 기록 5년) ② 회원에게는 자기 결제 내역을 볼 길이 있어야 한다(「내 결제 내역」 화면이 그것) ③ 보존은 "무기한"이 아니라 기간이 있다 — 기간이 지난 줄을 지우는 것은 앱의 정책이다.

### 8.2 설계

- **줄에 개인정보를 두지 않는 것이 첫 번째 방어다.** 주문 · 환불 · 이력 · 수신함 어디에도 이름 · 이메일 · 전화 · 전체 카드 번호 · 자유 문장이 없다(§5). 개인정보로 볼 수 있는 칸은 `payment_orders.card_issuer` · `card_last4` 둘뿐이다 — 탈퇴 때 **지울 칸은 이 둘뿐**이고, 나머지 줄은 그대로 남으므로 보존 의무와 익명화가 부딪히지 않는다.
- `payment` 가 `AccountErasureListener`(`P/erasure/Erasure.kt:18`)를 구현한다 — 장부가 있을 때만 등록. **멱등**이고 업무 이유로 던지지 않는다.

| 대상 | 계정이 지워질 때 |
|---|---|
| 주문 줄 | **남는다.** `account_erased_at` 을 찍는다(탈퇴자 기록의 구분 · 보존 기간의 기준). `PENDING` 은 `CANCELED` 로 닫는다(결제할 사람이 없다). `CONFIRMING` 은 건드리지 않는다 — 조회 작업이 끝낸다 |
| `card_issuer` · `card_last4` | 그 계정의 **모든 주문 줄에서 NULL 로 지운다**(멱등 — 이미 비어 있으면 아무 일도 없다). 줄의 나머지 칸은 건드리지 않는다. `CONFIRMING` 이던 줄이 나중에 `PAID` 가 되며 두 칸이 다시 채워지지 않도록, 두 칸은 `account_erased_at` 이 찍힌 줄에는 쓰지 않는다 |
| `account_id` · `requested_by` · `decided_by` · `actor_id` | `skeleton.payment.erasure.account-id` — `KEEP`(기본): 그대로 / `TOMBSTONE`: `AccountTombstone` 값으로 바꾼다. 정해진 것 2 |
| 열린 환불 요청(`REQUESTED`) | 남는다 — 운영자가 처리할 수 있다. 환불은 원래 결제 수단으로 돌아가므로 계정이 없어도 된다 |
| 결제사 쪽 | 1단계에는 할 일이 없다(결제창에 구매자 정보를 넘기지 않았고 저장된 결제 수단이 없다). 2단계는 빌링키 삭제 · 구독 해지가 더해진다(§12.1) |

- `KEEP` 을 기본으로 추천하는 이유: 계정 탈퇴의 기본(`deletion.mode=ANONYMIZE`)은 계정 줄을 `ERASED` 로 남기고 **계정 id 는 그대로 둔다**(`docs/accounts.md` 「삭제 수명주기」 — "탈퇴 뒤에도 계정 id · 가입 시각 · 탈퇴 시각이 남는다"). 결제 줄의 계정 id 는 그 익명 줄을 가리킬 뿐이다. 그리고 `KEEP` → `TOMBSTONE` 은 나중에 할 수 있지만 반대는 못 한다.
- 탈퇴한 사람의 결제 내역은 **사용자 경로로는 아무도 볼 수 없다**(로그인할 계정이 없다). 운영자는 주문 id · 결제사 결제 번호로 찾아 환불할 수 있다. 운영자 목록은 `account_erased_at` 이 찍힌 줄을 기본으로 숨기고 `includeErased=true` 로만 보인다(따로 보존한다는 요구를 표의 구분으로 답한다 — 물리적으로 다른 표가 필요한지는 미확인).
- `AccountDataExporter`(`section = "payments"`)를 구현한다 — 주문 · 환불을 응답과 같은 모양으로(`card` 포함 — 지워지기 전의 본인 데이터다). (지금 이 고리를 구현한 모듈은 `legal` 뿐이고 모으는 엔드포인트는 앱의 몫이다.)
- **보존 기간이 지난 줄 지우기는 만들지 않는다**(비목표). 5년 뒤의 일이고 기간은 앱의 정책이다. `account_erased_at` 과 `paid_at` 이 있어 나중에 실행기를 더해도 표를 고칠 필요가 없다.
- 앱의 개인정보 처리방침에 적을 것(앱의 몫 — `docs/accounts.md` 의 "처리방침에 적을 것" 목록에 한 줄 더한다): 결제 기록(주문 번호 · 상품 · 금액 · 결제 수단 종류 · 결제사 결제 번호 · 시각)을 탈퇴 뒤에도 법정 기간 보관한다는 것, 카드사와 카드 번호 끝 네 글자는 탈퇴 때 지운다는 것, 결제 처리를 결제사에 맡긴다는 것.

## 9. 설정 · 비밀 · 배포 가드

### 9.1 `skeleton.payment` — `docs/config/modules/payment.yml` (지금의 `default-provider` · `providers` 는 그대로)

| 키 | 기본 | 뜻 |
|---|---|---|
| `checkout-ttl` | `30m` | 만든 주문으로 결제할 수 있는 시간. 지나면 만료 작업이 결제사에 묻고 닫는다 |
| `reconcile.first-delay` | `60s` | 승인 선점 뒤 첫 조회까지. 결제사 응답 제한 시간보다 길어야 한다 |
| `reconcile.max-attempts` | `12` | 조회 작업의 시도 수 |
| `refund.requests-enabled` | `true` | 본인이 환불 **요청**을 만들 수 있다(돈은 운영자가 움직인다) |
| `refund.settle-delay` | `2m` | 환불 선점 뒤 조회까지 |
| `webhook.enabled` | `true` | 웹훅 경로를 연다(`PaymentWebhookGateway` 빈이 있을 때만 의미가 있다) |
| `webhook.path-token` | (비어 있음) | 주소 끝의 비밀 조각. 서명 없는 결제사에서는 보호 환경 필수 |
| `webhook.max-body-bytes` | `65536` | |
| `http.enabled` · `http.base-path` | `true` · `/api/v1/payments` | 장부가 있을 때만 |
| `admin.enabled` · `admin.base-path` · `admin.role` | `false` · `/api/v1/admin/payments` · `ADMIN` | 운영자 경로 |
| `erasure.account-id` | `KEEP` | §8.2 |

장부를 켜는 스위치는 없다 — `payment-jdbc` 의 유무가 스위치다. `payment-jdbc` 는 설정 접두사가 없다(작업의 폴링 · 백오프는 `skeleton.job-queue`).

### 9.2 `skeleton.payment-toss` — 더하는 키

| 키 | 기본 | 뜻 |
|---|---|---|
| `client-key` | (비어 있음) | 결제창을 여는 공개 키. 서버가 `checkout` 응답으로 프론트에 준다 — **프론트 환경변수를 두지 않는다**(시크릿 키와 같은 묶음임을 한 곳에서 지킨다) |
| `lookup-path` · `lookup-by-order-path` | `/v1/payments/{paymentKey}` · `/v1/payments/orders/{orderId}` | |
| `lookup-timeout` | `10s` | 조회 한 번의 제한 시간(요청 단위 — 클라이언트 제한보다 짧을 때만 뜻이 있다) |
| `allow-test-keys-in-protected-env` | `false` | 보호 환경에서 테스트 키로 서는 것을 일부러 허락한다(아래) |

승인 · 환불의 제한 시간은 이 모듈의 키가 아니라 **앱 yml 의 `skeleton.http.clients.payment-toss.response-timeout`** 이다(§6.6). 모듈은 플랫폼 기본값(5초)을 바꾸지 않는다 — 샘플 앱이 `30s` 로 정하고, `reconcile.first-delay` 가 이 값보다 길지 않으면 기동 실패, 장부가 있는데 10초보다 짧으면 가드 경고.

**환경변수 이름** (`<P>` = 앱의 접두사, 스켈레톤은 `SKELETON`)

| 값 | 이름 | 어디에 |
|---|---|---|
| 켜기 | `<P>_PAYMENT_TOSS_ENABLED=true` | 선언의 `env:` · 로컬 `.env.local` |
| 시크릿 키 | `<P>_PAYMENT_TOSS_SECRET_KEY` — 샘플 · 워크벤치는 `TOSS_PAYMENTS_SECRET_KEY` 별칭도 받는다(지금 그대로) | 선언의 `secrets:`(운영자) + `data/secrets/<앱>.local.env` · 로컬 `.env.local` |
| 클라이언트 키 | `<P>_PAYMENT_TOSS_CLIENT_KEY` — 별칭 `TOSS_PAYMENTS_CLIENT_KEY` | 비밀이 아니다 → 선언의 `env:` · 로컬 `.env.local` |
| 웹훅 비밀 조각 | `<P>_PAYMENT_WEBHOOK_PATH_TOKEN` | 선언의 `secrets:` — **운영자 값**으로 둔다(결제사 개발자센터에 등록하는 주소에 그대로 들어가므로 무작위로 만들어진 값을 읽어 올 수 있어야 한다) |

`.env.example` · `deploy/app.yaml` 의 `[payment-toss]` 줄 · `docs/deploy.md` §7 표 · `docs/new-project-recipe.md` 를 함께 고친다. 주인의 `.env.local` 에는 이미 `SKELETON_PAYMENT_TOSS_ENABLED=true` · `TOSS_PAYMENTS_SECRET_KEY` 가 있다 — `TOSS_PAYMENTS_CLIENT_KEY` 한 줄만 더하면 된다(같은 묶음의 `test_ck_…`).

### 9.3 배포 가드 — 키를 잘못 끼우는 실수

`payment-toss` 가 `DeployGuard`(`P/deploy/DeployGuard.kt`) 하나를 낸다. 메시지에는 **환경변수 이름만** 적고 값 · 접두사 뒤의 글자는 싣지 않는다.

| 상황 | 결과 |
|---|---|
| 켰는데 시크릿 키가 비었다 | 지금과 같다(제공자 빈이 없다) + 가드 경고 |
| 클라이언트 키와 시크릿 키의 **모드가 다르다**(`test_` ↔ `live_`) | **기동 실패** — 어느 환경이든 |
| 두 키의 **종류가 다르다**(`ck`↔`gsk` · `gck`↔`sk`) 또는 결제위젯 키 묶음(`gck` · `gsk`)이다 | **기동 실패** — 이 모듈은 결제창(API 개별 연동 키)만 연동한다 |
| 장부가 있는데 클라이언트 키가 비었다 | 보호 환경에서 기동 실패, 그 밖은 경고(`GET /config` 가 `available: false`) |
| `skeleton.env=local` 또는 스프링 프로필 `local` · `test` 인데 **실결제 키**(`live_`) | **기동 실패** — 로컬에서 진짜 돈이 나가지 않게 |
| `skeleton.env=stage\|prod` 인데 **테스트 키**(`test_`) | **기동 실패.** `allow-test-keys-in-protected-env=true` 면 경고로 내려간다 — 사업자등록 전의 시연 배포가 이 길이다 |
| 보호 환경 + 서명 없는 웹훅 결제사 + `webhook.path-token` 이 비었다 | 기동 실패(`payment` 의 가드) |
| 보호 환경 + 장부 + `job-queue` 워커가 꺼져 있다 | 경고 — 조회 · 지급 · 웹훅 처리가 돌지 않는다 |

가드 밖의 두 겹: ① 주문 줄의 `provider_mode` 가 지금 키의 모드와 다르면 승인 · 환불을 거절한다(다른 환경의 DB 를 복사해 온 경우) ② 응답의 `testMode` 로 화면이 「테스트 결제 — 실제로 돈이 나가지 않아요」를 늘 보인다(§11).

**배포 선언**: `deploy/app.yaml` 의 `secrets:` 에 `<P>_PAYMENT_WEBHOOK_PATH_TOKEN` 한 줄, 설명 줄에 `[payment-toss] (env:) … _CLIENT_KEY` 와 `[payment-jdbc]` 를 더한다. 웹훅 주소는 공개 도메인의 `https://<도메인>/api/v1/payments/webhooks/toss/<token>` — Cloudflare Tunnel 뒤에서 그대로 닿는다(새 포트 · 새 경로 규칙이 필요 없다, `api_prefix` 안이다). `scripts/test-deploy-contract.sh` 가 켜진 조합에서 가드 문구를 확인한다.

## 10. 로컬에서 웹훅 시험하기

토스의 결제 웹훅은 서명이 없고 우리는 본문을 믿지 않고 조회하므로, **로컬에서는 웹훅을 직접 만들어 보내도 진짜와 같은 길을 탄다.**

| 방법 | 어떻게 | 무엇을 확인하나 |
|---|---|---|
| ① 손으로 보내기 (터널 없음) | 테스트 키로 실제 결제를 한 번 한 뒤 `scripts/payment-webhook.sh <주문 id>` — 수신함 주소로 `PAYMENT_STATUS_CHANGED` 모양의 본문(결제 키 · 주문 번호)을 보낸다. 처리기는 **토스 테스트 서버에 진짜로 조회**한다 | 수신 → 조회 → `converge` 전체. 같은 것을 두 번 보내 중복, 없는 주문 번호로 무시, 틀린 비밀 조각으로 404 |
| ② 진짜 웹훅 받기 | 임시 공개 주소를 연다 — `cloudflared tunnel --url http://localhost:8080`(계정 없이 되는 빠른 터널) 또는 ngrok(토스 문서가 안내하는 방법). 토스 개발자센터의 웹훅 메뉴에 `https://<임시 주소>/api/v1/payments/webhooks/toss/<token>` 을 `PAYMENT_STATUS_CHANGED` 로 등록 | 토스가 실제로 보내는 헤더 · 본문 모양, 재전송(서버를 내려 두었다 올린다), 개발자센터의 관리 화면에서 취소했을 때 오는 이벤트 |
| ③ 자동 시험 | 가짜 결제사 서버(§13) | 타임아웃 · 순서 뒤바뀜 · 유실 — 진짜 결제사로는 일으키기 어려운 것 |

- ② 는 개발자센터에 로그인해 웹훅을 등록할 수 있어야 한다. 문서용 공개 테스트 키로는 웹훅을 쓸 수 없다(§17) — 주인의 키는 가입 뒤 받은 것이라 된다.
- 임시 주소는 띄울 때마다 바뀐다. 끝나면 개발자센터의 등록을 지운다(죽은 주소로 재전송이 3일 넘게 간다).
- 결제사 거절 재현: 테스트 시크릿 키로 호출할 때 `TossPayments-Test-Code: <오류 코드>` 헤더를 붙이면 그 오류가 그대로 돌아온다(§17). `scripts/check-real-providers.sh` 에 "토스 키가 유효한가 · 거절 코드가 `DECLINED` 로 옮겨지는가" 검사를 더한다(지금 스크립트가 소셜 · 메일에 하는 것과 같은 방식).
- 순서는 `docs/real-provider-setup.md` 에 「토스페이먼츠(테스트 키)」 절로 적는다.

## 11. 프론트 (react-skeleton)

### 11.1 `@skeleton/payment` — 얇은 패키지에서 "결제 한 벌"로

지금 것(`createPaymentApi` · `confirmRequestFromTossRedirect` · 계약 타입 · 오류 판별)은 **그대로 둔다** — 장부 없이 자기 컨트롤러를 둔 앱이 계속 쓴다. 더하는 것:

| 더하는 것 | 내용 |
|---|---|
| `createPaymentLedgerApi(client, { basePath = '/payments' })` | §7 의 사용자 경로. 모듈이 HTTP 를 여니 이제 기본 경로가 있다. 만들기 · 환불 요청에는 `Idempotency-Key`(`newIdempotencyKey()`) |
| 훅 | `usePaymentConfig` · `useCreateOrder` · `useConfirmOrder` · `useOrder(id)`(`CONFIRMING` 동안만 되풀이해 묻는다 — 간격을 늘려 가며, 끝 상태면 멈춘다) · `useOrders` · `useRequestRefund` · `useWithdrawRefund`. 키는 한 뿌리, 변경 훅은 성공하면 뿌리를 무효화 |
| `openCheckout(checkout, { successUrl, failUrl, loadSdk? })` | `checkout.kind` 로 갈라 결제창을 연다. `TOSS_PAYMENT_WINDOW`: 토스 SDK v2 스크립트(`https://js.tosspayments.com/v2/standard`)를 **누른 그때** 불러와 §6.6 의 호출을 한다. npm 의존을 더하지 않는다. 불러오는 함수는 주입할 수 있다(시험 · 스토리). 모르는 `kind` 는 던진다. SSR 안전 — import 할 때 `window` 를 읽지 않는다 |
| `readCheckoutReturn(search)` | 돌아온 주소 → `{ ok: true, orderId, providerPaymentId, amount }` 또는 `{ ok: false, orderId, code }`. 결제사가 준 `message` 는 화면에 그대로 쓰지 않는다(우리 문구로 옮긴다) |
| 부품 (문구는 `labels` prop, 스토리 + `play`, `docs/ui-catalog.md`, `tests/support/ssrFixtures.ts`) | `OrderStatusBadge` · `OrderList`(결제 내역 — 카드 표시 포함) · `PaymentResult`(결과: 됐어요 / 확인 중 / 안 됐어요 / 그만뒀어요) · `OrderSummary`(한 건 + 카드 표시 + 환불 줄) · `RefundRequestDialog` · `TestModeNotice` |
| `@skeleton/payment/admin` (하위 경로 — `@skeleton/auth/admin` 과 같은 방식) | `createPaymentAdminApi` · `AdminOrderTable` · `AdminOrderPanel`(이력 · 「결제사와 맞추기」) · `AdminRefundDialog` · `RefundRequestTable` · `WebhookEventTable` |

`package.json` 의 런타임 의존에 `@skeleton/ui` · `@skeleton/time` 이 더해진다(둘 다 스타터에 이미 있다). `capabilities.json` 의 항목(`summary` · `notFor` · `needs` · `backend`)과 README 를 고친다 — "결제창 UI 는 없다" · "기본 경로를 가정하지 않는다"가 더는 사실이 아니다.

### 11.2 화면 원칙

- **눌리는 요소는 눌리게 보인다.** 누를 수 있는 것은 `Button` 이나 링크 모양뿐이다. 표의 줄 전체를 조용히 눌리게 만들지 않는다 — 줄 끝에 「자세히」 단추를 둔다. 상태 배지는 눌리지 않고, 눌리지 않게 보인다. 못 누르는 단추는 흐리게 하고 **왜 못 누르는지 옆에 한 줄** 적는다(「환불 중이에요」).
- **쉬운 한국어 낱말 하나로 통일한다.** 아래 표가 정본이고 `ko.ts` 와 부품 기본 문구 시험이 이 낱말만 쓰는지 본다.

| 화면 낱말 | 쓰지 않는 말 | 코드 이름 |
|---|---|---|
| **결제** · 「결제하기」 · 「결제 내역」 | 주문 · 구매 내역 · 오더 · 트랜잭션 | order |
| **환불** · 「환불 요청」 | 취소 · 결제 취소 · 캔슬 (토스 API 는 cancel 이지만 화면에는 「환불」 하나) | refund |
| 「결제 확인 중」 | 승인 대기 · 처리 중 · 펜딩 | `CONFIRMING` |
| 「결제됨」 · 「결제 안 됨」 · 「시간이 지났어요」 · 「그만뒀어요」 | 성공 · 실패 · 만료 · 취소됨 | `PAID` · `FAILED` · `EXPIRED` · `CANCELED` |
| 「환불 요청함」 · 「환불 중」 · 「환불됨」 · 「일부 환불됨」 · 「환불 안 됨」 | 접수 · 처리 대기 · 반려 | `REQUESTED` · `PENDING` · `REFUNDED` · `PARTIALLY_REFUNDED` · `REJECTED`/`FAILED` |
| 「테스트 결제」 | 샌드박스 · 테스트 모드 | `testMode` |
| 「결제사와 맞추기」 (운영자) | 동기화 · 싱크 · 대사 | sync |
| **응원** · 「응원하기」 · 「내 응원 내역」 (샘플) | 후원 · 기부 · 도네이션 · 구매 | cheer |

- 금액은 `Intl.NumberFormat` 으로 통화에 맞게(`3,000원`). 시각은 `@skeleton/time` 의 `formatInstant`.
- 카드 결제는 「현대카드 ····1234」 모양으로 보인다(`····` 뒤에 끝 네 글자를 그대로 — `*` 가 섞여 있으면 그대로). 카드사 **코드 → 이름은 프론트 문구**(`labels`)이고, 문구에 없는 코드는 「카드」, 끝 네 글자가 없으면 이름만, 둘 다 없으면 수단 종류(「카드」 · 「간편결제」)만 보인다.
- 「결제 확인 중」은 끝없이 돌지 않는다: 일정 시간 뒤에는 "확인이 늦어지고 있어요. 결제 내역에서 다시 볼 수 있어요"로 바꾸고 「결제 내역」 단추를 준다(확인이 끝나지 않아도 사용자가 갇히지 않는다 — 운영 교훈).
- 실패 문구는 종류(`data.category`)로 고른다: 한도 · 잔액 / 카드사 거절 / 일시 장애 / 그 밖. 결제사 코드는 `ErrorReference`(참조 번호) 안에만.
- `testMode` 면 결제 단추 가까이와 결과 화면에 `TestModeNotice`(「테스트 결제예요. 실제로 돈이 나가지 않아요」)가 늘 보인다.

### 11.3 `apps/sample` 의 화면

| 경로 | 화면 | 출발 Pattern |
|---|---|---|
| `/cheer` | 응원 금액 고르기(`GET /api/v1/cheers/options` 의 서버 목록 — 1,000 · 3,000 · 5,000원) + 「응원하기」 채운 단추 **하나** + 테스트 표시 → 누르면 주문을 만들고 결제창을 연다. 로그인 안 했으면 로그인 뒤 돌아온다 | Patterns/폼 |
| `/payments/return` | 결제창에서 돌아오는 곳(성공 · 실패 둘 다). 성공이면 승인을 부르고 결과를 보인다. 새로 고쳐도 다시 승인하지 않는다(서버가 같은 답을 준다) | Patterns/상세 (로딩 · 오류 상태) |
| `/cheers` | **내 응원 내역**(`GET /api/v1/cheers/me`) — 금액 · 시각 · 카드 표시(「현대카드 ····1234」) · 상태 배지 「결제됨」 / 「일부 환불됨」 / 「환불됨」(눌리지 않는 배지) · 줄 끝 「자세히」 단추 | Patterns/목록 |
| `/payments/:id` | 한 건 + 환불 줄 + 「환불 요청」(확인 대화상자). 「자세히」가 여기로 온다 | Patterns/상세 |
| `/settings` | 「내 응원 내역」 링크 한 줄 | 지금 화면에 구역 하나 |
| `/admin/payments` · `/admin/payments/:id` | 운영자: 목록(확인 필요 · 환불 요청 탭) · 한 건(이력 · 환불 · 맞추기) | Patterns/목록 · 상세, `RequireRole` 아래 |

랜딩의 요금제 표는 이 여정과 이어지지 않는다(손대지 않는다). 응원하기로 들어가는 길은 메뉴 · 랜딩의 「응원하기」 링크 하나다.

`ko.ts`(기본) · `en.ts`, 화면마다 `handle.seo`(모두 비공개). `README.md` 의 「화면 → Pattern 지도」에 줄을 더한다.

## 12. 2단계 · 3단계 — 표를 뜯지 않고 자라는 방법

요지: **결제가 한 번 일어날 때마다 `payment_orders` 한 줄**이라는 규칙은 구독에서도, 다른 결제사에서도 같다. 그래서 환불 · 조회 · 웹훅 · 내역 화면은 1단계 것을 그대로 쓴다. 2 · 3단계는 **새 표와 새 능력 인터페이스를 더할 뿐**이고 1단계의 표에는 `alter` 가 없다.

| 1단계에 미리 둔 것 | 2 · 3단계에서 쓰이는 곳 |
|---|---|
| `payment_orders.kind` | `SUBSCRIPTION_INITIAL` · `SUBSCRIPTION_RENEWAL` |
| `origin_type` · `origin_ref` + 인덱스 | `SUBSCRIPTION` · 구독 id — 한 구독의 결제 이력 |
| `dedupe_key` (유니크) | `sub:<구독 id>:<주기 시작>` — **한 주기에 청구 줄이 하나**임을 DB 가 지킨다(실행기가 두 번 돌아도, 두 인스턴스가 겹쳐도) |
| `paid_amount` · `tax_amount` | 결제사가 세금을 얹어 받는 방식(판매 대행)에서 실제 받은 총액과 세금 |
| `currency` + 최소 단위 정수 | USD 등. 통화별로 따로 집계하고 환산하지 않는다 |
| `provider` 가 줄마다 · 서버가 고른다 | 국내 = 토스, 해외 = 다른 결제사 |
| 능력 인터페이스가 기능별 | 새 결제사는 할 수 있는 것만 구현한다 |
| `payment_webhook_events.resource_type` · 자원 종류별 처리기 | `SUBSCRIPTION` · `BILLING_METHOD` 이벤트 |
| `converge` 한 곳 | 결제사 관리 구독의 청구도 "조회 → 맞추기"로 들어온다 |
| `WebhookEnvelope.storedPayload` · `PaymentWebhookGateway.signed` | 서명된 본문이 사실인 결제사 |

### 12.1 2단계 — 구독 (새 모듈 `payment-subscription` + `-jdbc`, `payment` 에 `api` 의존)

**새 표 (새 V 파일, `payment-subscription-jdbc`)**

| 표 | 핵심 열 | 제약 |
|---|---|---|
| `payment_billing_methods` | `id` · `account_id` · `provider` · `provider_customer_ref`(결제사에 준 고객 키 — 계정 id 가 아닌 무작위 값) · `provider_billing_ref`(빌링키 — **암호화 저장**, `crypto` 의 `TextEncryptor`) · `status`(ACTIVE · DELETED) · `created_at` · `deleted_at` | 카드 번호(끝 네 글자 밖의 어떤 부분도) · 이름은 저장하지 않는다(표시가 필요하면 1단계 주문 줄과 같은 카드사 코드 + 끝 네 글자까지만, 탈퇴 때 빌링키와 함께 지운다) |
| `payment_subscriptions` | `id` · `account_id` · `provider` · `management`(`SELF_BILLED` · `PROVIDER_MANAGED`) · `plan_code` · `status` · `provider_subscription_id` · `billing_method_id` · `currency` · `amount`(이번 주기에 청구할 금액 — 가입 때 박는다) · `current_period_start` · `current_period_end` · `next_charge_at` · `cancel_at_period_end` · `grace_until` · `pending_plan_code` · `live_key`(살아 있는 동안 = `account_id` + 구독 묶음 코드, 끝나면 NULL — **계정당 살아 있는 구독은 하나**, 유니크) | 인덱스 `(status, next_charge_at)` · `(provider, provider_subscription_id)` |
| `payment_subscription_events` | `subscription_id` · `type` · `from_plan` · `to_plan` · `from_status` · `to_status` · `actor` · `created_at` | 추가만. **플랜을 바꿔도 구독 줄은 하나**이고 변경은 여기에 남는다 |

구독 상태: `INCOMPLETE → ACTIVE → PAST_DUE → (ACTIVE | EXPIRED)`, `ACTIVE → CANCELED`(주기 끝에). 해지 예약은 상태가 아니라 `cancel_at_period_end` 표시다. 체험 · 유예 일수 · 해지 정책은 설정이고 기본값은 중립이다(체험 없음 · 유예 0).

**새 능력 (`payment` 에 인터페이스, 결제사 모듈이 구현)**

```kotlin
interface PaymentBillingKeyGateway : PaymentGateway { fun issue(request: BillingKeyIssueRequest): BillingKey; fun delete(billingRef: String) }
interface PaymentChargeGateway : PaymentGateway { fun charge(request: ChargeRequest): PaymentOperationResult }      // 저장된 결제 수단으로 즉시 청구 (토스 빌링)
interface PaymentSubscriptionGateway : PaymentGateway { /* 결제사 관리 구독: 만들기 · 해지 · 되살리기 · 플랜 바꾸기 · 지금 상태 조회 */ }
```

**두 방식이 같은 장부로 모인다**

| | `SELF_BILLED` (토스 빌링키 — 우리가 청구한다) | `PROVIDER_MANAGED` (결제사가 청구한다) |
|---|---|---|
| 청구를 거는 쪽 | 정기 결제 실행기: `next_charge_at <= 지금` 인 `ACTIVE` · `PAST_DUE` 를 골라 주기마다 주문 줄(`dedupe_key`)을 **먼저 만들고** → 선점 → `charge` → `converge`. 1단계의 승인과 같은 세 걸음이고 "모름"은 같은 조회 작업이 끝낸다(운영 교훈: 청구 호출에 멱등 키와 조회 정산이 없어 "우리는 실패 · 결제사는 성공"이 될 수 있었다 — 여기서는 주문 줄이 먼저다) | 실행기는 돌지 않는다. 결제사의 청구 웹훅 → 조회 → 주문 줄(`dedupe_key` = 결제사 청구 번호)을 만들고 `converge` |
| 실패 | `PAST_DUE` + `grace_until` 까지 재시도, 지나면 `EXPIRED` | 결제사의 재청구 정책을 따른다. 유예가 끝나면 결제사 구독도 해지한다(늦은 자동 회복 결제를 막는다) |
| 해지 | `cancel_at_period_end` — 다음 청구를 걸지 않는다 | 결제사에 해지를 걸고 주기 끝까지 권한 유지 |
| 플랜 변경 | 다음 주기부터(`pending_plan_code`). 즉시 변경 · 일할 계산은 만들지 않는다 | 결제사의 변경 API. 타임아웃이면 보상하지 않고 조회로 맞춘다 |

- 실행기를 깨우는 것: 인스턴스마다 도는 틱이 DB 임대를 쥔 하나만 "청구할 구독 찾기" 작업을 큐에 넣는다(`account` 의 탈퇴 정리와 같은 모양). 1단계에는 없는 주기 작업이 여기서 처음 생긴다.
- 요금제는 표가 아니라 설정이다: `skeleton.payment-subscription.plans.<코드>` = 이름 · 주기 · 통화별 금액 · 결제사별 상품 참조. 결제사별 열을 상품 표에 늘리지 않는다. 결제사가 가격을 들고 있는 방식은 기동할 때 결제사의 가격과 설정을 대조한다(화면과 실제 청구액이 달라지는 사고 방지 — 운영 교훈).
- 앱이 끼어드는 자리: `SubscriptionListener`(시작 · 갱신 · 연체 · 끝) — 권한을 주고 거두는 것은 앱이다.
- **탈퇴와 구독 — platform 에 고리 하나가 더 필요하다.** 지금의 `AccountErasureListener` 는 유예(30일)가 끝난 뒤에 불린다. 그 사이에 청구가 걸리면 안 되므로 "탈퇴를 요청한 순간"을 알리는 고리(`AccountClosingListener` — 요청 때 해지 예약, 탈퇴 취소 때 되돌림)를 `platform` 에 더하고 `account` 가 부른다. 지워질 때는 빌링키를 결제사에서 지우고 줄을 지운다.
- 토스의 자동결제는 추가 심사 · 계약이 필요하다(§17) — 사업자등록 전에는 테스트 키로만 돌려 볼 수 있다.

### 12.2 3단계 — 해외 결제사 · 통화 · 세금 (자리만)

- 새 결제사 모듈(판매 대행 방식의 결제사 하나 — 무엇으로 할지는 미정)은 `PaymentCheckoutGateway`(결제사 화면으로 보내는 주소) · `PaymentLookupGateway` · `PaymentRefundGateway` · `PaymentWebhookGateway`(`signed=true`)를 구현하고 `PaymentConfirmGateway` 는 구현하지 않는다. 프론트의 `openCheckout` 에 `kind: 'REDIRECT'` 한 갈래가 더해진다.
- 이 방식은 결제가 결제사 화면에서 끝난다 — 우리 서버의 승인 호출이 없다. 주문은 `PENDING` 에서 웹훅 → 조회 → `converge` 로 바로 `PAID` 가 된다(전이표의 `PENDING → PAID` 가 이 길이다). 닫힌 주문에 늦게 돈이 들어오는 일이 실제로 생기는 것도 이 방식이다(§3 16).
- 금액 규칙이 갈린다: 결제사가 세금을 얹어 받으면 `paid_amount ≠ amount` 가 정상이다. `converge` 의 금액 비교를 결제사가 고르는 정책(`EXACT` — 1단계 / `SUBTOTAL` — 세금을 뺀 값이 `amount` 와 같다)으로 만들고 **검사는 여전히 한 곳**에 둔다.
- 어느 결제사로 보낼지: 지금의 `PaymentProviderRouter`(통화 · 국가)를 쓴다. 국가를 무엇으로 판정할지(요청 IP · 계정 설정)와 "한 계정은 처음 결제에 성공한 결제사로 고정"은 구독이 생길 때 필요해진다 — 2 · 3단계에서 정한다.
- 토스의 해외 카드 · 외화 결제가 한국 사업자에게 어떤 조건으로 열리는지는 **확인하지 않았다**(§17).

## 13. 테스트 전략

### 13.1 가짜 결제사 — 세 층

진짜 결제사로는 타임아웃 · 응답 유실 · 순서 뒤바뀜을 일으킬 수 없다. 그래서 가짜가 시험의 중심이다. 가짜는 모두 **시험 쪽 소스**에만 있다(`main` 에 없다).

| 층 | 가짜 | 재현하는 것 |
|---|---|---|
| `payment` 단위 시험 | `ScriptedGateway` — 능력 인터페이스를 구현한 메모리 결제사. 호출마다 대본을 준다 | **승인됨** / **거절(코드)** / **타임아웃인데 적용됨** / **타임아웃이고 적용 안 됨** / **응답이 한참 뒤에 적용됨** / 조회가 5xx / 결제사 화면에서 취소됨(장부 모르게) / 금액이 다른 승인. 호출 횟수 · 멱등 키 · 금액을 기록해 "결제사를 정확히 한 번 불렀다"를 잰다 |
| `payment-toss` 단위 시험 | 가짜 토스 HTTP 서버(JDK `HttpServer`) — 토스 문서 모양의 JSON 으로 승인 · 취소 · 조회에 답한다 | Basic 인증 · `Idempotency-Key` · 본문 칸 · 경로, 지연(제한 시간 초과) · 5xx · 깨진 JSON · 연결 끊기, 오류 코드 → 확정/모름 분류, 상태 대응표(§6.6) 전부, `cancels[]` → 환불 목록, **응답에 심은 이름 · 이메일 · 전화 · 카드 번호가 결과 객체 · 예외 메시지 · 로그에 없다** |
| 프론트 e2e | `apps/sample/e2e/fakeToss.ts` — 같은 모양의 Node 서버 + 제어 엔드포인트(`POST /control`: 다음 승인을 거절 · 지연 · 유실). 브라우저의 토스 SDK 스크립트 요청은 시험이 가로채 "바로 `successUrl` 로 돌려보내는" 대역으로 바꾼다(소셜 로그인 e2e 의 `fakeProviders.ts` 와 같은 방식). 백엔드는 `SKELETON_PAYMENT_TOSS_BASE_URL` 로 가짜를 본다 | 화면에서 본 여정 전체 |

`apps/sample` 의 통합 시험은 HTTP 가짜가 아니라 시험 소스의 `ScriptedGateway` 빈을 끼운다(진짜 PostgreSQL + 보안 체인).

### 13.2 무엇을 어디서

| 대상 | 무엇 |
|---|---|
| `payment` 단위 | §3 의 표 한 줄마다 적은 시험. 전이표(§5.2 · §5.3)의 **허용되지 않는 전이 전부**가 거절된다(표를 그대로 매개변수 시험으로). `converge` 를 같은 스냅샷으로 두 번 부르면 두 번째는 아무것도 바꾸지 않는다. 지급 · 회수 리스너가 던져도 돈의 상태는 그대로다 |
| `payment` `noOptionalTest` | `alert` 가 클래스패스에 없어도 장부가 돌고 "확인 필요"는 줄과 로그에만 남는다. 있으면 `PAYMENT_REVIEW_NEEDED` 가 주문 id 를 키로 한 번 나간다(주문 id 말고는 아무 값도 싣지 않는다) |
| `payment` — 장부 없는 조립 | `PaymentLedgerStore` 빈이 없으면 컨트롤러 · 탈퇴 고리 · 가드가 하나도 등록되지 않는다. 지금의 `PaymentAutoConfigurationTest` · `PaymentProviderRouterTest` · `PaymentServiceTest` 는 **한 줄도 바뀌지 않는다**. 워크벤치의 기존 시험도 그대로 |
| `payment` 웹 시험 | §7 의 경로 · 상태 코드 · 오류 코드, 남의 주문 = 없는 주문, 운영자 경로의 권한(역할 없음 403 · 꺼져 있으면 404), 웹훅의 비밀 조각 · 크기 제한 · 모르는 결제사, 사용자 응답에 결제사 결제 번호 · `reviewReason` 이 없다 |
| `payment-jdbc` `dbTest` (PostgreSQL · MySQL) | 저장소 왕복, 유니크 다섯 개의 경합(16 스레드: 같은 `dedupe_key` · 같은 결제 키 · 같은 주문의 열린 환불 · 같은 결제사 환불 번호 · 같은 `event_key`), `check` 제약, 승인 ↔ 만료 · 승인 응답 ↔ 웹훅 · 환불 ↔ 환불 · 지급 ↔ 지급 경주에서 교착이 없고 승자가 하나다, 작업 넣기가 트랜잭션과 함께 되돌려진다, 탈퇴 고리 멱등(두 열을 비우고 두 번째에는 아무것도 바꾸지 않는다), **열 분류 시험**(§5.4 — "지워지는 개인정보" 는 `card_issuer` · `card_last4` 뿐)과 **심은 값 스캔**(§3 19 — 전체 카드 번호 · 이름 · 이메일 · 전화가 어느 열에도 없고 `card_last4` 는 4자 이하) |
| 마이그레이션 | `MigrationLockTest` · `RepositoryMigrationsTest`(두 벤더 폴더 짝) · 샘플의 `MigrationUpgradeIntegrationTest` · `MySqlMigrationUpgradeTest`(기준선 데이터 위에서 새 표가 올라간다 — 올라간 뒤 탈퇴 고리를 돌려 두 열이 비워지는 것도 본다) |
| `payment-toss` | 13.1 의 둘째 줄 + `TossKeyGuardTest`(§9.3 의 표 전부, 메시지에 키 값이 없다) + 설정 없이 기동(지금의 `TossPaymentBootWithoutConfigurationTest`) |
| `apps/sample` 통합 | 가입 → 응원하기 → 응원 기록이 생김(`PAID`) → 환불 요청 → 운영자 환불 → 응원 기록이 「환불됨」(일부 환불이면 기록은 `PAID` 로 남고 환불된 금액만 오른다). 목록에 없는 `itemRef` · 다른 `quantity` 는 `ITEM_NOT_PURCHASABLE`. 승인이 "모름"으로 끝난 뒤 작업이 돌아 응원 기록이 생긴다. 계정 탈퇴 뒤 전체 표 스캔(`AccountJourneyIntegrationTest` 류)에 결제 줄을 넣고 **`card_issuer` · `card_last4` 가 비어 있고** 전체 카드 번호 · 이름 · 이메일 · 전화가 어느 열에도 남지 않음을 본다 |
| 가드 | `CapabilitiesCatalogTest` · `ModuleDocumentationTest` · `ModuleRegistrationRulesTest` · `scripts/test-deploy-contract.sh` · `scripts/test-new-project.sh`(`--modules payment,payment-jdbc,payment-toss` 로 찍은 프로젝트가 빌드된다 · `payment` 만 고르면 표가 없다) |
| 프론트 패키지 | 경로 · 메서드 · `Idempotency-Key` 헤더, `openCheckout` 이 주입한 SDK 대역에 §6.6 의 인자를 넘기고 **구매자 정보를 넘기지 않는다**, `readCheckoutReturn`, `useOrder` 가 끝 상태에서 묻기를 멈춘다, 낱말 시험(§11.2 표의 「쓰지 않는 말」이 기본 문구 · `ko.ts` 의 결제 · 응원 키에 없다), 카드 표시(「현대카드 ····1234」 · 모르는 코드는 「카드」 · 끝 네 글자 없음), `tests/ssr.safety` |
| 프론트 스토리 (`play`) | 상태마다 한 스토리(됐어요 · 확인 중 · 늦어지고 있어요 · 안 됐어요 · 테스트 표시 · 카드 표시 있음/없음), 환불 대화상자의 확인 · 취소, 못 누르는 단추 옆의 이유 문구, a11y |
| e2e (`apps/sample/e2e/payment.e2e.ts`, 응원하기 기준) | ① 응원하기 → 가짜 결제창 → 결과 「결제됨」 → 내 응원 내역(카드 표시) → 환불 요청 → 운영자 환불 → 「환불됨」 ② 결제창 이탈 뒤 지연 결제 정합 — 결제창을 열고 인증만 하고 떠남 → 주문은 「결제 확인 중」/「시간이 지났어요」로 남음 → 가짜 결제사가 나중에 결제를 끝냄 → 조회 작업이 「결제됨」으로 맞추고 응원 기록이 생김 ③ 금액 변조 거절 — 돌아온 주소의 `amount` 를 고치면 `400 PAYMENT.AMOUNT_MISMATCH` 이고 결제사 호출이 없다 ④ 거절 → 「결제 안 됨」 → 다시 응원하기는 새 주문 ⑤ 승인 응답 유실 → 「결제 확인 중」 → 잠시 뒤 「결제됨」 ⑥ 결과 화면 새로 고침 · 뒤로 가기에 결제사 호출이 늘지 않는다 ⑦ 결제창에서 그만둠 → 「그만뒀어요」 |
| 진짜 토스 (손으로, 테스트 키) | `scripts/check-real-providers.sh` 의 토스 검사 + §10 의 ① · ② + 샘플을 띄워 결제 · 부분 환불 · 관리 화면 취소 뒤 맞추기. 결과를 `docs/modules/payment-toss.md` 「검증 기록」에 날짜와 함께 적는다 |
| 변이 확인 (한 번, 손으로) | 금액 비교를 지우면 §3 3 의 시험이, 환불 조회가 다시 보내도록 바꾸면 12 가, 웹훅 본문의 상태를 믿도록 바꾸면 7 이, `fulfilled_at` 조건을 지우면 6 이 실패하는지. 기록은 `docs/modules/payment.md` |

돌리는 범위: 작업 중에는 바뀐 모듈의 시험만(`:modules:payment:test`, `:modules:payment-jdbc:postgresTest` …). 전체 묶음은 단계가 끝났을 때 한 번.

## 14. 단계별 작업 목록

크기: S = 파일 5개 안팎 · M = 10~20 · L = 20 이상. 각 단계는 앞 단계만 있으면 따로 검증된다. 단계마다 CHANGELOG `[Unreleased]` 에 한 줄.

**백엔드**

| # | 작업 | 크기 | 끝났다는 증거 |
|---|---|---|---|
| B1 | `payment`: 능력 인터페이스(§4.1) · `PaymentProvider` 를 그 합으로 · `PaymentSnapshot` · `PaymentMode` · `PaymentFailures.outcomeOf` · 라우터의 `gateway()` · `PaymentOperationResult.remaining` | S | `payment` 시험 + `payment-toss` · `payment-stripe` · 워크벤치가 **고치지 않고** 컴파일 · 통과 |
| B2 | `payment-toss`: 조회(카드사 코드 · 끝 네 글자 대응 포함, §6.6) · 환불 결과(남은 금액 · 거래 키 · 응답 상태를 본다) · 결제창 값(`PaymentCheckoutGateway`) · 클라이언트 키 · 모드 · 가짜 토스 HTTP 서버 시험 · 키 가드(§9.3) · 오류 본문을 예외에 싣지 않기. **기존 결함 고치기: `TossPaymentProvider.refund` 가 응답 상태를 보지 않고 늘 `REFUNDED` 로 돌려준다 → 응답 상태를 본다.** `ExternalHttpClient` 의 응답 제한 5초는 §6.6 대로 다룬다 — 플랫폼 기본값은 바꾸지 않고 샘플 yml 에서 30초로 올리며 `reconcile.first-delay` 가드(§9.2)로 지킨다 | M | `payment-toss` 시험(대응표 · 확정/모름 · 심은 값) + `TossKeyGuardTest` |
| B3 | `payment` 장부 1 — 주문: 모델 · 포트 · `PaymentCatalog` · 만들기 · 선점/호출/확정 · `converge`(결제 부분) · 그만둠 · 만료 · 조회 작업 · 지급 · 이력 · "확인 필요" 경보(`alert` 선택 의존). `ScriptedGateway` 와 메모리 포트 대역(시험 소스) | L | §3 의 1~6 · 15 · 16 · 18(결제 쪽) 시험 + `noOptionalTest` |
| B4 | `payment-jdbc`: V 파일(두 방언 — `card_issuer` · `card_last4` 포함) + 잠금 · 저장소 · 작업 어댑터 · `dbTestModules` 등록 · 모듈 문서 · `capabilities.json` · `new-project.sh` 조각 | L | `postgresTest` · `mysqlTest`(경합 · 제약 · 열 분류) + `MigrationLockTest` + 샘플 업그레이드 시험 + 카탈로그 · 문서 가드 |
| B5 | `payment` 장부 2 — 환불: 요청 · 거둠 · 승인 · 거절 · 운영자 환불 · 환불 전 확인 · 조회 정산 · `converge`(환불 부분) · 회수 | M | §3 의 12~14 시험 + 두 방언 환불 경합 |
| B6 | `payment` 장부 3 — 웹훅 수신함: 경로 · 비밀 조각 · 해석(`payment-toss` 의 `PaymentWebhookGateway`) · 줄 넣기 · 처리 작업 · 재전송 우회 · `payment` 의 가드 | M | §3 의 7~11 시험 + 두 방언 중복 수신 |
| B7 | HTTP(사용자 · 운영자) · 오류 코드 · OpenAPI · 탈퇴 고리(`card_issuer` · `card_last4` 지우기 포함) · 내보내기 고리 · 문서(`docs/modules/payment.md` 다시 쓰기 · `payment-toss.md` · `docs/payment-http-contract.md` · `docs/errors.md` · `docs/config/modules/*.yml` · `docs/deploy.md` §7 · `.env.example` · `deploy/app.yaml` · `docs/accounts.md` 한 줄 · `docs/new-project-recipe.md` 의 "손으로 써야 하는 것: 결제 HTTP" 고치기) | L | 웹 시험 + 탈퇴 시험 + `ModuleDocumentationTest` + `scripts/test-deploy-contract.sh` |
| B8 | `apps/sample`: 응원 조각(`cheer/` — 응원 기록 표 하나[주문 id · 계정 · 금액 · 환불된 금액 · 상태 `PAID`/`REFUNDED` · 시각] · 서버가 가진 금액 목록 1,000 · 3,000 · 5,000원을 쓰는 `PaymentCatalog`[`itemType="cheer"`, `itemRef` = 목록의 코드 예 `cheer-3000`, 목록에 없으면 `ITEM_NOT_PURCHASABLE`] · `PaymentOrderListener`[`onPaid` 가 응원 기록을 남기고 `onRefunded` 가 전액 환불일 때 그 기록을 `REFUNDED` 로 바꾼다] · `GET /api/v1/cheers/options`[금액 목록] · `GET /api/v1/cheers/me`[내 응원 내역]) · yml(토스 별칭 · 응답 제한 시간 30초 · 운영자 경로) · 시드 · 통합 시험 · `docs/sample.md` · 워크벤치에 `payment-jdbc` 조립 | M | 샘플 통합 시험 + 전체 표 스캔(두 열이 비워짐) |
| B9 | 진짜 토스 검증: `scripts/check-real-providers.sh` 확장 · `scripts/payment-webhook.sh` · `docs/real-provider-setup.md` 절 · §16 의 실측 · 변이 확인 기록 | S | 검증 기록(날짜 · 무엇을 봤나) |

B2 는 B1 만 있으면 B3 와 나란히 간다. B4 는 B3 의 포트가 정해지면 시작하고 B5 · B6 이 더하는 저장소 메서드를 따라 자란다(V 파일은 B4 에서 **네 표 모두** 한 번에 만든다 — 잠근 뒤에는 고칠 수 없다). B7 은 B5 · B6 뒤.

**프론트**

| # | 작업 | 크기 | 끝났다는 증거 |
|---|---|---|---|
| F1 | `@skeleton/payment`: 장부 API · 타입 · 오류 코드 · 훅 · `openCheckout` · `readCheckoutReturn` · README · `capabilities.json` | M | 패키지 시험 + `tests/capabilities*.test.ts` · `ssr.safety` |
| F2 | `@skeleton/payment` 부품 6개(카드 표시 포함) + 스토리 · `docs/ui-catalog.md` · 낱말 시험 | M | `test:stories`(가짜 전송) + 낱말 시험 |
| F3 | `apps/sample`: `/cheer`(금액 고르기 + 「응원하기」) → 결제창 → `/payments/return` → `/cheers`(내 응원 내역) → `/payments/:id`(환불 요청) · 설정의 「내 응원 내역」 링크 · 문구(`ko` · `en` — §11.2 낱말) · `fakeToss.ts` · e2e(§13.2 ①~⑦) | L | `pnpm e2e:sample`(B8 뒤) |
| F4 | `@skeleton/payment/admin` 부품 + `apps/sample` 의 운영자 화면 + e2e ⑤ | M | 스토리 + e2e |

F1 · F2 는 B7 의 계약 문서만 정해지면 가짜 전송으로 먼저 간다.

**2단계 · 3단계**는 이 목록에 없다 — 각각 자기 스펙과 작업 목록을 따로 쓴다(§12 가 출발점).

## 15. 남는 위험 (이 설계가 막지 못하는 것)

- **"적용 안 됨"으로 닫은 환불이 늦게 적용되는 경우.** `settle-delay` 뒤에 조회했는데 결제사가 그 뒤에 앞선 요청을 처리하면 장부는 `FAILED`, 결제사는 환불됨이다. 다음 환불의 "환불 전 확인"이나 웹훅이 `origin=PROVIDER` 줄로 잡아 **금액은 맞게 되지만**, 그 사이에 운영자가 다시 환불을 눌렀다면 결제사의 남은 금액 검사만이 초과 환불을 막는다.
- **접수된 환불을 닫은 뒤 다시 환불했는데 첫 요청이 늦게 적용되는 경우.** 결제사가 접수한 환불을 `provider-pending-timeout` 뒤 `sync` 가 닫은 다음 다시 환불했는데 첫 요청이 늦게 적용되면, 부분 환불(합이 결제액 이하)은 결제사의 잔액 검사로도 못 막는다 — 장부는 둘 다 잡고(`PROVIDER` 줄 · `LATE_RESULT` · `REFUND_MISMATCH`) 사람이 본다.
- **경보에 기대는 상태.** 조회 작업이 시도를 다 쓰면(`CONFIRM_UNRESOLVED`) · 지급이 끝내 실패하면 · 금액이 어긋나면 사람이 봐야 한다. `alert` 의 웹훅 주소를 정하지 않은 앱에서는 로그와 운영자 화면의 「확인 필요」 탭뿐이다.
- **지급의 "정확히 한 번"은 같은 DB 안에서만.** 앱의 `onPaid` 가 다른 시스템(외부 API · 메일)을 부르면 다시 불릴 수 있다 — 리스너를 멱등하게 써야 한다.
- **같은 것을 두 번 사는 것.** 두 탭에서 주문 두 개를 만들어 둘 다 결제하면 둘 다 정상 결제다. 한 번만 살 수 있는 상품인지는 앱만 안다 — `PaymentCatalog` 가 거절하거나, 리스너가 중복을 보고 환불을 건다. 샘플의 응원은 여러 번 해도 되는 것이라 이 문제가 없다.
- **웹훅의 비밀 조각은 주소 안에 있다.** 프록시 · 접근 로그에 남을 수 있다. 새어 나가도 할 수 있는 일은 "아는 주문에 대한 조회를 일으키는 것"뿐이다 — 토큰이 새면 아는 주문의 조회를 주문당 창마다 상한(기본 30줄)까지 일으킬 수 있고, 상한을 넘은 신호는 버려지지 않고 창 끝의 조회 한 번으로 미뤄진다(§6.5; 그 동안 그 주문의 진짜 변화는 창 끝, 최대 `window` 뒤에 맞춰지고 운영자 목록에 표식 줄이 보인다). 토스가 보내는 서버의 IP 범위로 좁히는 것은 확인하지 못해 넣지 않았다(§17).
- **테스트 환경이 못 잡는 것.** 테스트 결제는 가짜 승인이다 — 실제 카드사 거절 · 지연 · 세금이 얹힌 금액 같은 것은 실결제에서만 나온다(운영 교훈: 테스트 모드에서는 우연히 맞던 금액 공식이 실결제에서 틀렸다). 사업자등록 뒤 실결제 키로 소액 결제 · 환불을 한 번 해 보기 전까지 "실결제 검증됨"이라고 적지 않는다.
- **탈퇴한 계정의 구매 이력은 DB 안에서 계정 id 로 묶인다**(`KEEP` 일 때). 이름 · 이메일 · 카드 값(`card_issuer` · `card_last4` 는 탈퇴 때 지워진다)은 없지만 결제사 결제 번호로 결제사에 물으면 결제한 사람을 알 수 있다 — 법정 보존의 대가다. 보존 기간이 지난 줄을 지우는 실행기는 없다.
- **운영자 판정이 토큰의 역할이면 최대 15분 낡는다**(기본 구현). 방금 역할을 뺏긴 운영자가 그 사이 환불할 수 있다 — `account` 를 쓰는 앱은 빈을 바꾼다(샘플).
- **분쟁 · 차지백 · 결제사의 강제 취소**는 조회에서 환불로만 보인다. 사유를 구별하지 않는다.
- **결제사 상점을 바꾸면**(다른 시크릿 키 묶음) 옛 주문은 조회 · 환불이 안 된다. 모드가 같으면 가드가 잡지 못한다.
- **`job-queue` 의 끝난 줄 정리**(샘플은 14일)가 작업 줄을 지워도 장부에는 영향이 없다 — 다만 "그 작업이 왜 죽었나"의 `last_error` 는 함께 사라진다(수신함 · 주문의 코드 열에는 남는다).
- **결제창을 여는 스크립트는 결제사의 것**이다. 결제사 스크립트가 바뀌면 `openCheckout` 이 깨질 수 있고 우리 시험(대역)은 그것을 못 본다 — 진짜 토스 검증(§13)이 유일한 확인이다.

## 16. 구현 전에 실측할 것 (읽기만으로는 확정하지 못한 것)

- **토스 웹훅의 실제 모양**: `PAYMENT_STATUS_CHANGED` 본문에서 결제 키 · 주문 번호가 어디에 있는지, `tosspayments-webhook-transmission-id` 가 재전송에서 같은 값인지(다르면 같은 일로 줄이 여럿 생긴다 — 결과는 같지만 `event_key` 를 본문 해시로 바꿀지 정한다), 테스트 키로 결제 웹훅이 실제로 오는지, 개발자센터에서 취소하면 어떤 이벤트가 오는지.
- **토스 `card.number` 의 끝 네 글자가 실제로 어떤 모양인지**: 토스가 끝자리도 가려서 줄 수 있다(예 `1234` 가 아니라 `****` 로 끝나는 번호). 테스트 키와 실결제에서 각각 어떤 모양으로 오는지, `card.issuerCode` 가 어떤 값(코드표)으로 오는지를 보고 `card_last4` 의 걸러내기 규칙(마지막 네 글자, 숫자 · `*` 만)과 카드사 대응표를 확정한다. 끝 네 글자가 전부 `*` 이면 화면은 끝자리를 보이지 않는다.
- **토스 조회의 경계**: 결제창만 열고 인증하지 않은 주문 번호로 `GET /v1/payments/orders/{orderId}` 를 부르면 404 인지, 인증만 하고 승인하지 않은 결제의 `status`, 승인하지 않은 결제가 `EXPIRED` 로 바뀌는 데 걸리는 시간(문서의 "10분" 과 "30분" 의 관계).
- **토스 환불 응답**: 부분 환불 뒤의 `balanceAmount` · `cancels[]`, 같은 `Idempotency-Key` 로 다시 보냈을 때의 응답(우리는 다시 보내지 않지만 중간 프록시의 재전송에 대비해 안다), `TossPayments-Test-Code` 로 승인 · 취소 오류가 재현되는지.
- **승인 호출의 실제 지연**과 토스가 권하는 제한 시간(문서에서 찾지 못했다) — `response-timeout: 30s` 가 맞는지.
- **작업 넣기와 트랜잭션**: `payment-jdbc` 의 트랜잭션 안에서 `JobQueue.enqueue` 가 같은 연결을 쓰는지 — 되돌리면 작업 줄도 사라지는지 시험으로.
- **락**: 주문 줄 → 환불 줄 순서에서 두 방언 모두 교착이 없는지. PostgreSQL 의 `for update` 는 기다린 뒤의 줄을 다시 읽어야 한다(`author-profile-jdbc` 의 주석이 같은 함정을 적어 두었다 — 잠그는 문장과 판단하는 읽기를 나눈다).
- **`check` 제약**: 이 저장소의 마이그레이션에 처음 쓰인다. MySQL 8.4 에서 `coalesce` 가 든 `check` 가 통하는지, `persistence-jooq` 의 코드 생성 증명(`DDLDatabase`)이 이 파일을 읽는지. 안 되면 제약을 빼고 서비스 검사 + 시험으로 대신한다.
- **`review_reason` 인덱스**: 거의 전부 NULL 인 열에서 두 방언이 인덱스를 타는지(실행 계획).
- **토스 SDK v2**: 비회원 상수(`ANONYMOUS`)를 스크립트 판에서 어떻게 꺼내는지, `requestPayment` 가 리다이렉트 방식에서 무엇을 돌려주는지, 모바일 브라우저 · 앱 내 브라우저에서 `successUrl` 로 돌아오는지.
- **배포 경로**: Cloudflare Tunnel · Caddy 뒤에서 웹훅 본문이 그대로 닿는지, Caddy 접근 로그에 경로(비밀 조각)가 남는지 — 남으면 홈서버 쪽 로그 설정을 본다.
- **주인의 키**: `.env.local` 의 `test_sk_` 가 가입 뒤 받은 것인지(문서용 공개 키면 웹훅 · 결제 내역 확인이 안 된다), 같은 묶음의 `test_ck_` 를 개발자센터에서 확인.
- **마이그레이션 잠금 시점**: V 파일을 B4 에서 잠근 뒤 B5 · B6 에서 열이 모자라면 — 아직 main 에 나가기 전이면 `--rewrite` + CHANGELOG 한 줄로 고칠 수 있는지(`docs/schema-management.md` 의 규칙 확인), 아니면 잠금을 B6 뒤로 미룬다.

## 17. 공식 문서로 확인한 사실과 확인하지 못한 것

2026-10-08 에 읽었다. 페이지를 요약 도구로 읽었으므로 **문장 단위 인용은 보장하지 않는다** — 구현 전에 §16 의 실측과 함께 원문을 다시 본다.

**확인한 것 (토스페이먼츠)**

| 사실 | 출처 |
|---|---|
| 키 묶음이 둘이다: **결제위젯 연동 키** `test_gck_` · `test_gsk_`(실결제 `live_gck_` · `live_gsk_`) / **API 개별 연동 키** `test_ck_` · `test_sk_`(`live_ck_` · `live_sk_`). 결제위젯은 앞의 것, **결제창 · 브랜드페이 · 자동결제(빌링)는 뒤의 것.** 클라이언트 키와 시크릿 키는 한 묶음으로 써야 하고 섞으면 `INVALID_API_KEY`. 시크릿 키는 Basic 인증(`키:` 를 Base64) | https://docs.tosspayments.com/reference/using-api/api-keys |
| 테스트 키는 결제가 **가상으로 승인**되고 돈이 나가지 않는다. 전자결제 신청 전에도 테스트 키를 볼 수 있다(일부) | 위와 같음 · https://docs.tosspayments.com/guides/v2/get-started/environment |
| 가입 없이 쓰는 문서용 공개 테스트 키는 웹훅 · 가상계좌를 못 쓴다. 이메일(또는 전화)로 가입해 받은 테스트 키는 **API 기록 · 테스트 결제 내역 · 웹훅 설정**이 된다. 사업자등록은 실결제를 열 때 필요하다 | https://docs.tosspayments.com/blog/how-to-test-toss-payments |
| SDK v2: 스크립트 `https://js.tosspayments.com/v2/standard`(npm `@tosspayments/tosspayments-sdk`), `TossPayments(clientKey)` → `widgets()` · `payment()`. 성공 리다이렉트의 쿼리는 `paymentKey` · `orderId` · `amount`(+ `paymentType`), 실패는 `code` · `message` · `orderId`. 비회원은 `ANONYMOUS` | https://docs.tosspayments.com/sdk/v2/js |
| 결제창 연동: API 개별 연동 키, 필수는 결제수단 · 주문 번호 · 금액 · `successUrl` · `failUrl`, 구매자 정보는 선택. **`successUrl` 의 `amount` 를 서버에 저장한 금액과 비교**하라는 안내. 인증 뒤 **10분 안에** 승인해야 한다 | https://docs.tosspayments.com/guides/v2/payment-window/integration |
| 승인 `POST /v1/payments/confirm` (`paymentKey` 최대 200자 · `orderId` 6~64자 영숫자 `-` `_` · `amount`). 조회 `GET /v1/payments/{paymentKey}` · `GET /v1/payments/orders/{orderId}`. 취소 `POST /v1/payments/{paymentKey}/cancel` — `cancelReason` 필수(최대 200자), `cancelAmount` 가 없으면 전액 · 있으면 부분 취소. 응답 `cancels[]` 에 `transactionKey` · `cancelAmount` · `canceledAt` · `cancelStatus`. 결제 상태 `READY` · `IN_PROGRESS` · `WAITING_FOR_DEPOSIT` · `DONE` · `CANCELED` · `PARTIAL_CANCELED` · `ABORTED` · `EXPIRED`. `totalAmount` · `balanceAmount`. 카드 번호는 일부가 가려져 응답에 온다 | https://docs.tosspayments.com/reference |
| 멱등 키: **모든 POST API** 에 `Idempotency-Key` 헤더, 최대 300자, **15일** 유효, 처리 중인 요청에 같은 키 → `409 IDEMPOTENT_REQUEST_PROCESSING`. GET 에 붙인 헤더는 무시된다 | https://docs.tosspayments.com/reference/using-api/idempotency-key |
| 웹훅: **10초 안에 200** 을 받지 못하면 다시 보낸다 — **최대 7회**, 간격 1 · 4 · 16 · 64 · 256 · 1024 · 4096분(처음부터 약 3일 19시간). 로컬 시험은 ngrok 을 안내한다. 이벤트 종류: `PAYMENT_STATUS_CHANGED` · `DEPOSIT_CALLBACK` · `CANCEL_STATUS_CHANGED` · `BILLING_DELETED` 외 | https://docs.tosspayments.com/guides/v2/webhook |
| 웹훅 헤더 넷: `tosspayments-webhook-transmission-time` · `-retried-count` · `-id` · `-signature`. **서명 헤더는 지급대행 · 셀러 이벤트(`payout.changed` · `seller.changed`)에만 있다 — 결제 이벤트에는 없다.** `PAYMENT_STATUS_CHANGED` 의 `data` 는 결제 객체. `DEPOSIT_CALLBACK` 은 본문의 `secret` 으로 확인 | https://docs.tosspayments.com/reference/using-api/webhook-events |
| 보안 점검표: 웹훅은 본문을 믿지 말고 **결제 조회 API 로 다시 확인**, 전달 id 로 중복 방지, 테스트 키와 실결제 키를 환경으로 나누기, 시크릿 키를 코드 · 로그에 두지 않기 | https://docs.tosspayments.com/reference/using-api/security-checklist |
| 오류 코드: `ALREADY_PROCESSED_PAYMENT`(400) · `ALREADY_CANCELED_PAYMENT`(400) · `NOT_CANCELABLE_AMOUNT`(403) · `NOT_CANCELABLE_PAYMENT`(403) · `NOT_ALLOWED_PARTIAL_REFUND`(403) · `NOT_FOUND_PAYMENT`(404) · `NOT_FOUND_PAYMENT_SESSION`(404) · `INVALID_API_KEY`(400) · `UNAUTHORIZED_KEY`(401) · `REJECT_CARD_PAYMENT`(403) · `REJECT_CARD_COMPANY`(403) · `PROVIDER_ERROR`(400) · `FAILED_INTERNAL_SYSTEM_PROCESSING`(500) · `FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING`(500) · `UNKNOWN_PAYMENT_ERROR`(500) | https://docs.tosspayments.com/reference/error-codes |
| 테스트 시크릿 키로 호출할 때 `TossPayments-Test-Code` 헤더에 오류 코드를 넣으면 그 오류가 재현된다(실결제 키에서는 무시) | https://docs.tosspayments.com/guides/v2/get-started/environment |
| 자동결제(빌링): API 개별 연동 키, **리스크 검토와 추가 계약 뒤** 쓸 수 있다, 빌링키 발급 `POST /v1/billing/authorizations/issue`(`authKey` · `customerKey`) · 청구 `POST /v1/billing/{billingKey}`(`customerKey` · `amount` · `orderId` · `orderName`), **정기 청구 일정은 토스가 해 주지 않는다**(상점이 직접), 발급된 빌링키는 다시 조회할 수 없다 | https://docs.tosspayments.com/guides/v2/billing/integration-api · https://docs.tosspayments.com/reference |
| 배포 점검표: 실결제 키(`live_`)로 바꾸기, 시크릿 키를 저장소에 두지 않기, 취소 · 부분 취소 시험 | https://docs.tosspayments.com/guides/v2/deploy-checklist |

`PROVIDER_ERROR` 는 HTTP 400 이지만 "일시적인 오류" 다 — 그래서 확정/모름 분류는 상태 코드만이 아니라 **코드 목록**으로도 한다(`payment-toss` 가 이 코드를 모름으로 분류한다).

**확인한 것 (법령)** — §8.1 표. 출처: 시행령 https://www.nepla.ai/law/전자상거래-등에서의-소비자보호에-관한-법률-시행령 · 법률(2013년 개정본) https://ko.wikisource.org/wiki/전자상거래_등에서의_소비자보호에_관한_법률

**확인하지 못한 것**

- 결제 웹훅(`PAYMENT_STATUS_CHANGED`)의 진위를 확인하는 **공식 절차의 문장** — 이벤트 레퍼런스에는 없고, 보안 점검표의 요약에서 "조회로 다시 확인"만 봤다.
- `tosspayments-webhook-transmission-id` 가 재전송에서 같은 값인지 · 웹훅의 순서 보장 · 중복 가능성에 대한 문서 문장.
- 토스가 웹훅을 보내는 **서버 IP 목록**(있는지, 어디에 적혀 있는지).
- 승인 API 의 **권장 제한 시간**.
- 결제 상태 `EXPIRED` 의 "30분" 과 인증 유효 "10분" 의 정확한 관계.
- 이미 쓴 주문 번호를 다시 쓰면 어떻게 되는지(그래서 실패한 주문은 새 주문으로 다시 결제하게 했다).
- 테스트 키로 결제 웹훅이 실제로 전송되는지(블로그는 "웹훅 설정 가능"이라고만 한다).
- 토스의 해외 카드 · 외화 결제가 열리는 조건, 한국 사업자가 쓸 수 있는 해외 결제사.
- 전자상거래법 제6조의 **현행 문장**, 개인정보 보호법 제21조의 **조문 원문**, 국세기본법 · 부가가치세법 · 전자금융거래법의 보존 의무.
- 사업자등록이 없는 개인이 공개 서비스에 **테스트 키 결제 화면**을 두는 것에 대한 토스 약관상의 제한.

## 정해진 것

주인이 2026-10-08 에 정했다. 「가」는 되돌리기 어려운 것, 「나」는 나중에 바꿀 수 있는 것이다. 본문은 모두 이 결정으로 쓰여 있다. **나-5 · 나-6 은 초안의 추천과 다르게 정했다.**

### 가. 되돌리기 어려운 것

1. **장부를 어디에 두나** — **(a) `payment` 안에 두고 저장은 새 모듈 `payment-jdbc`.** `account` + `account-jdbc` 와 같은 모양이고, `payment-jdbc` 가 없으면 지금과 같다. 설정은 `skeleton.payment` 하나.
2. **탈퇴한 계정의 결제 줄** — **(a) 줄은 남기고 계정 id 도 그대로(`KEEP`)**, 탈퇴 시각만 찍는다. 나중에 `TOMBSTONE` 으로 갈 수 있다. (카드사 · 끝 네 글자는 나-5 에 따라 탈퇴 때 지운다.)
3. **표 · 식별자 이름과 모양** — **(a)** `payment_orders` · `payment_refunds` · `payment_events` · `payment_webhook_events`, id 는 `ord_` · `rfd_` + 무작위, 주문 id 를 결제사 주문 번호로 그대로 쓴다.
4. **토스를 무엇으로 붙이나** — **(a) 결제창 + API 개별 연동 키(`test_ck_` · `test_sk_`).** 결제위젯 키 묶음(`test_gck_` · `test_gsk_`)은 쓰지 않는다. 주인 확인 사항은 그대로다: 개발자센터에서 `test_sk_` 와 같은 묶음의 `test_ck_` 를 받아 `.env.local` 에 `TOSS_PAYMENTS_CLIENT_KEY` 로 넣기, 이 키가 가입 뒤 받은 키인지 확인.

### 나. 주인이 정한 것

5. **카드 정보를 얼마나 남기나** — **(b) 카드사 + 끝 4자리 저장** *(초안의 추천 (a)와 다르다).* `payment_orders` 에 `card_issuer varchar(40)`(결제사 중립의 대문자 영문 코드) · `card_last4 varchar(4)`(가려진 번호의 마지막 네 글자, 숫자 또는 `*` 만)를 더한다. 그 이상(전체 번호 · 유효기간 · 소유자명 · 할부)은 저장하지 않는다. 이 두 칸은 지워지는 개인정보로 분류하고 계정이 지워질 때 NULL 로 지운다. 반영한 곳: §1 · §3 의 19 · 20 · §4.1 · §5.1 · §5.4 · §6.1 · §6.6 · §7 · §8 · §11.2 · §12.1 · §13.2 · §14 · §16.
6. **샘플 앱에서 무엇을 사나** — **(b) 「응원하기(후원) 1회」** *(초안의 추천 (a)와 다르다).* 금액은 서버가 가진 목록(1,000 · 3,000 · 5,000원 — `itemType="cheer"`, `itemRef` 가 목록의 코드, 목록에 없으면 `ITEM_NOT_PURCHASABLE`)이고 브라우저가 임의 금액을 정하지 못한다. 샘플 조각은 `cheer/`(응원 기록 표 하나 · `PaymentCatalog` · `PaymentOrderListener` · `GET /api/v1/cheers/options` · `GET /api/v1/cheers/me`). `onPaid` 가 응원 기록을 남기고 `onRefunded` 가 전액 환불일 때 그 기록을 환불됨으로 바꾼다(일부 환불이면 「일부 환불됨」). 화면: `/cheer` → 결제창 → `/payments/return` → `/cheers` → `/payments/:id`. 반영한 곳: §1 · §2 · §3 의 3 · §4.2 · §7 · §11.2 · §11.3 · §13.2 · §14 B8 · F3 · §15.
7. **환불은 누가 하나** — **(a) 본인은 요청만, 돈은 운영자가 움직인다.**
8. **가짜 결제사를 어디에 두나** — **(a) 시험 쪽에만**(단위: 메모리 대본 · 토스: 가짜 HTTP 서버 · e2e: Node 가짜 + SDK 대역). 로컬 시연은 토스 테스트 키로 한다.
9. **`payment-stripe`** — **(a) 지금 그대로 둔다**(장부에 올리지 않는다).
10. **운영자 판정의 기본** — **(a) 토큰의 역할**, 앱이 빈을 바꿀 수 있다. 샘플은 계정 저장소의 역할로 바꾼다.
11. **웹훅 원문** — **(a) 토스는 저장하지 않는다**(식별자와 해시만).
12. **인증만 하고 돌아오지 않은 결제** — **(a) 서버가 대신 승인하지 않는다 — 만료시킨다.**
13. **클라이언트 키를 프론트에 어떻게 주나** — **(a) 서버가 `checkout` 응답으로 준다.**
14. **사업자등록 전의 공개 배포** — **(a) 테스트 키 + 「테스트 결제」 표시로 연다**(보호 환경에서는 `allow-test-keys-in-protected-env=true` 를 일부러 켜야 선다). 토스 약관이 막는지는 확인하지 못했다(§17).
15. **기본값** — `checkout-ttl` 30분 · 본인 환불 요청 켜짐 · 운영자 경로 꺼짐 · 조회 12번(약 4시간) · 환불 조회 2분 뒤. 추천대로.
16. **결제 알림** — **(a) 모듈은 보내지 않는다 — 앱의 리스너가 보낸다**(샘플에 예시).
17. **2단계의 자리** — 구독은 새 모듈 `payment-subscription`(+ `-jdbc`) · 탈퇴를 요청한 순간을 알리는 고리를 `platform` 에 더한다 · 요금제는 설정. 추천대로(1단계는 막히지 않는다).
