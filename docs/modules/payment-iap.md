# payment-iap

앱이 App Store · Google Play 에서 산 것을 **서버가 스토어에 직접 물어 확인한 뒤에만** 지급하는 앱용 HTTP 둘 — 설정 응답(`GET /config`)과 구매 확인(`POST /{store}/purchases`). 지급 · 회수 · 구독 상태는 결제 장부 그대로(주문 줄 · 환불 줄 · 구독 줄 · `PaymentOrderListener`)라 웹(토스)과 앱(IAP)이 **권한 표 하나**를 같이 쓴다. 스토어와 말하는 일은 `payment-appstore` · `payment-googleplay` 가 `PaymentStoreGateway` 로 하고, 이 모듈은 스토어를 모른다(상품 id 를 그대로 받을 뿐). 장부(`payment-jdbc` 등 `PaymentStorePurchases` 빈)가 없으면 HTTP 는 하나도 서지 않는다.

**기본 꺼짐**(`skeleton.payment-iap.enabled=false`) — 앱 yml 이 켠다. 경로는 `/api/v1/payments/iap`(= `skeleton.payment.http.base-path` + `/iap`), 로그인이 필요하다.

**`GET /config?platform=ios|android&storefront=<나라>`** — `{ channel, webCheckoutUrl, store, appAccountToken, products }`.
- `channel` = 지역 스위치(`IAP_ONLY` 기본 · `IAP_AND_WEB` · `WEB_ONLY`). 나라는 `storefront`(앱이 읽은 스토어 나라 — 애플 세 글자 · 구글 두 글자, 서버가 두 글자로 바꾼다) → 접속 나라(`PaymentCountryRouting`) → 기본값 순이다. 앱이 값을 속여도 웹 결제 링크가 보일 뿐 돈 · 권한에는 길이 없다. `webCheckoutUrl` 은 채널에 웹이 들 때만 오고 바깥 브라우저로 연다(웹뷰 금지).
- `store` = 이 플랫폼의 스토어 결제사 id, 꺼져 있거나 키가 없으면 `null`.
- `appAccountToken` = `UUID.nameUUIDFromBytes("payment-iap:" + accountId)`(`StoreAccountTokens`) — 앱이 `PurchaseParam.applicationUserName` 에 넣는다. 개인정보도 비밀도 아니다. 만드는 법은 확장 지점이 아니다(바꾸면 이미 산 구매의 주인 확인이 깨진다).
- `products` = 이 플랫폼에서 파는 상품(`kind`, `itemType`·`itemRef` 또는 `plan`, `storeProductId`, 구글 구독이면 `basePlanId`).
- `WEB_ONLY` 여도 구매 확인은 열려 있다(예전 구매의 복원).

**`POST /web-checkout-link?platform=&storefront=`** — 웹 결제로 넘어갈 때 쓰는 **한 번 쓰는 자동 로그인 주소**: `{ url }` = `web-checkout-url` + `#handoff=<1회용 코드>`. `GET /config` 와 같은 방식으로 채널을 가려 웹이 없으면(`IAP_ONLY`) `404` 이고 코드를 만들지도 않는다. 코드는 `#` 조각에 실려 서버 · 프록시 접근 로그와 Referer 에 가지 않고, 응답은 `Cache-Control: no-store` 다. 웹은 조각을 읽어 `POST /api/v1/auth/web-handoff/redeem` 으로 세션을 받고 주소에서 지운다. 코드의 수명 · 폐기는 [auth-web-handoff](auth-web-handoff.md). `auth-web-handoff` 가 꺼져 있으면 이 경로 자체가 없다.

**`POST /{store}/purchases`** — `appstore`: `{ transactionId }`, `googleplay`: `{ productId, purchaseToken }`(`productId` 는 어느 API 로 물을지 고르는 힌트일 뿐 스토어의 답과 다르면 거절). `Idempotency-Key` 는 받지 않는다 — 구매 자체가 자연 키다. 순서: 스토어에 묻기(락 · 트랜잭션 밖) → 검사 → 상품 지도 → `PaymentStorePurchases.record`(트랜잭션) → 커밋 뒤 승인 통지.

