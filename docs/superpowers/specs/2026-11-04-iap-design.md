# 인앱결제(IAP) — App Store · Google Play 구매를 서버가 검증해 결제 장부에 싣는다 — 설계

> 2026-11-04. 요청(형): 앱에서 디지털 상품을 판다 → 인앱결제가 기본이다. 우회했다 걸려 앱이 내려가는 길은 피한다. 지역 스위치는 **기본 꺼짐으로 자리만** 두고, 서버 설정으로 「IAP 만 / IAP+웹 결제 링크 / 웹 전용」을 정한다. 켜도 웹 결제는 바깥 브라우저의 기존 `payment-toss` 다.
> 형 원칙: 조립식 모듈 · 모듈 기본 꺼짐(앱 yml 이 켠다) · 업계 관행 · 쓸데없는 옵션 없음 · 막을 것은 영수증 위조 · 이중 지급 · 다른 계정으로의 권한 이전 · 환불 뒤 회수 · 알림 위조 · 재생 공격 · 실계정 호출 없음(가짜 서버 · 시험용 인증서 체인) · 예시는 `example.com`.
> 기준: kotlin main `7b6b184c`. 짝: flutter-skeleton 스펙 `2026-11-04-flutter-skeleton-design.md` §12.6-1(앱은 `in_app_purchase`, 서버가 검증). 조사: 지휘 세션의 `mobile-iap-external-payment-research.md`.
> 표시: **[문서]** 공식 문서로 오늘 확인 · **[코드]** 패키지 소스로 확인 · **[확인 필요]** 못 확인함.

## 0. 목적 · 비목표

**목적**: 앱이 스토어에서 산 것을 서버가 스토어에 직접 물어 확인한 뒤에만 지급한다. 지급 · 회수 · 구독 상태는 **지금 결제 장부 그대로**(주문 줄 · 환불 줄 · 구독 줄 · `PaymentOrderListener` · `PaymentSubscriptions.current`)라 웹(토스)과 앱(IAP)이 **권한 표 하나**를 같이 쓴다.

**1차 범위(끝에서 끝까지 도는 최소판)**: 단건(소모성 · 비소모성) + 자동 갱신 구독, 두 스토어, 구매 확인 엔드포인트 · 지역 정책 설정 응답 · 스토어 알림 수신(갱신 · 만료 · 환불), 견본 앱 조립, 가짜 서버 시험.

**비목표** (만들지 않는다):
- 서버에서 스토어 환불 보내기(운영자 환불) — 환불은 구매자가 스토어에 요청하고 우리는 알림으로 받는다. 운영자 화면의 스토어 주문은 `refundBlock: UNSUPPORTED`.
- 가족 공유 · 단체 구매(multiseat) · 비갱신 구독 · 수량 2 이상 · 프로모션 오퍼 서명 · 오퍼 코드 화면 · 구독 일시정지 다루기(멈춤은 「이용 불가」로만 본다).
- 서버가 스토어 구독을 해지 · 플랜 변경(애플은 길이 없다) — 해지는 스토어 화면에서, 우리는 알림을 받는다.
- `CONSUMPTION_REQUEST` 응답 · 구글 `pendingRefundReviewNotification`(ReviewRefund) · 외부 구매 신고(`EXTERNAL_PURCHASE_TOKEN`, EU · 일본 링크의 월간 보고) · 한국의 앱 안 제3자 결제(26%).
- 놓친 알림 쓸어 담기 작업(구글 `voidedpurchases.list` · 애플 Notification History) — 두 스토어가 며칠 재전송한다(§5).
- 앱 → 웹 결제로 넘어갈 때 자동 로그인 · 카탈로그 운영자 화면에서 스토어 상품 id 잇기 · flutter 쪽 코드(다음 단계).

### 다음에 넓힐 것 (순서는 추천)
1. 놓친 알림 쓸어 담기(구글 voided 목록 하루 한 번 · 애플 Notification History).
2. 운영자 환불: 구글 `orders.refund`(`revoke=true`) · `subscriptionsv2.revoke`.
3. 카탈로그 가격 줄에 스토어 상품 id 링크(`ProviderPriceRefs` 의 `appstore` · `googleplay`).
4. EU · 일본 링크를 켤 때의 외부 구매 신고 · 앱 → 웹 한 번 쓰는 로그인 링크.

## 1. 모듈 경계 · 확장 지점

| 모듈 | 하는 일 | 설정 접두사 |
|---|---|---|
| `payment`(고침) | 스토어 능력 계약 `PaymentStoreGateway` · 장부 기록 `PaymentStorePurchases` · 사건의 모드 · 스토어 구독 규칙(§2.4) | `skeleton.payment`(새 키 없음) |
| `payment-iap`(새, 공통) | 앱용 HTTP 둘(설정 · 구매 확인), 상품 지도, 지역 정책, 계정 토큰 | `skeleton.payment-iap` |
| `payment-appstore`(새) | App Store Server API · 서명(JWS) 검증 · Notifications V2 해석 | `skeleton.payment-appstore` |
| `payment-googleplay`(새) | Google Play Developer API · RTDN(Pub/Sub push) 인증 · 해석 · 승인 통지(acknowledge) | `skeleton.payment-googleplay` |

