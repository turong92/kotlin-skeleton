# payment-googleplay

Google Play(앱 안 결제)를 `payment` 의 **스토어 결제사**(`PaymentStoreGateway` + `PaymentPushGateway`)로 구현한다. 결제창도 서버 승인도 없다 — 구매는 이미 앱 안에서 끝났고, 서버는 **앱이 보낸 구매 토큰을 믿지 않는다**. 앱이 보낸 토큰으로 서버가 **Google Play Developer API 에 직접 묻고** 구글이 답한 것만 믿는다. 갱신 · 만료 · 환불은 **RTDN(Pub/Sub push)** 으로 받되, RTDN 은 신호일 뿐이라 서버가 **토큰으로 API 를 다시 읽는다**.

**조회**(`fetch`): 단건 `GET …/purchases/productsv2/tokens/{token}`(구매 상태 `PURCHASED` · `PENDING` · `CANCELLED`, `orderId`, `obfuscatedExternalAccountId`, `testPurchaseContext`, 수량), 구독 `GET …/purchases/subscriptionsv2/tokens/{token}`(`subscriptionState`, `lineItems[]`, `linkedPurchaseToken`, `externalAccountIdentifiers`, `testPurchase`), 금액 `GET …/orders/{orderId}` 의 `total`. 단건으로 먼저 묻고 구글이 단건으로 모르면(400 · 404) 구독으로 묻는다; 둘 다 모르면 `null`. 앱이 댄 `productId` 가 구글의 답과 다르면 `null`(믿지 않는다). 429 · 5xx · 시간 초과는 던진다(앱이 다시 보낸다). 구독 번호는 `sha256(purchaseToken)` 16진수, `linkedPurchaseToken`(올리기 · 다시 구독)은 해시로 `replacesSubscriptionId` 에 실린다.
- **금액**: 운영(`LIVE`)에서 주문 조회가 실패하거나 주문이 없으면 던진다 — 공짜로 지급하지 않는다. 시험 구매(`testPurchaseContext`/`testPurchase`)는 모드가 `TEST` 이고, **주문이 없으면 0 원 · 통화 `XXX` · 결제 번호 `pt:` + `sha256(토큰)`**. 주문이 `REFUNDED` 면 확인 때 `REVOKED`.
- **승인 통지**(`afterGrant`): 지급이 커밋된 뒤 한 번 — 단건 `POST …/purchases/products/{productId}/tokens/{token}:acknowledge`, 구독 `POST …/purchases/subscriptions/tokens/{token}:acknowledge`(구독 id 생략, 2025-05-21 부터). 조회 때 이미 통지된 구매면 부르지 않고, 400 은 「이미 통지됨」으로 보아 정상이다. 3일 안에 통지하지 않으면 구글이 자동 환불한다. 소비(consume)는 앱이 한다. `afterGrant` 는 `StorePurchase` 만 받는데 토큰이 필요해 **조회 때 토큰을 기억한다**(결제 번호 → 토큰, 상한 2000 — 지급 직전 같은 요청 안에서 쓰인다; 기억에서 빠졌으면 경고만 남기고 건너뛴다. 앱이 `completePurchase` 로도 통지한다).
- **인증**: 서비스 계정 JWT(RS256, 범위 `androidpublisher`, 5분 전까지 재사용) → 접근 토큰. `notification-fcm` 의 `FcmAccessTokens` 모양을 이 모듈 안에 옮겼다. **토큰 주소는 설정이 정하고(구글 것으로 고정) 서비스 계정 JSON 의 `token_uri` 는 읽지 않는다.**

**알림(RTDN)**: 주소는 `/api/v1/payments/webhooks/googleplay`, Pub/Sub push 본문 `{ message: { data(base64), messageId }, subscription }`, 주소의 비밀 조각은 없다(`signed=true` — OIDC 토큰이 진위를 보증한다).

푸시는 `Authorization: Bearer <구글의 OIDC 토큰>` 을 싣고 오므로 이 모듈이 웹훅 경로(`<base-path>/webhooks/<provider-id>`)를 `BearerIgnoredPaths` 로 내놓는다 - 앱의 JWT 필터(`auth`)는 그 경로의 Bearer 를 로그인 토큰으로 읽지 않고 지나보내고, 진위는 아래 OIDC 확인이 가린다.

- **OIDC 확인**(`GoogleOidcVerifier`, 본문을 읽기 전): `Authorization: Bearer` 의 RS256 JWT 를 **구글 공개키**(JWKS, 한 시간 캐시, 모르는 `kid` 는 분당 한 번만 다시 받는다)로 확인하고, `iss`(`accounts.google.com` 또는 `https://accounts.google.com`) · `aud`(= `pubsub.audience`) · `email`(= `pubsub.service-account-email`) · `email_verified=true` · 만료(시계 오차 60초)를 본다. 하나라도 틀리면 `401`, **저장 없음**. 공개키를 못 받았는데 아는 키도 없으면 던진다(5xx — Pub/Sub 이 다시 보낸다; 위조로 취급하지 않는다).
- **봉투**: 이벤트 키 = `messageId`(같은 메시지가 두 번 오면 수신함 한 줄), 자원 = `sha256(토큰)`. `packageName` 이 다르면 · `testNotification` · 아래 표에 없는 종류는 `IGNORED`. 저장된 원문은 RTDN 알림 JSON 이다(토큰이 들어 있어 `toString` 에는 찍지 않는다).
- **해석**(`interpret`, 락 밖): 토큰으로 API 를 다시 물어 지금 사실로 만든다. 읽는 중 API 가 일시 실패하면 던진다 — 수신함 줄은 `DEAD` 가 아니라 재시도된다(K1 의 길). 구글이 토큰을 더는 모르면 `IGNORED`.

