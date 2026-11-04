# payment-appstore

App Store(앱 안 결제)를 `payment` 의 **스토어 결제사**(`PaymentStoreGateway` + `PaymentPushGateway`)로 구현한다. 결제창도 서버 승인도 없다 — 구매는 이미 앱 안에서 끝났고, 서버는 **앱이 보낸 영수증을 믿지 않는다**. 앱이 보낸 거래 id 로 서버가 **App Store Server API 에 직접 묻고**(`GET /inApps/v1/transactions/{id}`, 구독이면 `GET /inApps/v1/subscriptions/{id}` 로 지금 상태 · 최신 거래) 애플이 서명한 답만 믿는다. 갱신 · 만료 · 환불은 애플의 **서버 알림 V2**로 받는다. 애플 Java 라이브러리는 쓰지 않는다(OkHttp · 옛 Jackson · BouncyCastle 을 끌고 온다) — 서명은 nimbus(`spring-security-oauth2-jose`) + JDK PKIX 로 한다.

**조회**: 운영 주소(`api.storekit.apple.com`)에 먼저 묻고 `404` 면 샌드박스(`api.storekit-sandbox.apple.com`)에 묻는다(심사 · TestFlight 구매는 샌드박스 거래다 — 환경 설정을 두지 않는다). 인증은 서버가 만드는 ES256 JWT(헤더 `kid` · `typ: JWT`, 클레임 `iss` · `iat` · `exp`(5분; 애플은 60분 넘는 것을 거절한다) · `aud: appstoreconnect-v1` · `bid`)이고 키(.p8)는 `private-key` 다. 429 · 5xx · 시간 초과는 던지고(호출한 쪽이 다시 시도), 둘 다 모르면 `null`(위조 · 다른 앱).

**서명 확인**(`AppStoreSignatureVerifier`, 조회 응답 · 알림 · 알림 안의 거래 정보 모두): 헤더 `alg` 가 `ES256` 이 아니면 거절 → `x5c` 가 잎 · 중간 · 루트 정확히 세 장 → JDK PKIX 로 **모듈에 넣어 둔 Apple Root CA - G3**(`src/main/resources/AppleRootCA-G3.cer`, `apple.com/certificateauthority` 의 공개 인증서, SHA-256 `63:34:3A:BF:…:3E:91:79`)까지 → 중간 인증서에 `1.2.840.113635.100.6.2.1`, 잎에 `1.2.840.113635.100.6.11.1` 확장(애플 라이브러리 `AppleExtensionCertPathChecker` 와 같은 값 · 같은 비-중요 확장) → 잎 공개키로 서명. 이어서 `bundleId` 가 설정과 같아야 하고, 운영 알림 · 구독 상태 응답은 `appAppleId` 도 같아야 한다. **폐기 확인(OCSP)은 하지 않는다.** **신뢰 루트를 바꾸는 설정은 없다** — 검증기는 `@ConditionalOnMissingBean` 빈이라 시험만 빈을 바꿔 시험용 루트를 넣는다.

**알림**: 주소는 `/api/v1/payments/webhooks/appstore`, 본문 `{"signedPayload": …}`, 주소의 비밀 조각은 없다(`signed=true` — 서명이 진위를 보증한다). 서명 · 번들 id · 앱 id 가 틀리면 `401`(애플이 재전송한다 — 1 · 12 · 24 · 48 · 72 시간 뒤, 운영만). **검증 전에는 아무것도 저장하지 않는다**(수신함 줄은 봉투를 받은 뒤에 생긴다). 이벤트 키는 `notificationUUID`(같은 알림이 두 번 오면 수신함 한 줄), 사건 시각은 `signedDate`. `TEST` 와 다루지 않는 종류(소비 요청 · 가격 인상 동의 · 내리기 예약 …)는 `IGNORED`. 읽기(§2.3 표):