- 의존: `payment-iap` → `api(payment)`, 장부(`payment-jdbc`)가 있을 때만 HTTP 가 선다. 두 스토어 모듈 → `api(payment)`. 스토어 모듈은 `payment-iap` 을 모른다(상품 id 를 그대로 돌려줄 뿐). 앱은 `payment-iap` + 쓰는 스토어 모듈을 고른다.
- 결제사 id: `appstore` · `googleplay`. 새 결제사 규칙대로 `PaymentGatewaySource` 빈 하나로 등록한다(프로젝트 여럿이면 프로젝트마다 자기 설정 — Paddle 과 같다).
- **마이그레이션 없음.** 장부의 열이 이미 맞다: `(provider, provider_payment_id)` 유니크(200자), `(provider, provider_subscription_id)` 유니크(100자), `end_reason` 40자.
- 외부 라이브러리: **애플 App Store Server Library(Java) 는 쓰지 않는다.** 5.2.0 이 OkHttp 4 · Jackson 2.16 · java-jwt · BouncyCastle 을 끌고 온다 **[문서: Maven POM]** — 스켈레톤은 Jackson 3 · `ExternalHttpClient` · 네이티브 이미지라 겹친다. JWS 서명 · 검증은 `auth` 가 이미 쓰는 nimbus(`spring-security-oauth2-jose`) + JDK `CertPathValidator`, 구글 서비스 계정 토큰은 `notification-fcm` 의 JDK 서명 방식(`FcmAccessTokens` — 토큰 주소 고정, 자격 파일의 `token_uri` 를 읽지 않는다)을 옮긴다.

**확장 지점** (developer 는 이것만 만든다):
| 지점 | 기본 구현 | 고른 이유 |
|---|---|---|
| `PaymentStoreGateway`(계약) | 두 스토어 모듈 | 스토어가 더 생겨도(다른 앱 마켓) 장부 · HTTP 를 안 고친다 — 결제사 능력 모델과 같은 모양 |
| `IapProducts`(빈) | 설정 `payment-iap.products` | 다음에 카탈로그 DB 링크로 바꿀 자리(위 「넓힐 것」 3) |
| `IapChannelPolicy`(빈) | 설정 `payment-iap.channels` | 나라 · 플랫폼 말고 다른 기준(앱 판 등)으로 정하려는 앱이 바꾼다 |

계정 토큰 만드는 법은 확장 지점이 **아니다** — 바꾸면 이미 산 구매의 주인 확인이 깨진다.

## 2. 기존 결제 모델과 맞물리는 법

### 2.1 계정 토큰 (`appAccountToken` = `obfuscatedAccountId`)
- 서버가 계정마다 UUID 하나를 정해 설정 응답으로 준다: `UUID.nameUUIDFromBytes("payment-iap:" + accountId)`(JDK, 표 없음 · 늘 같은 값). 앱은 `PurchaseParam.applicationUserName` 에 넣는다 — StoreKit 2 경로에서 `appAccountToken` 으로 간다 **[코드: in_app_purchase_storekit]**, 안드로이드는 `accountId` 로 넘어간다 **[코드]**(→ `setObfuscatedAccountId` 인지는 **[확인 필요]**). 36자라 구글 64자 한도 안이다 **[문서]**.
- 개인정보가 아니다(계정 id 의 해시). 비밀일 필요도 없다 — 남의 토큰으로 사면 그 사람이 받을 뿐 내가 가져갈 길은 없다.

### 2.2 상품 지도 (`skeleton.payment-iap.products`)
```yaml
skeleton:
  payment-iap:
    products:
      - item-type: cheer            # 단건: PaymentCatalog 의 itemType · itemRef
        item-ref: cheer-3000
        ios-product-id: com.example.sample.cheer3000
        play-product-id: cheer_3000
      - plan: premium-monthly-krw    # 구독: SubscriptionPlans 의 플랜 코드
        ios-product-id: com.example.sample.premium.monthly
        play-product-id: premium     # 구글 구독 상품 id
        play-base-plan-id: monthly   # 구글 기본 요금제 id (구독만)
```
- 한 줄은 `item-type`+`item-ref` 또는 `plan` 중 하나. 스토어 id 가 겹치면 기동 실패. 지도에 없는 상품은 지급하지 않는다(`422 IAP_PRODUCT_UNKNOWN`, `WARN` 한 줄).
- **금액의 출처는 스토어다**(Paddle 과 같다 — 가격은 스토어 콘솔에서 정한다). 주문 금액 · 통화 = 애플 거래의 `price`(밀리 단위 → 통화 최소 단위로, `Currency.defaultFractionDigits`) **[문서]** / 구글 `orders.get` 의 `total` **[문서]**. 애플은 이 값을 회계에 쓰지 말라고 한다 **[문서]** — 장부 금액은 참고값이고 정산은 스토어 보고서다. 같은 통화면 기존 `payment.price-drift` 경고가 그대로 돈다.
- `PaymentCatalog.quote` 는 주문 이름(`order_name`)과 살 수 있는지 보는 데만 쓴다. 판매가 끝난 상품이어도 **이미 돈을 냈으니 지급한다**(이름만 `item-ref` 로).
- 구독 줄의 플랜 · 금액 · 주기는 `SubscriptionPlans.find(plan)` 값(화면 표시용, 갱신 과금은 스토어가 한다). 주문 줄은 스토어 금액. 무료 체험 주문은 0원.

