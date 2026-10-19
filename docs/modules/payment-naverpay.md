# payment-naverpay

`payment` 의 제공자로 **네이버페이 직접 연동(결제형 REST)** 의 **단건 결제**를 구현한다 - 토스 계약 없이 네이버페이 계약만 있는 사업자를 위한 별도 결제사다(기본 꺼짐, 카카오페이 · 토스와 함께 켜면 결제 화면에서 고른다). 외부 호출은 `platform` 의 `ExternalHttpClient` 를 쓰고 네이버 SDK 는 어디에도 없다(REST 직접; 브라우저는 서버가 예약한 결제창 주소로 이동하기만 한다).
한 빈(`NaverPayProvider`)이 능력을 모두 맡아 라우터에는 결제사가 하나로 보인다: 결제 예약(`v2/reserve`) · 승인(`apply/payment`) · 취소 · 환불 · 조회(결제 내역). **정기 결제(자동결제)와 웹훅은 없다** - 네이버페이 문서에서 결제 이벤트 웹훅을 찾지 못해 승인 응답과 결제 내역 조회로 맞춘다.

**이 모듈은 진짜 네이버페이 서버로 한 번도 재 보지 않았다** - 인증값이 가맹 심사 뒤에야 나온다. 문서를 따라 만들고 엄격한 가짜 서버로 끝까지 시험했으며, 못 잰 가정은 [real-provider-setup](../real-provider-setup.md) §13 에 「진짜 키로 확인할 것」으로 모았다.

흐름(카카오페이의 준비 → 결제창 → 돌아온 주소 → 서버 승인과 닮았다): ① 주문을 만들면 서버가 결제를 **예약**한다(`v2/reserve` - `merchantPayKey` = 우리 주문 id, 금액 · 상품명 · 과세 구분 · 돌아올 주소는 서버가 정한 값) → 받은 예약 번호로 결제창 주소(`<결제창 도메인>/payments/{예약 번호}`)를 만들어 checkout 응답에 싣는다 ② 프론트가 구매자를 그 주소로 보낸다(SDK 없이 같은 창에서 이동) ③ 구매자가 결제하면 네이버페이가 `return-url` 에 `resultCode=Success&paymentId=…` 을 붙여 돌려보낸다 ④ 프론트가 `POST /orders/{id}/confirm` 에 `providerPaymentId`(= `paymentId`)와 금액을 실어 보내면 서버가 `apply/payment` 를 부른다 ⑤ 장부 `PAID`. **서버 승인 없이는 돈이 움직이지 않는다**(승인은 결제창이 열린 지 10분 안에) - 구매자가 결제창을 마치지 않은 `PENDING` 주문은 조회 없이 마감 뒤 `EXPIRED` 로 닫힌다.

**금액은 서버가 고정한다**: 네이버 SDK 로 브라우저가 결제창을 열면 구매자가 창에 넘기는 금액을 바꿀 수 있고, 승인 API 는 결제 번호만 받아(금액 없음) 낮춘 금액으로 승인돼 돈이 이미 빠진 뒤에야 알게 된다. 그래서 문서의 「SDK 미사용」 길(서버 예약 → 예약 번호로 결제창 호출)을 쓴다 - 브라우저가 쥔 값은 예약 번호뿐이다. 그래도 승인 API 는 결제 번호만 받아 **다른 주문의 결제 번호로도 승인이 일어난다**(돈이 움직인다). 그래서 승인 응답의 `merchantPayKey` 나 총액이 이 주문과 다르면 모듈이 **곧바로 전액 취소**한다(멱등 키 `mismatch-<결제 번호>`; 네이버 보안 가이드의 「승인 응답 값이 기대 값과 같은지 검증」). 취소가 확인되면(응답, 못 받았으면 조회의 전액 환불) **확정 실패**로 돌려줘 이 주문은 지급되지 않고 닫히며(원래 주인의 주문은 그대로 `PENDING` - 새 결제창으로 다시 결제한다), 확인하지 못하면 「모름」으로 돌려줘 장부가 `CONFIRMING` 에 두고 조회가 맞춘다(`ORDER_MISMATCH` · `AMOUNT_MISMATCH` 표시). 어느 쪽이든 주인에게 알린다(`alert` 가 있으면 `PAYMENT_MISMATCH_CANCELLED` · 취소가 `CancelNotComplete` 로 아직 끝나지 않았으면 낮은 심각도의 `PAYMENT_MISMATCH_CANCEL_PENDING` · 못 했거나 확인 못 했으면 `PAYMENT_MISMATCH_CANCEL_FAILED`, 키는 주문 id). 취소가 확인된 확정 거절은 `moneyReturned` 를 달아 장부가 주문을 닫을 때 그 사이 조회가 찍은 `ORDER_MISMATCH`/`AMOUNT_MISMATCH` 표시를 비운다(돈이 이미 돌아와 사람이 볼 일이 없다). 원래 주인 주문에 이력 한 줄을 남기는 자리는 코어에 없다 - 건너뛰었다. 성공 복귀 주소에는 예약 번호(`reserveId`)가 오지 않는다(실패 주소에만) - 그래서 승인 전에 예약과 짝짓는 검사는 못 한다.

