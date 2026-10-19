# payment-paddle

Paddle Billing(v2 API)을 `payment` 의 제공자로 구현한다. Paddle 이 판매자(해외 세금 대행)이고, 결제창은 프론트의 Paddle.js, 구독 갱신도 Paddle 이 한다.
서버가 먼저 Paddle 거래를 만들고(`POST /transactions`, 가격은 설정 `prices`) 결제창은 그 거래 id 만 연다. 서버는 **서명된 웹훅**(`Paddle-Signature` = `ts=…;h1=…`, HMAC-SHA256(`ts:원문`), 시각 허용 오차 밖이면 거절)으로 결과를 받아 장부에 옮긴다. Paddle API 는 거래 만들기 · 구독 해지 · **환불**(`POST /adjustments`)과 환불 전 확인의 **거래 조회**(`GET /transactions/{id}?include=adjustments`)에 부른다.

**환불**: 요청 금액이 거래 총액이고 승인 · 검토 중 환불이 없으면 `type: full`, 아니면 `partial` + 거래 품목 하나(서버가 만든 거래는 품목이 하나다 — 둘 이상이면 확정 거절 `PARTIAL_NOT_ALLOWED`). 금액은 구매자가 낸 세금 포함 총액 그대로다(`tax_mode` 를 보내지 않는다). 실계정은 대부분 Paddle 이 검토한 뒤(`pending_approval`) 승인 · 거절을 `adjustment.updated` 웹훅으로 알린다 — 장부는 줄을 `PENDING` 으로 두고 웹훅이 끝낸다(샌드박스는 10분마다 자동 승인). 검토 중인 환불이 있는 거래에는 새 환불을 보내지 않는다. Paddle 에는 멱등 키 헤더가 없다 — 「환불 한 줄에 호출 한 번」을 장부가 지키고, 모름(`adjustment_pending_refund_request` · `*_above_remaining_allowed` · 5xx · 시간 초과)이면 다시 보내지 않고 조회가 가린다. 확정 거절은 허용 목록(`PaddleErrorMapper.DEFINITIVE_CODES`)뿐이다. 조회는 거래 번호로만 된다(`answersByOrderId=false`). 진짜 Paddle 에서 잰 것과 못 잰 것: [real-provider-setup.md §14](../real-provider-setup.md).
웹훅 주소는 `/api/v1/payments/webhooks/paddle`. 금액 · 통화는 웹훅 값 그대로 기록한다(여러 통화).

프로젝트 여럿([multi-project](../multi-project.md) "결제"): 프로젝트마다 자기 `payment-paddle.*`(API 키 · 클라이언트 토큰 · 웹훅 비밀값)로 게이트웨이를 만든다. 웹훅은 도메인으로 프로젝트가 정해지고 그 프로젝트의 비밀값으로 서명을 확인한다 — A 의 비밀값으로 서명한 웹훅은 B 도메인에서 거절.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment-paddle"))` |
| 함께 오는 모듈 | `platform`, `payment`, `crypto` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.payment-paddle` — [docs/config/modules/payment-paddle.yml](../config/modules/payment-paddle.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 와 세 비밀(`api-key` · `client-token` · `webhook-secret`)이 모두 있어야 켜진다 — 하나라도 비면 이 결제사만 없다(서버는 뜬다). |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `PaddlePaymentProvider` |
| 필요한 API 키 권한 | 거래 만들기 · 구독 해지에 더해 **`adjustment.write` · `adjustment.read` · `transaction.read`**. 알림 대상(웹훅)에 **`adjustment.created` · `adjustment.updated`** 도 켠다 |
| 마이그레이션 | 없음 (`payment-jdbc` 의 구독 열 두 개를 쓴다) |
| 프론트 짝 | `@skeleton/payment` (Paddle.js 결제창) |
| 테스트 | `modules/payment-paddle/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