### 2.3 권한 표 — 스토어 사건 → 장부 → 앱이 보는 것

| 스토어 사건 (애플 / 구글) | 장부 | 앱 |
|---|---|---|
| 구매 확인(단건) — 구매 확인 엔드포인트 | `ONE_TIME` 주문을 `PAID` 로 넣고 지급 작업 | `onPaid` |
| 첫 구독 — 같은 엔드포인트 | 구독 줄 `ACTIVE`(스토어 관리) + `SUBSCRIPTION_INITIAL` 주문 `PAID` | `current().isInUse` |
| `DID_RENEW` / `SUBSCRIPTION_RENEWED` · `RECOVERED` · `RESTARTED` | `SUBSCRIPTION_RENEWAL` 주문 `PAID`, 기간 = 스토어의 만료 시각 | 그대로 이용 |
| `DID_FAIL_TO_RENEW`(하위 `GRACE_PERIOD`) / `IN_GRACE_PERIOD` | `ACTIVE` 그대로(유예 중 이용) | 이용 |
| `DID_FAIL_TO_RENEW`(유예 없음) · `GRACE_PERIOD_EXPIRED` / `ON_HOLD` · `PAUSED` | `PAST_DUE` | 이용 불가 |
| `DID_CHANGE_RENEWAL_STATUS`(끔 · 켬) / `CANCELED`(만료 전) | `CANCELING` ⇄ `ACTIVE` | 기간 끝까지 |
| `EXPIRED` / `EXPIRED` · `REVOKED` | `ENDED` | 끝 |
| `DID_CHANGE_RENEWAL_PREF`(올리기) · 갱신의 다른 상품 id | 플랜 코드를 지도로 따라간다(기존 `items` 따라가기) | — |
| `REFUND`(전액 · `REFUND_PRORATED` 는 `revocationPercentage` 만큼) **[문서]** / `voidedPurchaseNotification` | 환불 줄 `PROVIDER` · `SUCCEEDED` → 회수 작업. 지금 기간 구독 주문 전액이면 구독 `ENDED`(기존 규칙) | `onRefunded` |
| `REFUND_REVERSED` | 그 환불 줄 `REVERSED`(차지백 되돌림 길) — 지급 · 구독은 되살리지 않는다(기존 규칙) | — |
| 구글 새 토큰의 `linkedPurchaseToken` 이 우리 구독 줄을 가리킴(올리기 · 다시 구독) | 같은 트랜잭션에서 옛 줄 `ENDED`(`REPLACED`) + 새 줄 | 이어서 이용 |

### 2.4 `payment` 를 고치는 것 (K1 · K2)
1. **능력 `PaymentStoreGateway`**: `fetch(claim: StoreClaim): StorePurchase`(락 · 트랜잭션 밖, 스토어에 묻는다 — 없으면 null) · `afterGrant(purchase)`(커밋 뒤 한 번, 기본 아무것도 안 함 — 구글 acknowledge). `StorePurchase` = 모드 · 종류(단건/구독) · 스토어 상품 id(+기본 요금제) · 결제 번호 · 구독 번호 · 이어지는 옛 구독 번호 · 계정 토큰 · 금액 · 수량 · 결제 시각 · 기간 · 상태(`PURCHASED` · `PENDING` · `REVOKED`) · 구독 상태.
2. **장부 기록 `PaymentStorePurchases.record(accountId, purchase, mapped)`**(`PaymentPushedEvents` 옆, 한 트랜잭션): 단건이면 주문을 `PENDING → PAID` 로 바로 넣고 지급 작업, 구독이면 구독 줄(스토어 관리, `provider_subscription_id` = 애플 `originalTransactionId` / 구글 `sha256(purchaseToken)` 16진수) + 지금 기간 주문 하나. 이미 있는 구독 줄이면 **상태 · 기간 맞추기**(`PushedSubscription` 반영과 같은 길 — 「구매 복원」 단추가 이것이다). 지난 갱신 주문은 거슬러 넣지 않는다(장부는 우리가 안 순간부터).
3. **사건의 모드**: `PushedPayment` · `PushedRefund` · `PushedSubscription` 에 `mode: PaymentMode?`(null = 게이트웨이 모드, 지금 그대로). 스토어는 거래마다 모드가 다르다 — 애플 `environment=Sandbox` · 구글 `testPurchase`/`testPurchaseContext` → `TEST`, 그 밖 `LIVE`. 대시보드 매출은 지금처럼 `LIVE` 만.
4. **해석의 일시 실패**: `PaymentPushGateway.interpret` 가 외부 호출 실패(기존 `PaymentProviderException` · `ExternalHttpException`)를 던지면 수신함 줄은 `DEAD` 가 아니라 재시도(구글 해석은 API 를 부른다 — 락 밖이라 지금 위치 그대로 된다).
5. **스토어 구독 규칙**: 구독 켜기 조건에 스토어 게이트웨이를 「스스로 갱신하는 결제사」로 센다(지금은 빌링 · hosted 만). 스토어 줄의 해지 · 중도 해지 · 플랜 변경 요청은 `409 PAYMENT.SUBSCRIPTION_MANAGED_BY_STORE`, 결제사 해지 작업 · 우리 갱신 작업은 건너뛴다. 끝 이유 `REPLACED` 를 더한다. `GET /subscriptions/me` 가 `provider` 를 싣는지 보고 없으면 더한다(앱이 해지 단추 대신 스토어 관리 화면을 연다).
6. **탈퇴**: 지금처럼 우리 줄만 끝낸다(`ACCOUNT_ERASED`). 스토어 과금은 계속되므로 앱이 탈퇴 화면에서 「스토어에서 해지하세요」를 보인다(정해야 할 것 2).

