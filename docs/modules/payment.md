# payment

제공자 중립 결제 계약(`PaymentService`, `PaymentProviderRouter`)이다.
제공자(`payment-toss`, `payment-stripe`)가 각자 등록하고, 라우터가 이름으로 고른다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.payment` — [docs/config/modules/payment.yml](../config/modules/payment.yml) |
| 기본 동작 | 켜짐 (라우팅만). |
| 부팅에 필요한 것 | 없음. 제공자 모듈이 있어야 결제할 수 있다. |
| 교체 지점 | `PaymentProviderRouter`, `PaymentService` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/payment/src/test` |

## 왜 HTTP 엔드포인트가 없나 — 만들려면 필요한 것

`PaymentService.confirm` 은 금액을 **그대로** 제공자에 넘긴다. 브라우저가 돌려보내는 `amount` 는 주소에서 온 값이라 믿을 수 없고, 맞는 금액은 앱의 주문 저장소에만 있다.
그래서 모듈이 `POST /api/v1/payments/confirm` 을 열려면 아래가 먼저 계약이어야 한다 (지금은 앱 컨트롤러가 직접 한다):

1. `PaymentOrderResolver`(앱이 구현): `orderId` + 호출자 → 결제할 금액 · 통화 · 소유자 확인 · 이미 결제됐는지. 모듈은 요청의 `amount` 와 이 값이 다르면 400 으로 막는다.
2. 결제 결과를 앱의 주문 상태에 반영하는 훅(`PaymentConfirmedListener`) — 트랜잭션 경계는 앱의 것이다.
3. `Idempotency-Key` 필수(`idempotency` 모듈 의존이 생긴다)와 웹훅(토스 · 스트라이프 서명 검증)은 제공자 모듈마다 다르다.

이 셋이 정해지기 전에 열면 "금액 검증이 없는 결제 확정 엔드포인트" 가 기본값이 되므로 열지 않았다. 프론트 `@skeleton/payment` 도 같은 이유로 기본 경로가 없다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