| 응답 | 뜻 · 앱이 할 일 |
|---|---|
| `200 { result: GRANTED, kind, orderId, subscription }` | 지급했거나 이미 지급됨. 앱은 `completePurchase` |
| `202 { result: PENDING }` | 스토어가 아직 결제 대기. 끝내지 말고 기다린다 |
| `404 PAYMENT.IAP_PURCHASE_NOT_FOUND` | 스토어가 모른다(위조 · 다른 앱 · 힌트 불일치). 끝낸다 |
| `409 PAYMENT.IAP_PURCHASE_OWNED_ELSEWHERE` | 다른 계정의 구매. **끝내지 않는다** — 원래 계정으로 로그인하면 다시 보내진다 |
| `409 PAYMENT.ALREADY_SUBSCRIBED` | 이미 다른 구독이 살아 있다 — 돈은 장부에 넣고 「확인 필요」. 끝낸다 |
| `422 PAYMENT.IAP_PRODUCT_UNKNOWN` · `IAP_PURCHASE_REVOKED` · `IAP_PURCHASE_NOT_ELIGIBLE` | 지급 없음(지도에 없는 상품 · 종류가 지도와 다름 / 환불 · 취소 · 끝난 구독 / 가족 공유 · 수량 2 이상). 끝낸다 |
| `429 COMMON.TOO_MANY_REQUESTS` · `503 PAYMENT.UNAVAILABLE` | 요청 수 한도(`skeleton.payment.http.order-rate-limit` — 주문 만들기와 같은 값, 별도 통) / 스토어가 안 받거나 꺼짐. **끝내지 않고** 다음 실행에 다시 보낸다 |

**상품 지도**(`products`): 한 줄은 단건(`item-type` + `item-ref`) 또는 구독(`plan`) 중 하나이고 스토어 상품 id(`ios-product-id` · `play-product-id`)를 쓰는 스토어만 적는다. 같은 스토어 상품 id 가 두 줄에 있으면 **기동 실패**(구글 구독은 `play-base-plan-id` 까지 같아야 겹침). **금액의 출처는 스토어다** — 주문 금액은 스토어가 말한 값이고(정산은 스토어 보고서), `PaymentCatalog.quote` 는 주문 이름과 「살 수 있는가」를 보는 데만 쓴다: 판매가 끝난 상품이어도 이미 돈을 냈으니 지급한다(이름만 `item-ref`). 구독 줄의 플랜 · 주기는 `SubscriptionPlans.find(plan)` 값이다.

**지역 스위치**(`channels`): `default` + `countries.<나라>.<ios|android>` 표. 웹이 드는 값(`IAP_AND_WEB` · `WEB_ONLY`)이 하나라도 있는데 `web-checkout-url` 이 비었으면 **기동 실패**(링크 없는 「웹 결제」 단추가 나가지 않게). 나라가 두 글자가 아니거나 플랫폼이 `ios` · `android` 가 아니면 기동 실패. 켜도 웹 결제는 바깥 브라우저의 기존 `payment-toss` 다. 2026-11 기준 앱 안 링크가 자유로운 곳은 미국뿐이다 — EU · 일본 링크는 애플 신청과 외부 구매 신고가 따로라 **이 스위치만으로 켜지 않는다**, 한국은 구글 2026-12-31 시행을 본 뒤 정한다. 앱 → 웹으로 넘어갈 때의 자동 로그인은 아래 `web-checkout-link` 다(`auth-web-handoff` 를 얹었을 때만).

**막는 것**(스펙 §6): 영수증 위조(앱 값을 믿지 않고 스토어에 직접 묻는다) · 이중 지급(결제 번호 유니크 + 주문 줄 락 안 `fulfilled_at` 한 번) · 다른 계정으로의 권한 이전(계정 토큰이 부른 계정과 같아야 하고 토큰이 없으면 처음 확인받은 계정이 주인 — `409`) · 환불 뒤 회수(확인 전에 환불된 구매는 `422`) · 스토어 일시 실패는 `503` 이라 앱이 끝내지 않는다. 환불 검사는 두 겹이다 — 이 모듈이 `state` 로 먼저 거르고, `record` 도 환불 · 끝난 구독을 아무것도 쓰지 않고 돌려준다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment-iap"))` |
| 함께 오는 모듈 | `platform`, `payment` |
| 컴파일 전용 | `spring-security-core`(호출자 — 앱의 보안 설정이 낸다) |
| 설정 접두사 | `skeleton.payment-iap` — [docs/config/modules/payment-iap.yml](../config/modules/payment-iap.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 이고 장부가 있을 때만 경로가 선다. 설정이 잘못되면 기동 실패. |
| 부팅에 필요한 것 | `enabled=true` 면 `payment-jdbc`(장부)와 스토어 모듈 하나 이상. 구독 상품을 쓰면 `skeleton.payment.subscription.enabled=true` + `SubscriptionPlans` 빈 |
| 교체 지점 | `IapProducts`(상품 지도 — 카탈로그 DB 링크로 바꿀 자리), `IapChannelPolicy`(나라 · 플랫폼 말고 다른 기준으로 채널을 정할 때) — 둘 다 `@ConditionalOnMissingBean` |
| 마이그레이션 | 없음 |
| 프론트 짝 | flutter `payment`(IAP 는 다음 단계) |
| 테스트 | `modules/payment-iap/src/test` — 메모리 장부 + 가짜 스토어(`FakeStoreGateway`)로 컨트롤러까지(MockMvc). 실계정 호출 없음 |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