| RTDN | 읽은 사건 |
|---|---|
| `SUBSCRIPTION_RENEWED` · `RECOVERED` | 구독 결제(`PushedPayment`) — 결제 번호 = `latestSuccessfulOrderId`, 금액 = 그 주문의 `total`, 기간 끝 = `expiryTime`. 이미 장부에 있는 주문이면 장부가 건너뛴다(`latestSuccessfulOrderId` 가 장부에 없을 때만 새 주문) |
| `SUBSCRIPTION_RESTARTED` · `IN_GRACE_PERIOD` | 구독 `ACTIVE`(스토어의 지금 상태) |
| `SUBSCRIPTION_CANCELED` | 구독 `CANCELING`(만료 전) |
| `SUBSCRIPTION_ON_HOLD` · `PAUSED` | 구독 `PAST_DUE` |
| `SUBSCRIPTION_EXPIRED` · `REVOKED` | 구독 `ENDED` |
| `voidedPurchaseNotification` `refundType=1` | 환불 `APPROVED`(번호 `void-<orderId>`) — 그 주문 전액(금액은 `orders.get`), 구독이면 구독 번호도 싣는다 |
| `voidedPurchaseNotification` `refundType=2`(수량 부분 환불) | **확인 필요** — 금액을 알 수 없어 던진다(수신함 줄이 `DEAD` 로 남아 사람이 본다) |
| 첫 구매(`SUBSCRIPTION_PURCHASED`) · 가격 변경 · 미룸 · 일시정지 예약 · 단건 알림 | `IGNORED` — 첫 구매는 앱이 확인받는다 |

모든 사건은 `testPurchase`/`testPurchaseContext` 로 모드를 싣는다(있으면 `TEST`, 아니면 `LIVE`) — 시험 구매는 운영 서버에서도 지급되지만 `TEST` 로 표시되어 대시보드 매출에서 빠진다.

**설정**: `enabled`(false) · `package-name` · `service-account`(비밀) · `pubsub.audience` · `pubsub.service-account-email` · `base-url` · `token-url` · `certs-url` · `lookup-timeout`. 네 값 중 하나라도 비었거나 서비스 계정을 읽을 수 없으면 이 결제사만 서지 않는다 — **보호 환경(`skeleton.env=stage|prod`)에서는 기동 실패**(돈 낸 사람이 못 받는 배포가 정상으로 뜨지 않게), 그 밖에서는 경고. `token-url` · `certs-url` 은 구글 주소로 고정이고 시험만 바꾼다 — **보호 환경에서 기본 구글 주소와 정확히 같지 않으면 기동 실패**(서명한 자격이 그리로 간다 · 알림 위조 방어가 풀린다). `base-url` 이 구글 주소가 아니면 경고. 값은 메시지에 적지 않는다. 결제사 id 는 `googleplay`.

프로젝트 여럿([multi-project](../multi-project.md) "결제"): 프로젝트마다 자기 `payment-googleplay.*` 로 결제사를 만든다(값이 빈 프로젝트에는 없다). 키 가드는 프로젝트 모드에서도 돈다(켠 프로젝트마다 같은 규칙, 메시지 앞에 `project '<id>': `).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment-googleplay"))` |
| 함께 오는 모듈 | `platform`, `payment`, `crypto` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.payment-googleplay` — [docs/config/modules/payment-googleplay.yml](../config/modules/payment-googleplay.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 와 네 값이 모두 있고 서비스 계정을 읽을 수 있어야 켜진다 — 아니면 이 결제사만 없다(보호 환경에서는 기동 실패). |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `GooglePlayPaymentProvider` |
| 필요한 Google 설정 | Play Console → API 액세스에서 서비스 계정에 **재무 데이터 보기 · 주문 및 구독 관리** 권한, 수익 창출 설정 → 실시간 개발자 알림 주제(Pub/Sub)에 **push 구독**(엔드포인트 `https://<서비스 주소>/api/v1/payments/webhooks/googleplay`, 인증 켜기: 서비스 계정 이메일 · 대상 = `pubsub.audience`) |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 (모바일 앱의 in_app_purchase 가 setObfuscatedAccountId 로 계정 토큰을 싣고 구매 토큰을 보내면 서버가 검증한다) |
| 테스트 | `modules/payment-googleplay/src/test` — 가짜 서버 `FakePlayServer`(토큰 주소 · productsv2 · subscriptionsv2 · orders · acknowledge · OIDC 공개키)와 시험용 서명 키(실계정 호출 없음) |

## 변이 확인 기록

검사를 하나씩 빼 보고 시험이 빨개지는지 손으로 확인했다(2026-11-04).

| 뺀 검사 | 빨개진 시험 |
|---|---|
| OIDC `aud` | `a push without a token, with a token for another audience, …` |
| OIDC `email` | 같은 시험 |
| OIDC `email_verified` | 같은 시험 |
| OIDC 만료 | `an expired token, another issuer and a token signed by a key Google does not publish are rejected` |
| 앱이 댄 `productId` ↔ 구글 답 | `an answer for another product than the app claimed is refused` |
| 운영 금액 조회 실패 → 던짐 | `a live purchase whose order Google does not know is a transient failure` |
| 앱의 JWT 필터가 푸시 경로의 Bearer 를 건너뜀(`googlePlayBearerIgnored`) | 고치기 전 상태 그대로 `apps/sample` 의 `IapJourneyIntegrationTest` 가 `401` 로 빨개졌다(푸시의 구글 OIDC 토큰을 앱의 로그인 토큰으로 읽어 막았다). 모듈 시험은 앱 보안 체인이 없어 못 본다 |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