프로젝트 여럿([multi-project](../multi-project.md) "결제"): 프로젝트마다 자기 `payment-naverpay.*`(클라이언트 아이디 · 시크릿 · 체인 아이디 · 돌아올 주소 …)로 게이트웨이를 만든다 - 켜지 않았거나 하나라도 비면 그 프로젝트에는 네이버페이가 없다. 시크릿 · 아이디 · 체인 아이디 · 돌아올 주소를 공통에서 물려받으면 기동 실패. 키 가드(보호 환경)는 네이버페이를 켠 프로젝트마다 돈다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment-naverpay"))` |
| 함께 오는 모듈 | `platform`, `payment`, `crypto` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.payment-naverpay` — [docs/config/modules/payment-naverpay.yml](../config/modules/payment-naverpay.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 로 켠다. 켜도 클라이언트 아이디 · 시크릿 · 체인 아이디가 하나라도 비면 제공자 빈이 없다 — 보호 환경(`skeleton.env=stage\|prod`)에서는 기동 실패, 그 밖은 기동은 되고 배포 가드가 경고한다. |
| 부팅에 필요한 것 | 없음. 켤 때 인증값 셋과 인터넷이 필요하다. 결제창을 열려면 돌아올 주소(`return-url`)도 필요하다. |
| 돌아올 주소 | `return-url` 이 기본. `allowed-return-origins`(기본 빈 목록 = 늘 `return-url`)에 든 origin 으로 시작한 결제만 그 origin 으로 돌아온다(주문 만들기의 `returnOrigin`). 목록 밖 origin 은 조용히 `return-url`. origin 모양이 아닌 줄은 경고(stage · prod 는 기동 실패), stage · prod 는 https 만 |
| 교체 지점 | `NaverPayProvider` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/payment` |
| 테스트 | `modules/payment-naverpay/src/test` |

## 하는 일

