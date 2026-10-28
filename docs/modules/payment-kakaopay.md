# payment-kakaopay

`payment` 의 제공자로 **카카오페이 직접 연동(온라인 결제 REST)** 의 **단건 결제**를 구현한다 - 토스 계약 없이 카카오페이 계약만 있는 사업자를 위한 별도 결제사다(기본 꺼짐, 토스와 함께 켜면 결제 화면에서 고른다). 외부 호출은 `platform` 의 `ExternalHttpClient` 를 쓰고 카카오 SDK 는 받지 않는다(REST 직접).
한 빈(`KakaoPayProvider`)이 능력을 모두 맡아 라우터에는 결제사가 하나로 보인다: 결제창 값(`ready`) · 승인(`approve`) · 취소 · 환불 · 조회. **웹훅은 없다** - 카카오페이 문서에서 결제 이벤트 웹훅을 찾지 못해 승인 응답과 주문 조회로 맞춘다. **정기 결제(SID)는 `subscription-cid`(정기 가맹점 코드)를 설정했을 때만** 같은 이름의 하위 클래스(`KakaoPayBillingProvider`)가 능력으로 드러낸다 - 아래 「정기 결제」.

흐름(토스의 결제창 → 돌아온 주소 → 서버 승인과 닮았다): ① 주문을 만들면 서버가 `ready` 를 부른다(`partner_order_id` = 우리 주문 id, `partner_user_id` = 계정 id, 성공 · 취소 · 실패 주소 = `return-url` + 조각 - 주문 만들기가 `returnOrigin` 을 보냈고 그 origin 이 `allowed-return-origins` 에 있으면 `return-url` 의 scheme · host 만 그 origin 으로 바뀐다) → 받은 `tid` 를 **주문의 결제 키 자리에 박는다**(`CheckoutInstruction.providerPaymentId`) · 결제창 주소(PC · 모바일 · 앱)는 checkout 응답 `attributes` 로 프론트에 간다 ② 구매자가 카카오톡에서 인증을 마치면 카카오페이가 `return-url` 로 `pg_token` 을 붙여 돌려보낸다 ③ 프론트가 `POST /orders/{id}/confirm` 에 `approvalToken`(= `pg_token`)과 금액을 실어 보내면(거래 번호는 주문에 박혀 있어 보내지 않아도 된다) 서버가 `approve` 를 부른다(총액은 장부의 주문 금액 - 다르면 카카오페이가 거절) ④ 장부 `PAID`. **서버 승인 없이는 돈이 움직이지 않는다** - 구매자가 승인하지 않은 `PENDING` 주문은 조회 없이(거래 번호가 있어도 `READY`) 마감 뒤 `EXPIRED` 로 닫힌다.

프로젝트 여럿([multi-project](../multi-project.md) "결제"): 프로젝트마다 자기 `payment-kakaopay.*`(시크릿 키 · 가맹점 코드 · 돌아올 주소 …)로 게이트웨이를 만든다 - 켜지 않았거나 시크릿 키 · 가맹점 코드가 비면 그 프로젝트에는 카카오페이가 없다. 시크릿 키 · 가맹점 코드 · 돌아올 주소를 공통에서 물려받으면 기동 실패. 키 가드(보호 환경)는 카카오페이를 켠 프로젝트마다 돈다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment-kakaopay"))` |
| 함께 오는 모듈 | `platform`, `payment`, `crypto` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.payment-kakaopay` — [docs/config/modules/payment-kakaopay.yml](../config/modules/payment-kakaopay.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 로 켠다. 켜도 시크릿 키나 가맹점 코드가 비면 제공자 빈이 없다 — 보호 환경(`skeleton.env=stage\|prod`)에서는 기동 실패, 그 밖은 기동은 되고 배포 가드가 경고한다. |
| 부팅에 필요한 것 | 없음. 켤 때 시크릿 키 · 가맹점 코드와 인터넷이 필요하다. 결제창을 열려면 돌아올 주소(`return-url`)도 필요하다. |
| 돌아올 주소 | `return-url` 이 기본. `allowed-return-origins`(기본 빈 목록 = 늘 `return-url`)에 든 origin 으로 시작한 결제만 그 origin 으로 돌아온다 - 로그인 저장소가 origin 마다 따로라 다른 주소로 돌아오면 로그인이 풀린다. 목록 밖 origin 은 조용히 `return-url`. 허락한 origin 마다 개발자센터 「플랫폼 > Web > 사이트도메인」 등록이 필요하고(안 하면 `ready` -400), origin 모양이 아닌 줄은 경고(stage · prod 는 기동 실패), stage · prod 는 https 만 |
| 교체 지점 | `KakaoPayProvider` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/payment` |
| 테스트 | `modules/payment-kakaopay/src/test` |

