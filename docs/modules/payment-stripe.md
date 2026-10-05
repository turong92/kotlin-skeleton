# payment-stripe

`payment` 의 제공자로 Stripe 결제 승인 · 취소를 구현한다. 외부 호출은 `platform` 의 `ExternalHttpClient` 를 쓴다.
에러는 `ExternalHttpErrorMapper` 로 표준 결제 예외에 옮긴다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment-stripe"))` |
| 함께 오는 모듈 | `platform`, `payment` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.payment-stripe` — [docs/config/modules/payment-stripe.yml](../config/modules/payment-stripe.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 로 켠다. |
| 부팅에 필요한 것 | 없음. 켤 때 secret key 와 인터넷이 필요하다. |
| 교체 지점 | `StripePaymentProvider` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/payment-stripe/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