| 능력 | 네이버페이 | 메모 |
|---|---|---|
| 결제창 `PaymentCheckoutGateway` | `POST /naverpay-partner/naverpay/payments/v2/reserve` | 금액 · 주문 번호 · 과세 구분(전액 과세) · 돌아올 주소는 서버가 정한다(구매자 정보 없음), 멱등 키는 호출마다 새로(모든 예약이 새 결제창). 응답의 `reserveId` 로 `kind=NAVERPAY_REDIRECT`, `clientKey=null`, `attributes.windowUrl` = `<결제창 도메인>/payments/<reserveId>` 를 낸다. 돌아올 주소 = `return-url` + `kind=NAVERPAY_REDIRECT&provider=<결제사 id>&order=<주문 id>`. `providerPaymentId` 는 비어 있다. 원화가 아니거나 돌아올 주소가 http(s) 절대 주소가 아니거나 네이버페이가 예약에 답하지 못하면 `503 UNAVAILABLE`(주문은 `PENDING` 으로 남아 만료 작업이 닫고, 같은 키로 다시 열면 새 예약) |
| 승인 `PaymentConfirmGateway` | `POST /naverpay-partner/naverpay/payments/v2.2/apply/payment` | 폼 `paymentId` + 헤더 `X-NaverPay-Idempotency-Key: apply-<paymentId>`(같은 결제 번호의 재요청은 `409` 로 처음 응답을 받고 적용되지 않는다), 응답 제한 60초. 응답의 `paymentId`/`admissionTypeCode=01`/`admissionState=SUCCESS` 가 아니면 읽을 수 없는 응답(모름). 총액 · `merchantPayKey` 는 읽은 그대로 돌려준다(장부가 대조). 결제 번호 모양이 틀리면 결제사를 부르지 않고 400 |
| 환불 `PaymentRefundGateway` · 취소 | `POST /naverpay-partner/naverpay/payments/v1/cancel` | 폼 `paymentId` · `cancelAmount` · `cancelReason`(장부의 고정 문구 - 개인정보 금지) · `cancelRequester=2` · `taxScopeAmount`=취소액 · `taxExScopeAmount=0`, 헤더 멱등 키 = 환불 줄 id. 응답의 취소 금액 합 > 0 이어야 `REFUNDED`, 아니면 모름. `remaining` = `totalRestAmount`, 일부면 `attributes["partial"]="true"`. `CancelNotComplete`(취소가 아직 끝나지 않았다)는 취소로 읽지 않고 `PENDING`(처리 중)으로 돌려준다 - 장부가 줄에 접수 표시만 남기고 NOT_APPLIED 로 닫지 않으며 조회가 취소를 보면 맞춘다. 문서가 권하는 `doCompareRest` 는 쓰지 않는다 - 이중 환불은 멱등 키와 장부의 「환불 한 건에 호출 한 번」으로 막는다 |
| 조회 `PaymentLookupGateway` | `POST …/payments/v2.3/list/history/{paymentId}` | **결제 번호로만 묻는다.** 결제 번호 없이 주문 번호만 오면 결제를 모른다(null, 호출 없음) - 구매자가 승인 단계까지 오지 않았다는 뜻이 맞다. 승인 전 번호는 빈 목록이라 null. 승인 `01` 성공 → 받았다 / `03`(전체) · `04`(부분) 취소 성공 → 환불 줄(취소한 합이 결제액이면 전액 환불, 넘으면 읽을 수 없는 응답) / 승인 `FAIL` → 실패. 응답이 다른 결제 번호 · 다른 주문 번호면 읽을 수 없는 응답. 한 쪽(100건)을 넘는 내역은 읽을 수 없는 응답 |
| 모드 | `mode: development` → TEST, `production` → LIVE | 키 모양이 아니라 설정이 가른다. API 서버 · 결제창 도메인이 함께 따른다. 주문 줄에 박고 가드가 본다 |

## 실패 분류 - 확정 / 모름

허용 목록 방식이다. **HTTP 4xx** 이면서 문서가 「요청 자체를 받아들이지 않았다」고 말하는 코드(`Unauthorized`(401) · `RequireParameter` · `InvalidParameter` · `InvalidMerchant` · `InvalidPaymentId` · `TimeExpired` · `OwnerAuthFail` · `NotEnoughAccountBalance` · `CancelDeadlineExpired` · `TaxScope*Error` · `InvalidDiscountCancelCondition`)만 확정이고 **그 밖은 전부 모름** - 다시 보내지 말고 조회한다. 앞선 요청이 적용됐을 수 있는 코드(`AlreadyComplete` · `AlreadyOnGoing` · `AlreadyCanceled` · `OverRemainAmount` · `PreCancelNotComplete` · 멱등 키 재요청의 `409`)와 확신하지 못한 `Fail` 은 모름이다. **HTTP 200 안의 업무 오류는 확정으로 쓰지 않는다** - 업무 오류가 200 으로 오는지 4xx 인지 문서가 밝히지 않았다([real-provider-setup](../real-provider-setup.md) §13 「못 잰 가정」). 응답 본문은 예외 · 응답에 싣지 않는다 - 코드만. 다만 확정 거절 코드의 `message`(예: 등록되지 않은 가맹점)는 가려서(`PaymentFailures.safeReason`, 200자) 예약 실패 로그 한 줄에만 남긴다.

## 지켜지는 것 (시험)

결제창은 서버가 장부 금액으로 예약해 구매자가 금액을 못 바꿈 · 금액이 다른 승인 요청은 결제사를 부르기 전에 막힘 · 다른 주문의 결제 번호는 `PAID` 가 되지 않음 · 같은 결제는 두 번 반영되지 않음(재전송 · 응답 유실) · 남은 금액을 넘는 환불은 거절 · 프로젝트 A 의 키는 B 의 요청에 실리지 않음 · 공통 키 상속은 기동 실패 · 보호 환경에서 개발 모드는 기동 실패 · 모듈이 꺼져 있으면 기존 결제는 그대로 · 클라이언트 시크릿은 로그 · `toString` · 브라우저에 가는 값에 없음.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
