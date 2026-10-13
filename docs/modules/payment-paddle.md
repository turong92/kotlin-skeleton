# payment-paddle

Paddle Billing(v2 API)을 `payment` 의 제공자로 구현한다. Paddle 이 판매자(해외 세금 대행)이고, 결제창은 프론트의 Paddle.js, 구독 갱신도 Paddle 이 한다.
서버가 먼저 Paddle 거래를 만들고(`POST /transactions`, 가격은 설정 `prices`) 결제창은 그 거래 id 만 연다. 서버는 **서명된 웹훅**(`Paddle-Signature` = `ts=…;h1=…`, HMAC-SHA256(`ts:원문`), 시각 허용 오차 밖이면 거절)으로 결과를 받아 장부에 옮기고, Paddle API 는 거래 만들기와 구독 해지에만 부른다.
웹훅 주소는 `/api/v1/payments/webhooks/paddle`. 금액 · 통화는 웹훅 값 그대로 기록한다(여러 통화).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:payment-paddle"))` |
| 함께 오는 모듈 | `platform`, `payment`, `crypto` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.payment-paddle` — [docs/config/modules/payment-paddle.yml](../config/modules/payment-paddle.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 와 세 비밀(`api-key` · `client-token` · `webhook-secret`)이 모두 있어야 켜진다 — 하나라도 비면 이 결제사만 없다(서버는 뜬다). |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `PaddlePaymentProvider` |
| 마이그레이션 | 없음 (`payment-jdbc` 의 구독 열 두 개를 쓴다) |
| 프론트 짝 | `@skeleton/payment` (Paddle.js 결제창) |
| 테스트 | `modules/payment-paddle/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