## 하는 일

| 능력 | 카카오페이 | 메모 |
|---|---|---|
| 결제창 `PaymentCheckoutGateway` | `POST /online/v1/payment/ready` | `kind=KAKAOPAY_REDIRECT`, `attributes` = `pcUrl` · `mobileUrl` · `appUrl`(http(s) 절대 주소만), `clientKey=null`(공개 키가 없다), `providerPaymentId` = `tid`. 금액 · 상품명은 서버가 정한다. 성공 · 취소 · 실패 주소 = `return-url` + `kind=KAKAOPAY_REDIRECT&provider=<결제사 id>&order=<주문 id>&result=success\|cancel\|fail`(성공일 때 카카오페이가 `pg_token` 을 이어 붙인다). 카카오페이가 답하지 못하면 `503 UNAVAILABLE`(주문은 `PENDING` 으로 남아 만료 작업이 닫고, 같은 키로 다시 열면 새 `tid`) |
| 승인 `PaymentConfirmGateway` | `POST /online/v1/payment/approve` | `tid` · `partner_order_id` · `partner_user_id` · `pg_token` · `total_amount`(장부의 주문 금액). 응답의 값만 쓴다 - `tid` · `partner_order_id` 가 묻은 것과 다르거나 총액이 없으면 읽을 수 없는 응답(모름). 승인 토큰이 없거나 모양이 틀리면 결제사를 부르지 않고 400 |
| 환불 `PaymentRefundGateway` · 취소 | `POST /online/v1/payment/cancel` | `cancel_amount`, `cancel_tax_free_amount=0`(부가세는 보내지 않아 카카오페이가 계산). 응답 `status` 가 `PART_CANCEL_PAYMENT` · `CANCEL_PAYMENT` 이고 `approved_cancel_amount.total` 이 있어야 `REFUNDED`, 아니면 모름. `remaining` = `cancel_available_amount.total`, 일부면 `attributes["partial"]="true"`. 카카오페이에는 멱등 키가 없다 - 장부가 환불 한 건에 호출을 한 번만 하고 모름은 조회로 가린다 |
| 조회 `PaymentLookupGateway` | `POST /online/v1/payment/order` | **거래 번호(`tid`)로만 묻는다.** 주문 번호만 오면 결제를 모른다(null, 호출 없음) - 구매자가 승인하지 않았으면 돈이 안 움직였다는 뜻이 맞다. 상태 대응: `READY`~`AUTH_PASSWORD` → 진행 중 / `SUCCESS_PAYMENT` → 받았다 / `PART_CANCEL_PAYMENT` → 일부 환불 / `CANCEL_PAYMENT` → 전액 환불 / `QUIT_PAYMENT` · `FAIL_*` → 실패. 환불은 `payment_action_details` 의 `CANCEL` 줄에서. 모르는 상태(`ISSUED_SID` 포함)는 추측하지 않고 읽을 수 없는 응답 |
| 모드 | 가맹점 코드 `TC0ONETIME` · `TCSUBSCRIP` · `TCSEQUENCE` → TEST, 그 밖 → LIVE | 카카오페이는 테스트 키와 실결제 키를 키 모양으로 가르지 않는다 - 가맹점 코드가 가른다. 주문 줄에 박고 가드가 본다 |

## 정기 결제 (`subscription-cid` 가 있을 때만)