## 3. API (`payment-iap`, 기준 경로 `/api/v1/payments/iap`, 로그인 필요, `skeleton.payment-iap.enabled=false` 면 404)

### GET /config?platform=ios|android&storefront=<나라>
```jsonc
{ "value": {
  "channel": "IAP_ONLY",          // IAP_ONLY | IAP_AND_WEB | WEB_ONLY
  "webCheckoutUrl": null,         // channel 에 웹이 들 때만 (바깥 브라우저로 연다 — 웹뷰 금지)
  "store": "appstore",            // 이 플랫폼의 스토어 id, 꺼져 있거나 키가 없으면 null
  "appAccountToken": "6f1c…-…",   // §2.1
  "products": [ { "kind": "ONE_TIME", "itemType": "cheer", "itemRef": "cheer-3000", "storeProductId": "com.example.sample.cheer3000", "basePlanId": null } ]
} }
```
- 나라 = `storefront`(앱이 읽은 스토어 나라: 애플 세 글자 · 구글 두 글자, 서버가 두 글자로 바꾼다) → 없으면 기존 접속 나라(`PaymentCountryRouting`) → 없으면 기본. 규정은 **스토어 나라** 기준이라 앱 값을 먼저 쓴다. 앱이 값을 속여도 웹 결제 링크가 보일 뿐 돈 · 권한에는 길이 없다.
- `WEB_ONLY` 여도 구매 확인 엔드포인트는 열려 있다(예전 구매의 복원).

### POST /{store}/purchases — 산 것을 서버에 확인받는다
- 본문: `appstore` → `{ "transactionId": "2000000123456789" }`(앱의 `purchaseID` — SK2 에서 거래 id **[코드]**). `googleplay` → `{ "productId": "cheer_3000", "purchaseToken": "…" }`(`serverVerificationData` = 구매 토큰 **[코드]**; `productId` 는 어느 API 로 물을지 고르는 힌트일 뿐 응답의 상품 id 와 달라야 하면 거절).
- **`Idempotency-Key` 를 받지 않는다** — 구매 자체가 자연 키다(같은 거래 = 같은 줄). 계정 · IP 별 요청 수 한도는 주문 만들기와 같은 `http.order-rate-limit`(스토어 API 할당량 보호).
- 서버 순서: 게이트웨이 `fetch`(락 밖) → 검사(§6) → 지도 → `record`(트랜잭션) → 커밋 뒤 `afterGrant`(실패는 로그만 — 앱의 `completePurchase` 도 승인 통지를 한다 **[코드]**).

| 응답 | 뜻 · 앱이 할 일 |
|---|---|
| `200 { result: "GRANTED", kind, orderId, subscription }` | 지급했거나 이미 지급됨. 앱은 `completePurchase` |
| `202 { result: "PENDING" }` | 스토어가 아직 결제 대기(구글 보류 결제 · 애플 승인 요청). 끝내지 말고 기다린다 |
| `404 PAYMENT.IAP_PURCHASE_NOT_FOUND` | 스토어가 모른다(위조 · 다른 앱). 끝낸다 |
| `409 PAYMENT.IAP_PURCHASE_OWNED_ELSEWHERE` | 다른 계정의 구매. **끝내지 않는다**(원래 계정으로 로그인하면 다시 보내진다) |
| `409 PAYMENT.ALREADY_SUBSCRIBED`(기존 코드) | 이미 다른 구독(웹 등)이 살아 있다 — 돈은 장부에 넣고 「확인 필요」(`SUBSCRIPTION_SEAT_TAKEN`). 끝낸다 |
| `422 PAYMENT.IAP_PRODUCT_UNKNOWN` · `IAP_PURCHASE_REVOKED` · `IAP_PURCHASE_NOT_ELIGIBLE`(가족 공유 · 수량 2 이상) | 지급 없음. 끝낸다 |
| `429` · `503 PAYMENT.UNAVAILABLE` | 스토어가 안 받거나 꺼짐. **끝내지 않고** 다음 실행에 다시 보낸다(구글은 3일 안 승인 통지가 없으면 자동 환불 **[문서]**) |