| 애플 알림 | 읽은 사건 |
|---|---|
| `DID_RENEW`(`BILLING_RECOVERY` 포함) | 구독 결제(`PushedPayment`) — 기간 = 거래의 `purchaseDate` ~ `expiresDate` |
| `DID_FAIL_TO_RENEW` + `GRACE_PERIOD` | 구독 `ACTIVE`(기간 끝 = 유예 만료 시각) |
| `DID_FAIL_TO_RENEW`(유예 없음) · `GRACE_PERIOD_EXPIRED` | 구독 `PAST_DUE` |
| `DID_CHANGE_RENEWAL_STATUS` | `AUTO_RENEW_DISABLED` → `CANCELING`, `AUTO_RENEW_ENABLED` → `ACTIVE` |
| `EXPIRED` · `REVOKE` | 구독 `ENDED` |
| `DID_CHANGE_RENEWAL_PREF` + `UPGRADE` | 구독 `ACTIVE` + 바뀐 상품 id(`items`) — 내리기(`DOWNGRADE`)는 다음 갱신이 알린다 |
| `REFUND` | 환불 `APPROVED`(번호 `refund-<거래 id>`) — 전액, `revocationType=REFUND_PRORATED` 면 `revocationPercentage` 만큼 |
| `REFUND_REVERSED` | 같은 환불 번호의 `REVERSED` |

모든 사건은 거래의 `environment` 로 모드를 싣는다(`Production` → `LIVE`, 그 밖 → `TEST`) — 샌드박스 구매는 운영 서버에서도 지급되지만 `TEST` 로 표시되어 대시보드 매출에서 빠진다. 금액은 거래의 `price`(통화 단위 × 1000)를 그 통화의 최소 단위로 옮긴 **참고값**이다 — 애플은 이 값을 회계에 쓰지 말라고 하고, 정산은 App Store Connect 보고서다. 무료 체험(가격 없음)은 0 원.

**설정**: `enabled`(false) · `bundle-id` · `app-apple-id` · `issuer-id` · `key-id` · `private-key`(비밀) · `base-url` · `sandbox-base-url` · `lookup-timeout`. 다섯 값(`bundle-id` · `app-apple-id` · `issuer-id` · `key-id` · `private-key`) 중 하나라도 비면 이 결제사만 서지 않는다 — **보호 환경(`skeleton.env=stage|prod`)에서는 기동 실패**(돈 낸 사람이 못 받는 배포가 정상으로 뜨지 않게), 그 밖에서는 경고. 보호 환경에서 `base-url` · `sandbox-base-url` 이 애플 주소가 아니면(인증 토큰이 그리로 간다) 경고한다(값은 적지 않는다). 결제사 id 는 `appstore`.

프로젝트 여럿([multi-project](../multi-project.md) "결제"): 프로젝트마다 자기 `payment-appstore.*` 로 결제사를 만든다(값이 빈 프로젝트에는 없다). 키 가드는 프로젝트 모드에서 돌지 않는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment-appstore"))` |
| 함께 오는 모듈 | `platform`, `payment`, `crypto` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.payment-appstore` — [docs/config/modules/payment-appstore.yml](../config/modules/payment-appstore.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 와 다섯 값이 모두 있어야 켜진다 — 하나라도 비면 이 결제사만 없다(보호 환경에서는 기동 실패). |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `AppStorePaymentProvider`, `AppStoreSignatureVerifier`(시험용 루트를 넣을 때만) |
| 필요한 App Store Connect 설정 | 사용자 및 액세스 → 통합 → **앱 내 구입** 키(Issuer ID · Key ID · .p8), 앱 정보 → **App Store 서버 알림** 운영 · 샌드박스 URL 에 `https://<서비스 주소>/api/v1/payments/webhooks/appstore`(버전 2) |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 (모바일 앱의 in_app_purchase 가 거래 id 를 보내면 서버가 검증한다) |
| 테스트 | `modules/payment-appstore/src/test` — 가짜 서버 `FakeAppStoreServer` + 시험용 인증서 체인 `TestChain`(bcpkix 는 이 모듈 `testImplementation` 에만; 실계정 호출 없음) |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