카드 등록 단계가 따로 없다 - **첫 결제가 곧 등록**이다. ① 구독을 시작하면 서버가 정기 가맹점 코드로 `ready` 를 부르고(`partner_order_id` = 첫 주문 id `ord_…`) 받은 `tid` 를 구독 줄에 박는다 · 창 주소는 `billing.attributes`(`pcUrl` · `mobileUrl` · `appUrl`), 돌아올 주소는 `…&kind=KAKAOPAY_BILLING_REDIRECT&provider=<id>&sub=<구독 id>&result=…` ② 구매자가 인증하고 돌아오면 프론트가 `POST /subscriptions/{id}/activate` 에 `{providerPayload:{pg_token}}` 을 보낸다 ③ 서버가 자리와 첫 주문을 선점해 커밋한 뒤 **`approve` 를 한 번** 부르고, 응답의 `sid` 를 암호화해 구독 줄에 저장한다(응답 · 로그 · `toString` 에 안 나온다) ④ 갱신은 `POST /online/v1/payment/subscription`(`sid` · 새 주문 번호). 해지 · 탈퇴는 `inactive`(실패하면 `status` 로 이미 비활성인지 확인 - 멱등).

- `-782`(정기 결제 실패)만 확정 거절이고 그 밖의 오류는 모름이다. 모름은 같은 주문을 다시 보내지 않는다: 이 결제사는 주문 번호로 물을 수 없어(`answersByOrderId=false`) 거래 번호가 없으면 사람이 본다(`CONFIRM_UNRESOLVED`).
- 1회차 응답을 못 받았거나 `sid` 를 못 읽으면 돈은 움직였을 수 있다 - 토큰 없는 구독은 다음 갱신에서 과금 없이 `PAYMENT_FAILED` 로 끝난다(조회가 1회차 결제를 확인해도 SID 는 조회로 오지 않는다).
- 정기 주문의 조회 · 환불은 정기 가맹점 코드로 한다(`recurring`). 이 코드에는 `cid_secret` 을 보내지 않는다(설정 칸이 없다 - 인증키가 필요한 계약이면 못 쓴다).
- `subscription-cid` 의 모드(테스트/실결제)가 `cid` 와 다르면 기동 실패한다.
- 1회차를 **0원**으로 보내면 승인 없이 `sid` 만 발급된다(공식 문서 정기 결제 「SID 발급」 절) - 무료 첫 기간 쿠폰이 이 길을 쓴다.
- 카드 발급사는 `card_info.kakaopay_issuer_corp_code` 로 읽는다(필드 이름은 문서 기준 가정 - 진짜 키로 확인: [real-provider-setup](../real-provider-setup.md) 12.3).

## 실패 분류 - 확정 / 모름

허용 목록 방식이다. 4xx 의 **공통 오류 -400(잘못된 요청) · -401(키 오류) · -403(사용 API 없음)** 만 확정(요청을 받아들이지 않았다)이고 **그 밖은 전부 모름** - 다시 보내지 말고 조회한다. 승인 실패(-780) · 취소 실패(-781)는 일부러 확정에서 뺐다: 문서의 예시(`USER_LOCKED` 「진행중인 거래가 있습니다」 · `6666` 「원거래없음」)가 앞선 요청이 처리 중이거나 이미 처리됐다는 뜻일 수 있어 돈이 움직이지 않았다고 말할 근거가 없다(진짜 키로 `extras.method_result_code` 를 재면 카드 거절 코드를 더할 수 있다 - [real-provider-setup](../real-provider-setup.md) 카카오페이 절의 「못 잰 가정」). 응답 본문은 예외 · 응답에 싣지 않는다 - 코드만. 다만 공통 오류(-400 · -401 · -403)의 `error_message`(예: 등록되지 않은 도메인)는 가려서(`PaymentFailures.safeReason` - 토큰 · 비밀 · 키 쌍 · 이메일 · 6자리 이상 숫자 가림, 200자) `ready` 실패 로그 한 줄에만 남긴다. 승인 · 취소 오류(-780 · -781)와 `method_result_message` 는 사람 정보가 섞일 수 있어 싣지 않는다.

## 지켜지는 것 (시험)

금액이 다른 승인은 거절 · 같은 결제는 두 번 반영되지 않음(재전송 · 응답 유실) · 남은 금액을 넘는 환불은 거절 · 바꿔치기한 거래 번호는 결제사를 부르기 전에 막힘 · 프로젝트 A 의 키는 B 의 요청에 실리지 않음 · 공통 키 상속은 기동 실패 · 보호 환경에서 테스트 가맹점 코드는 기동 실패 · 모듈이 꺼져 있으면 기존 결제는 그대로 · 비밀 키 · 승인 토큰은 로그 · `toString` 에 찍히지 않음.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