## 4. 스토어 모듈

### 4.1 `payment-appstore`
- **조회**: `GET /inApps/v1/transactions/{transactionId}` → `signedTransactionInfo`(JWS) **[문서]**. 구독이면 `GET /inApps/v1/subscriptions/{transactionId}` 로 지금 상태 · 최신 거래 **[문서]**. 주소는 `https://api.storekit.apple.com` · 샌드박스 `https://api.storekit-sandbox.apple.com` **[문서]**. **운영 주소에 먼저, `404` 면 샌드박스에** 묻는다(심사 · TestFlight 구매는 샌드박스다 — 환경 옵션을 두지 않는다).
- **인증 JWT**: ES256, 헤더 `kid` · `typ: JWT`, 클레임 `iss`(issuer id) · `iat` · `exp`(60분 넘으면 무효 — 우리는 5분) · `aud: appstoreconnect-v1` · `bid` **[문서]**. 키(.p8)는 nimbus `ECDSASigner`.
- **JWS 검증**(조회 응답 · 알림 모두): `alg` 가 `ES256` 이 아니면 거절 → `x5c` 세 장(잎 · 중간 · 루트) **[문서]** → JDK PKIX 로 **모듈에 넣어 둔 Apple Root CA - G3**(공개 인증서, `apple.com/certificateauthority`) 까지 → 중간 인증서 확장 `1.2.840.113635.100.6.2.1` **[문서]** · 잎 확장 `1.2.840.113635.100.6.11.1` **[확인 필요: 애플 라이브러리 소스 기준]** → 잎 공개키로 서명 확인 → `bundleId` 일치, 운영 알림이면 `appAppleId` 일치. 폐기 확인(OCSP)은 하지 않는다(애플 라이브러리도 끌 수 있는 선택이다).
- 검증기는 빈(`AppStoreSignatureVerifier`, `@ConditionalOnMissingBean`)이고 루트를 바꾸는 **설정은 두지 않는다** — 시험은 빈을 바꿔 시험용 루트를 넣는다(운영에서 신뢰 루트를 바꾸는 길이 없다).
- **알림**: `POST /api/v1/payments/webhooks/appstore`, 본문 `{ signedPayload }` **[문서]**, `signed=true`(주소 비밀 조각 없음). 이벤트 키 = `notificationUUID`(중복 표시) **[문서]**, 사건 시각 = `signedDate`. 서명된 본문이 사실이라 `PaymentPushGateway`(Paddle 길)로 해석한다(§2.3 표). `TEST` 와 다루지 않는 종류는 `IGNORED`. 우리가 모르는 구매(아직 확인받지 않은 거래)의 알림은 `IGNORED`(`UNCLAIMED`) — 나중에 앱이 확인받을 때 스토어의 지금 상태(환불 포함)를 다시 읽는다.
- 응답: 받으면 `200`, 서명 실패 `401`(재전송된다 — 1 · 12 · 24 · 48 · 72 시간 뒤 다섯 번, 운영만 **[문서]**).
- **설정**: `enabled`(false) · `bundle-id` · `app-apple-id` · `issuer-id` · `key-id` · `private-key`(비밀) · `base-url` · `sandbox-base-url`. 켰는데 값이 비면 이 스토어 빈이 없다 — **보호 환경에서는 기동 실패**(토스 규칙: 돈 낸 사람이 못 받는 배포가 정상으로 뜨지 않게), 보호 환경에서 주소가 애플 것이 아니면 경고.

