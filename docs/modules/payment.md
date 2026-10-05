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

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