### 4.2 `payment-googleplay`
- **조회**: 단건 `GET …/purchases/productsv2/tokens/{token}`(`purchaseStateContext.purchaseState` `PURCHASED` · `PENDING` · `CANCELLED`, `orderId`, `obfuscatedExternalAccountId`, `testPurchaseContext`, `productLineItem[].productOfferDetails.quantity`) **[문서]**. 구독 `GET …/purchases/subscriptionsv2/tokens/{token}`(`subscriptionState`, `lineItems[].productId` · `expiryTime` · `latestSuccessfulOrderId` · `offerDetails.basePlanId`, `linkedPurchaseToken`, `externalAccountIdentifiers`, `testPurchase`) **[문서]**. 금액 `GET …/orders/{orderId}` 의 `total` **[문서]**. 운영(`LIVE`)은 금액 조회가 실패하면 `503`(앱이 다시 보낸다), 시험 구매에 주문이 없으면 0원 · 통화 `XXX`(ISO 4217 「통화 없음」) · 결제 번호 `pt:` + `sha256(token)`.
- **승인 통지**(`afterGrant`): 단건 `POST …/purchases/products/{productId}/tokens/{token}:acknowledge`, 구독 `POST …/purchases/subscriptions/tokens/{token}:acknowledge`(구독 id 생략 가능 — 2025-05-21 부터) **[문서]**. 이미 통지된 것은 정상. 소비(consume)는 앱이 한다(소모성은 `autoConsume`).
- **인증**: 서비스 계정 JWT(RS256, 범위 `androidpublisher`) → 접근 토큰. `FcmAccessTokens` 모양을 이 모듈 안에 옮긴다(공용으로 빼지 않는다 — 두 곳뿐이다).
- **RTDN 수신**: `POST /api/v1/payments/webhooks/googleplay`, Pub/Sub push 본문 `{ message: { data(base64), messageId }, subscription }` **[문서]**. `Authorization: Bearer <OIDC JWT>` 를 구글 공개키로 확인하고 `iss`(`accounts.google.com` 또는 `https://accounts.google.com`) · `aud`(설정) · `email`(설정한 push 서비스 계정) · `email_verified` 를 본다 **[문서]**. 틀리면 `401`, 맞으면 `200`(2xx = 받음 **[문서]**). 이벤트 키 = `messageId`, `packageName` 다르면 무시.
- **해석**(락 밖): RTDN 은 신호일 뿐이라(문서가 API 를 부르라고 한다 **[문서]**) 토큰으로 위 API 를 다시 물어 지금 사실로 `PushedPayment`/`PushedSubscription`/`PushedRefund` 를 만든다. `voidedPurchaseNotification` 은 `orderId` 의 주문 전액 환불(`refundType=1`), 수량 부분 환불(`2`)은 「확인 필요」. 구독 갱신은 `latestSuccessfulOrderId` 가 장부에 없을 때만 새 주문.
- **설정**: `enabled`(false) · `package-name` · `service-account`(비밀, 서비스 계정 JSON 의 base64 — FCM 과 같은 모양) · `pubsub.audience` · `pubsub.service-account-email` · `base-url`. 비밀 규칙은 4.1 과 같다. 구글 인증서 주소는 고정이고 시험만 바꾼다(보호 환경에서 바뀌어 있으면 기동 실패).

## 5. 지역 스위치 (`payment-iap.channels`)

```yaml
skeleton:
  payment-iap:
    enabled: false                  # 앱 yml 이 켠다
    web-checkout-url: ""            # 예: https://example.com/pricing — 웹 결제(토스) 화면, 바깥 브라우저로 연다
    channels:
      default: IAP_ONLY             # 기본: 모든 나라 · 플랫폼 인앱결제만 (= 스위치 꺼짐)
      countries: {}                 # 예) US: { ios: IAP_AND_WEB, android: IAP_ONLY }
```
- 값은 `IAP_ONLY` · `IAP_AND_WEB` · `WEB_ONLY` 셋뿐. 나라는 두 글자 대문자, 플랫폼은 `ios` · `android`, 빠진 칸은 `default`.
- 웹이 드는 값이 하나라도 있는데 `web-checkout-url` 이 비었으면 **기동 실패**(링크 없는 「웹 결제」 단추가 나가지 않게).
- 문서에 적을 것: 2026-11 기준 앱 안 링크가 자유로운 곳은 미국뿐이다. EU · 일본 링크는 애플 신청과 외부 구매 신고가 따로라 **이 스위치만으로 켜지 않는다**. 한국은 구글 2026-12-31 시행을 본 뒤 정한다(조사 문서).

## 6. 막아야 할 진짜 문제 → 막는 법

| 문제 | 막는 법 |
|---|---|
| 영수증 위조 | 앱이 보낸 영수증 · JWS 를 믿지 않는다 — 거래 id · 토큰으로 **우리 자격으로 스토어에 직접 묻는다**. 애플 응답은 고정 루트까지 체인 · 확장 OID · `bundleId` 확인, 구글은 우리 `package-name` 경로로만 묻는다. 스토어가 모르면 404 |
| 이중 지급 | 결제 번호 유니크 `(provider, provider_payment_id)`(애플 `transactionId` · 구글 `orderId`) + 구독 번호 유니크 + 지급은 기존 「주문 줄 락 안 `fulfilled_at` 한 번」. 확인 엔드포인트와 알림이 동시에 와도 줄 하나 |
| 다른 계정으로의 권한 이전 | 구매에 계정 토큰이 있으면 **부른 계정의 토큰과 같아야** 한다. 없으면(스토어에서 바로 산 오퍼 코드 등) 처음 확인받은 계정이 주인. 주인이 있는 구매는 다른 계정에 `409` — 같은 애플 · 구글 계정의 「구매 복원」도 원래 앱 계정에만 |
| 환불 뒤 권한 회수 | 환불 알림 → 환불 줄 → `onRefunded`(기존 회수 작업). 확인 전에 환불된 구매는 확인 때 스토어 상태로 `422 REVOKED` |
| 알림 위조 | 애플: 위 JWS 검증 전에는 아무것도 저장하지 않는다. 구글: OIDC 확인 + 어차피 내용은 API 로 다시 읽는다 |
| 재생 공격 | 수신함 `(provider, event_key)` 유니크(`notificationUUID` · `messageId`), 구독은 더 새로운 사건만(`provider_synced_at`), 같은 거래는 한 줄. 서명 시각 창으로 거르지 않는다 — 애플 재전송이 엿새 늦게 올 수 있고 진짜 알림의 재생은 위 셋으로 해가 없다 |

받아들이는 것: 심사 · 테스트 구매(샌드박스 · 라이선스 테스터)는 운영에서도 `TEST` 로 지급한다(정해야 할 것 1). 알림을 끝내 놓치면(서버가 엿새 넘게 죽음) 만료 · 환불이 늦게 닿는다 — 기존 「갱신 작업이 죽으면 무료 이용」과 같은 급이고 「넓힐 것」 1 이 막는다.

## 7. 시험 전략 (실계정 호출 없음, 바뀐 모듈 시험만)

- **가짜 서버**: `FakeAppStoreServer`(거래 조회 · 구독 상태 — 시험용 체인으로 서명한 JWS 를 돌려준다), `FakePlayServer`(productsv2 · subscriptionsv2 · orders · acknowledge · 토큰 주소 · OIDC 공개키). 토스 · Paddle 시험처럼 `base-url` 로 붙인다.
- **시험용 인증서 체인**: 시험 안에서 루트 · 중간(OID 포함) · 잎을 만든다 — `bcpkix-jdk18on` 을 **`payment-appstore` 의 testImplementation 에만**(제품 코드에는 없다). 검증기 빈을 시험 루트로 바꾼다.
- **반드시 덮을 것**(§6 한 줄마다 하나 이상): 다른 루트로 서명 · 중간 OID 빠짐 · `alg` 바꿈 · 다른 `bundleId` → 거절 / 같은 거래 순서대로 두 번 + 열여섯 번 동시 + 확인과 알림 경합 → 주문 한 줄 · `onPaid` 한 번 / 토큰 다른 계정 → `409` · 줄 없음, 토큰 없는 구매를 둘째 계정이 → `409` / 애플 `REFUND` · `REFUND_PRORATED` · 구글 voided → `onRefunded`, 환불 뒤 확인 → `422` / 서명 없는 · 틀린 OIDC(`aud` · `email` · 만료) → `401` 저장 없음 / 같은 `notificationUUID` · `messageId` 두 번 → 한 줄, 낡은 구독 사건 → 무시 / 구글 해석 중 API 503 → 재시도(`DEAD` 아님) / 지역 정책 표(나라 × 플랫폼, 기본 `IAP_ONLY`, URL 없이 웹 → 기동 실패).
- **변이 확인**: 위 검사를 하나씩 빼 보고 시험이 빨개지는지 모듈 문서의 「변이 확인 기록」에 남긴다(기존 결제 문서 관례).
- 장부 쪽(K2)은 메모리 저장소 계약 + `payment-jdbc` 두 방언(PostgreSQL · MySQL)의 동시성 시험.

## 8. 견본 앱 조립

- `apps/sample`: `payment-iap` · `payment-appstore` · `payment-googleplay` 세 줄. yml 에 `payment-iap.enabled: true`, `channels.default: IAP_ONLY`(미국 예시는 주석), 상품 지도 = 견본 `cheer-3000`(소모성) + 견본 월 구독 플랜 하나, 스토어 둘은 `enabled: ${…:false}` + 비밀은 환경변수. 키 없이 뜨면 `GET /iap/config` 의 `store` 가 `null`.
- 견본 통합 시험 하나: 가짜 애플 서버로 `cheer` 확인 → 응원 기록 → `REFUND` 알림 → 회수, 가짜 구글로 같은 길 한 번.
- `apps/workbench` 는 모든 모듈을 담는 규칙대로 세 모듈 + 기동 시험. `apps/api`(스타터)는 안 바꾼다.
- 카탈로그(`capabilities.json` 세 항목 · `newProjectFlag --modules payment-iap,payment-appstore` 등 · `llms.txt` 다시 만들기), `docs/modules/` 세 문서 · `docs/config/modules/` 세 yml, `docs/deploy.md` §7 비밀(`<P>_PAYMENT_APPSTORE_PRIVATE_KEY` · `<P>_PAYMENT_GOOGLEPLAY_SERVICE_ACCOUNT` …), `docs/real-provider-setup.md` 에 스토어 콘솔 설정 절차(키 만들기 · 알림 주소 · Pub/Sub push 구독과 인증 — 실행하지 않고 적기만), `CHANGELOG.md`. 짝 프런트: flutter `payment`(IAP 는 다음 단계), react 는 없음.

## 9. 작업 목록

| 키 | 내용 | 의존 | 크기 |
|---|---|---|---|
| K1 | `payment` 계약: `PaymentStoreGateway` · `StoreClaim` · `StorePurchase`, `Pushed*` 의 `mode`, 해석의 일시 실패 재시도, 스토어 구독 규칙(구독 켜기 조건 · 해지 · 변경 `409 SUBSCRIPTION_MANAGED_BY_STORE` · 결제사 해지 작업 건너뜀 · `REPLACED` · `/subscriptions/me` 의 `provider`), 시험 | — | 중간 |
| K2 | `payment` 장부 기록 `PaymentStorePurchases`(단건 `PAID` + 지급, 구독 줄 + 지금 기간 주문, 주인 규칙, 구독 맞추기, 좌석 충돌 「확인 필요」, 연결 토큰 교체), 메모리 계약 + `payment-jdbc` 두 방언 동시성 시험 | K1 | 큼 |
| K3 | `payment-iap` 새 모듈: 설정(상품 지도 · 채널 · 웹 주소, 기동 검사), 계정 토큰, `GET /config` · `POST /{store}/purchases`(요청 수 한도 · OpenAPI · 오류 코드), `IapProducts` · `IapChannelPolicy` 빈, 문서 · yml, 시험 | K2 | 중간 |
| K4 | `payment-appstore` 새 모듈: API 클라이언트(ES256 JWT · 운영 → 샌드박스), JWS 검증기(G3 고정 · OID · `bundleId` · `appAppleId`), `fetch`, 알림 해석(§2.3 표), 비밀 가드, 가짜 서버 + 시험 체인, 문서 · yml | K1 | 큼 |
| K5 | `payment-googleplay` 새 모듈: 서비스 계정 토큰, productsv2 · subscriptionsv2 · orders · acknowledge, OIDC 확인, RTDN 해석(API 다시 읽기 · voided · 연결 토큰), 비밀 가드, 가짜 서버, 문서 · yml | K1 | 큼 |
| K6 | 조립 · 카탈로그: `apps/sample` · `apps/workbench`, 견본 통합 시험(두 스토어 가짜), `capabilities.json` · `llms.txt` · `deploy.md` §7 · `real-provider-setup.md` · `CHANGELOG.md`, 변이 확인 기록 | K3, K4, K5 | 중간 |

**나란히**: K1 뒤에 K2 · K4 · K5 셋이 나란히(서로의 파일을 안 건드린다 — developer 셋이 동시에 돈다), K2 가 끝나면 K3, 마지막 K6. 전체 시험은 K6 끝에 지휘가 한 번.

## 10. 확인 못 한 것

- 안드로이드 `in_app_purchase` 의 `accountId` 가 `setObfuscatedAccountId` 로 가는지(래퍼 코드를 못 봤다).
- 애플 JWS 잎 인증서 확장 OID `1.2.840.113635.100.6.11.1` — 애플 문서는 「CPS 4.11.10 이 정한다」고만 한다. 애플 라이브러리 소스로 확인할 것.
- 구글 테스트 구매(라이선스 테스터)에 `orderId` 가 있는지, `orders.get` 이 답하는지 — 없으면 §4.2 의 0원 · `XXX` 길로 간다.
- 애플 구독을 환불한 뒤에도 자동 갱신이 이어지는지 — 이어지면 끝난 줄에 갱신이 와서 「확인 필요」가 된다.
- 구글 `products.acknowledge`(v1) 가 productsv2 시대에도 계속 권장 경로인지(문서에 폐기 표시는 없었다).
- 실제 심사 통과 여부 · 미국 애플 링크 수수료 확정 · 애플 한국 외부 링크(조사 문서와 같다).

## 정해야 할 것

1. **심사 · 테스트 구매를 운영 서버에서 인정할까?** 애플 심사원과 TestFlight 는 「가짜 돈(샌드박스)」으로 산다.
   - **인정하고 「테스트」로 표시, 매출에서 뺀다 (추천)** — 안 받으면 심사원이 결제가 안 된다고 앱을 반려한다. 업계 기본이다. 대신 테스트 초대받은 사람은 운영에서도 공짜로 쓴다.
   - 운영에서는 거절 — 심사 반려 위험.
   - 정해 둔 심사용 계정만 인정 — 계정 목록을 따로 관리해야 한다.
2. **앱에서 산 구독이 있는 사람이 탈퇴하면?** 애플은 서버가 구독을 끊을 길이 없다.
   - **우리 쪽 이용만 끝내고, 탈퇴 화면에서 「스토어에서 구독을 해지하세요」 안내 (추천)** — 애플 규정이 이 안내를 요구하고, 두 스토어가 같은 모양이라 단순하다.
   - 구글만 서버가 대신 해지(애플은 안내) — 스토어마다 동작이 달라진다.
3. **(나중에 지역 스위치를 켤 때) 앱에서 웹 결제로 넘어가면 로그인은?**
   - **웹에서 다시 로그인 (추천)** — 지금은 스위치가 꺼져 있어 쓸 일이 없고, 자동 로그인 링크는 새어 나가면 계정이 열리는 위험이 있다.
   - 한 번 쓰는 자동 로그인 링크를 서버가 만들어 준다 — 편하지만 새 기능 하나.

## 결정됨 (2026-11-04, 형 — 본문·정해야 할 것보다 우선)

1. 심사·테스트 구매: 운영에서도 인정하되 「테스트」로 표시하고 매출 집계에서 뺀다.
2. 앱 구독 보유자 탈퇴: 우리 쪽 이용만 끝내고 탈퇴 화면에 「스토어에서 해지하세요」 안내.
3. 앱 → 웹 결제 전환 시 로그인: 서버가 한 번 쓰는 자동 로그인 링크를 만들어 준다(형 선택, 추천안 아님). 짧은 수명·1회용·계정 단위 폐기로 새어 나가도 피해가 작게.
