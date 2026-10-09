# 구독(결제 2단계) 설계

> 2026-10-09 확정 (§14 의 네 가지 모두 결정됨 — 아래 §14 에 기록). 대상: kotlin-skeleton(`payment` · `payment-jdbc` · `payment-toss` · `apps/sample`) + react-skeleton(`@skeleton/payment` · `apps/sample`). 결제 장부(1단계) 위에 얹는다. 요청: "구독이랑 구독변경 … 위로밖에 못가는걸로. 차액만 결제시킬지는 … 옵션으로" · "부분결제 안했으면 구독이랑 합쳐서" · "공통은 공통 특별한건 특별하게".

## 1. 비목표

- 아래 등급으로 변경 · 일시정지 · 무료 체험 · 쿠폰 · 수량 과금 · 구독 주문의 일할 환불 · 갱신 예고/실패 알림 · 세금 · 영수증.
- 카드 바꾸기 · 카드 여러 장 · 계좌 자동결제 · 운영자의 구독 목록/강제 종료 · Stripe 구독 구현(계약 모양만 막지 않는다).

## 2. 범위

- **1차(끝에서 끝까지 한 벌)**: 플랜 목록 → 카드 등록(토스 빌링키) + 첫 결제 → 「내 구독」(상태 · 다음 결제일 · 카드 끝 네 자리) → 해지 예약(기간 끝에 종료) → 갱신 결제(작업 큐, 실패하면 재시도 뒤 종료). 형이 견본 앱에서 테스트 키로 눌러 본다.
- **2차**: 위 등급으로 즉시 변경(과금 방식 둘 — §9). 1차를 눌러 본 뒤 시작한다. **별건(1차와 독립)**: 환불 요청을 운영자가 금액을 고쳐 승인(§10).
- **다음에 넓힐 것**: 해지 예약 되돌리기 · 카드 바꾸기 · 운영자 구독 목록/강제 종료 · `BILLING_DELETED` 웹훅 수신 · 실패/종료 알림 · 가격 인상 반영 · Stripe 빌링 구현.

## 3. 상태 모형

| 상태 | 뜻 | 나가는 전이 |
|---|---|---|
| `PENDING` | 만들었고 카드 등록 · 첫 결제 전(또는 확인 중) | `ACTIVE`(첫 결제 `PAID`) · `ENDED`(첫 결제 거절) |
| `ACTIVE` | 이용 중 — 기간 끝에 갱신 | `ACTIVE`(갱신 `PAID`, 기간 +1) · `PAST_DUE`(갱신 거절) · `CANCELING`(본인 해지 예약) · `ENDED`(탈퇴 · 그 기간 주문의 전액 환불) |
| `PAST_DUE` | 갱신 실패 — 재시도 중, 이용 불가 | `ACTIVE`(재시도 `PAID`) · `ENDED`(시도 소진 · 본인 해지 · 탈퇴) |
| `CANCELING` | 해지 예약 — 기간 끝까지 이용, 갱신 없음 | `ENDED`(기간 끝 · 탈퇴) |
| `ENDED` | 끝. 빌링키를 지운다. 다시 구독하면 새 줄 | 없음 |

- 전이는 한 곳이 정하고 표 밖 전이는 버그다(주문 전이와 같은 방식). 이용 가능 = `ACTIVE` · `CANCELING` — 앱은 `PaymentSubscriptions.current(accountId)` 를 **읽을 때** 판단하고, 구독 리스너(확장 지점)는 두지 않는다(앱 표로 동기화할 쓰임이 생기면 그때).
- 추천안 적용: 기간 끝 = 첫 결제 시각 + 주기 × n(처음 시각 기준 — 누적 밀림 없음) / 갱신은 **줄에 박힌 금액**으로(카탈로그 가격을 바꿔도 기존 구독에 조용히 적용되지 않는다) / 재시도 `subscription.renewal.max-attempts`(3) · `retry-interval`(24h) / 전액 환불된 구독은 바로 끝낸다(환불했는데 다음 달 또 과금되지 않게).

## 4. 저장 — 새 V 파일만(`payment-jdbc`, 두 방언), 표 하나 `payment_subscriptions`

| 열 | 뜻 |
|---|---|
| `id`(`sub_…`) · `account_id` · `provider` · `provider_mode` · 시각 열 · `end_reason` · `account_erased_at` | 주문 줄과 같은 규칙 |
| `status` · `plan_code` · `amount` · `currency` · `period`(예 `P1M`) · `anchor_at` · `period_seq` · `current_period_start` · `current_period_end` | 박힌 플랜 · 가격 · 주기와 지금 기간 |
| `charge_order_id` · `attempt` | 지금 과금 중인 주문 · 이번 기간의 시도 번호(§5) |
| `customer_key` · `billing_token` · `card_issuer` · `card_last4` | 결제사에 댈 고객 키 · 빌링키(비밀 — 정해야 할 것 1) · 화면 표시용. 끝나거나 탈퇴하면 넷 다 NULL. 카드 바꾸기가 와도 이 칸을 갈아 끼우면 되므로 표를 나누지 않는다 |
| `active_key` | 살아 있는 동안 = `account_id`, `ENDED` 면 NULL → **계정당 살아 있는 구독 하나**(유니크 — 환불의 `open_key` 와 같은 수법) |
| `pending_plan_code` · `pending_amount` | 2차(플랜 변경)용 — 잠긴 파일은 못 고치므로 미리 둔다 |
| (주문 줄 — 표 변경 없음) | 이미 자리가 있다: `kind`(`SUBSCRIPTION_INITIAL` · `_RENEWAL`, 2차에 `_CHANGE` 값 추가) · `origin_type=SUBSCRIPTION` · `origin_ref`=구독 id · `item_type=subscription` · `item_ref`=플랜 코드 |

## 5. 갱신이 한 번만 과금되는 방법 — 주문 장부 · `dedupe_key` · 작업 큐를 그대로 쓴다

1. **운전자는 작업 하나**(`payment-subscription-renew`, 대상 = 구독 id, `runAt` = 기간 끝 — 기간을 넘기는 트랜잭션이 다음 작업을 같이 넣는다). 작업은 **구독 줄을 잠그고 다시 읽어** `ACTIVE` · `PAST_DUE` 이고 기한이 됐을 때만 진행한다(`CANCELING` 은 `ENDED` 로, 그 밖은 아무것도 안 함 — 해지 · 탈퇴 뒤 깨어난 작업은 결제사를 부르지 않는다).
2. `charge_order_id` 주문이 **끝 상태가 아니면 새 주문을 만들지 않고** 나중에 다시 본다. `FAILED` 면 `PAST_DUE` + 다음 시도(소진이면 `ENDED`). 없으면 주문을 넣는다 — `dedupe_key = sub:<구독 id>:<period_seq+1>:<attempt>`(유니크)라 여러 대가 동시에 넣어도 한 줄.
3. 과금은 승인과 같은 세 걸음: **선점(`CONFIRMING` + 조회 작업, 커밋) → 결제사 호출(락 밖) → 확정(`converge`)**. 결제사에는 `orderId` · `Idempotency-Key` 둘 다 주문 id.
4. **모름이면 다시 보내지 않는다.** 기존 조회 작업이 주문 번호로 결제사에 물어 끝낸다(결제 키가 없으면 주문 번호로 묻게 이미 돼 있다). 끝내 못 물으면 `CONFIRM_UNRESOLVED` — 사람이 볼 때까지 새 과금은 없다.
5. **기간은 지급 고리에서만 넘긴다**(주문 줄 락 안, `fulfilled_at` 이 비었을 때 한 번): `order.id == charge_order_id` 인 주문만 기간 +1 · `ACTIVE`. 다른 주문이 늦게 `PAID` 가 되면 기간은 두고 그 주문에 「확인 필요」를 찍는다(운영자가 환불). 락 순서는 **주문 → 구독** 한 방향 — 갱신 작업은 구독 락 안에서 주문 줄을 잠그지 않는다(넣기만).

## 6. 결제사 경계

- **공통**(`payment`): 구독 상태 · 주기 · 플랜 · 재시도 · 해지 · 갱신 작업 · HTTP · 탈퇴 고리 · `customer_key` 만들기 · 장부에 맞추기. **토스**(`payment-toss`): 카드 등록 창 값(`clientKey` · 종류 `TOSS_BILLING_AUTH`) · `authKey` → 빌링키 교환 · 빌링키로 승인 · 빌링키 삭제 · 거절 코드의 확정/모름 분류.
- 확장 지점 ① `PaymentBillingGateway`(결제사 능력) — 두 번째 구현(Stripe)이 계획에 있다. `billingSetup(customerKey)` · `issue(customerKey, providerPayload): 토큰 + 카드` · `charge(토큰, customerKey, 주문 id, 이름, 금액, 멱등 키): PaymentOperationResult` · `revoke(customerKey, 토큰)`. 토큰은 공통에게 불투명한 문자열이라 Stripe 는 고객 id · 결제수단 id 를 그 안에 담으면 된다 — 칸을 더 늘리지 않는다.
- 확장 지점 ② `SubscriptionPlans`(앱) — 플랜 코드 · 금액 · 주기 · 등급(`level`). 금액의 유일한 출처는 앱이라는 `PaymentCatalog` 와 같은 원칙. 스위치는 `skeleton.payment.subscription.enabled`(기본 `false`, 견본 앱이 켠다) — 켰는데 ② 나 빌링 능력이 없으면 기동 실패.
- **토스 자동결제 — 문서에서 확인한 것**(요약 도구로 읽었다: [연동 가이드](https://docs.tosspayments.com/guides/v2/billing/integration) · [API 레퍼런스](https://docs.tosspayments.com/reference) · [웹훅 이벤트](https://docs.tosspayments.com/reference/using-api/webhook-events)). **API 개별 연동 키**(`test_ck_` / `test_sk_` — 지금 쓰는 키 묶음)로 한다, 결제위젯 키가 아니다. SDK `tossPayments.payment({ customerKey }).requestBillingAuth({ method: 'CARD', successUrl, failUrl })` → `successUrl` 에 `authKey` · `customerKey`. 서버 `POST /v1/billing/authorizations/issue {authKey, customerKey}` → `billingKey` · `card` / `POST /v1/billing/{billingKey} {customerKey, amount, orderId, orderName}` → Payment 객체 / `DELETE /v1/billing/{billingKey}`. 빌링키는 다시 조회할 수 없고 **스케줄링은 토스가 하지 않는다**(→ §5).
- `customerKey` 는 영문 · 숫자 · `-_=.@` 2~50자이고 **추측 가능한 값(계정 id · 이메일 · 순번) 금지** → 무작위(`cus_` + 32자 16진수). 구매자 이름 · 이메일은 선택이라 보내지 않는다. 실결제 자동결제는 **추가 리스크 검토 · 계약** 뒤에 되고 없으면 `NOT_SUPPORTED_METHOD`. 테스트 환경은 인증 번호 `000000` · 국내 카드만. 웹훅 `BILLING_DELETED` 가 있고 서명은 없다.

## 7. HTTP 경로(사용자, 기준 `/api/v1/payments`)

| 경로 | 하는 일 |
|---|---|
| `GET /subscriptions/plans` | 플랜 목록(코드 · 이름 · 금액 · 주기 · 등급) |
| `POST /subscriptions` `{planCode}` + `Idempotency-Key` | `PENDING` 줄을 만들고 카드 등록 창 값을 돌려준다. 이미 구독 중이면 `409`. 주문 만들기와 같은 요청 수 한도 |
| `POST /subscriptions/{id}/activate` `{providerPayload:{authKey}}` | 빌링키 발급(락 밖) → 자리(`active_key`) 선점 + 첫 주문 → 과금. `200 ACTIVE` · `202` 확인 중 · `402 DECLINED`. 선점에 지면 방금 받은 빌링키를 지운다 |
| `GET /subscriptions/me` | 지금 구독(없으면 `null`) + 최근 결제 5건. 빌링키 · `customer_key` 는 싣지 않는다 |
| `POST /subscriptions/{id}/cancel` | `ACTIVE` → `CANCELING`, `PAST_DUE` 는 바로 `ENDED`. 다시 눌러도 같은 답 |
| (공통) | 남의 구독 · 없는 구독은 같은 `404`. 본문에 금액은 없다. 운영자 경로는 1차에 없다 — 구독 주문은 기존 운영자 주문 목록에 `kind` 로 보인다 |

## 8. 견본 앱 화면

- `/subscribe`: 플랜 카드 2~3장(예: 3,900 · 6,900 · 9,900원/월) + 「구독하기」 단추 → 토스 카드 등록 창 → `/subscriptions/return` 이 `authKey` 로 `activate` 하고 성공 · 확인 중 · 거절을 보여 준다(기존 `PaymentResult` 재사용).
- `/subscription`(메뉴 「구독」): 플랜 · 상태 배지 · 다음 결제일(해지 예약이면 「○월 ○일에 끝나요」) · 카드 끝 네 자리 · 「해지 예약」 단추(확인 대화상자) · 최근 결제(누르면 기존 `/payments/:id`). 견본의 주기는 설정값이다(기본 `P1M`, 로컬 `PT3M` — 갱신 · 재시도 · 종료를 몇 분 안에 본다).

## 9. 플랜 변경 과금(2차) — 위로만

| 옵션(`subscription.plan-change.charge`) | 지금 과금 | 기간 | 다음 갱신 |
|---|---|---|---|
| `FULL_RESTART` 전액 새로 | `P_new` | 지금부터 새로 시작(남은 기간은 버린다) | `P_new` |
| `PRORATED_DIFFERENCE` 남은 기간 차액만 | `floor((P_new − P_old) × 남은 초 / 기간 전체 초)` | 그대로 | `P_new` |

- 조건: `ACTIVE` · 대상 `level` > 지금 `level`(아니면 `409`) · 과금 중인 주문 없음. 변경 = 주문 한 줄(`SUBSCRIPTION_CHANGE`)이고 **`PAID` 가 된 뒤에만** 플랜이 바뀐다(거절이면 그대로). 차액이 `plan-change.min-charge`(견본 100원) 미만이면 과금 없이 바꾼다. 경로: `GET /subscriptions/{id}/change-preview?planCode=` · `POST /subscriptions/{id}/change` `{planCode, expectedCharge}` + `Idempotency-Key` — 서버가 다시 계산한 값이 `expectedCharge` 보다 크면 `409`.
- 모듈은 기본값을 고르지 않는다: 플랜 변경은 기본 꺼짐(`plan-change.enabled=false`)이고 켜는 앱이 옵션을 명시한다. 견본 앱의 값은 정해야 할 것 3(추천: 차액만).

## 10. 환불 금액 고쳐 승인(별건)

`POST /admin/payments/refunds/{id}/approve` 가 선택 본문 `{ "amount": <최소 단위 정수> }` 를 받는다. 없으면 지금처럼 요청 금액, 있으면 `0 < amount ≤ min(요청 금액, 환불 가능액)` — 넘으면 결제사를 부르지 않고 `400 REFUND_AMOUNT_EXCEEDED`. 줄의 `amount` 를 승인 금액으로 바꾸고 처음 요청한 금액은 새 열 `payment_refunds.requested_amount`(새 V 파일, NULL = 고치지 않음)에 남긴다 — 추천안 적용(이력 표에 금액 칸이 없다). 동시 승인은 지금처럼 줄 상태가 막는다(뒤쪽 `409`). 화면: `AdminRefundDialog` 승인 모드의 금액 칸을 고칠 수 있게(기본값 = 요청 금액, 상한 표시), 사용자 · 운영자의 환불 줄에 「요청 5,000원 · 환불 3,000원」.

## 11. 탈퇴 · 정지 · 이메일 없는 계정

- **탈퇴**: 기존 `PaymentAccountErasure` 가 그 계정의 구독을 `ENDED` 로 닫고 빌링키 · `customer_key` · 카드 두 칸을 지운 뒤 결제사 삭제 작업을 넣는다(멱등). 그 뒤 깨어난 갱신 작업은 아무것도 하지 않는다.
- **정지 · 삭제 유예**: `payment` 는 `account` 를 모른다 — 그대로 두면 정지됐거나 삭제를 예약한 계정도 갱신된다(정해야 할 것 2).
- **이메일 없는 계정**: 구독에 이메일이 필요 없다(결제사에도 안 보낸다). 알림이 없어 실패 · 종료는 「내 구독」 화면이 유일한 통지다. `email.required=true` 인 앱은 계정 게이트가 구독 API 도 막는다(기존 규칙).

## 12. 작업 목록(7조각)과 테스트 기준

| # | 구분 | 조각 | 검증 |
|---|---|---|---|
| 1 | 1차 · 백 | 토스 빌링 실측(§13) + `PaymentBillingGateway` + 토스 구현(창 값 · 발급 · 과금 · 삭제) | 가짜 토스 HTTP 서버 시험 + 실측 기록을 `real-provider-setup.md` 에 |
| 2 | 1차 · 백 | 구독 표 · 저장소 · 전이 · 만들기/`activate`/첫 결제/내 구독/해지 예약 + HTTP | 서비스 시험(동시 `activate` 는 하나만 과금 · 남의 구독 `404` · 빌링키가 응답 · 로그 · `toString` 에 없음) + 두 DB 저장소 시험 |
| 3 | 1차 · 백 | 갱신 작업 · 재시도 · 종료 · 지급 고리 · 탈퇴 고리 + 견본 플랜 · 설정. `CheerOrderListener` 는 `itemType == cheer` 만 받게 고친다(지금은 모든 주문에 응원 기록을 남긴다) | 두 워커가 같은 갱신을 집어도 주문 한 줄 · 호출 한 번(두 DB) / 모름이면 다시 안 보내고 조회로 끝 / 늦은 `PAID` 는 기간을 안 넘김 / 해지 · 탈퇴 뒤 과금 없음(동시에 와도) |
| 4 | 1차 · 프 | `@skeleton/payment` 구독 API · 훅 · `openBillingAuth` · `readBillingReturn` + 견본 화면(§8) | 단위 시험 + 테스트 키로 등록 → 첫 결제 → 해지 예약 → 3분 주기 갱신까지 눌러 본다 |
| 5 | 2차 · 백 | 플랜 변경(위로만 · 옵션 둘 · 미리보기) | 차액 계산 표(기간 시작 직후 · 끝 직전 · 0원 · 내림) · 아래 등급 거절 · 거절 시 플랜 불변 · 변경과 갱신 경합 |
| 6 | 2차 · 프 | 「내 구독」의 플랜 변경(금액 미리보기 → 확인) | 단위 시험 + 눌러 본다 |
| 7 | 별건 · 백+프 | 환불 금액 고쳐 승인(V 파일 · `approve` 본문 · 대화상자 · 표시) | 상한을 넘으면 결제사를 안 부름 · 결제사에 고친 금액이 감 + 운영자 화면에서 눌러 본다 |

시험은 깨지면 사고 나는 것(위 검증 칸)만 쓴다. 서비스 논리는 메모리 저장소 + `ScriptedGateway`(빌링 대역 추가)로, **두 DB 시험은 구독 저장소(자리 유니크 · 줄 락 · 두 워커 갱신)만**.

## 13. 위험 · 실측할 것

- **내 테스트 키로 계약 없이 빌링이 되는가** — 문서는 "계약 전에도 테스트 상점 키로 테스트"와 "자동결제는 추가 계약 뒤"를 둘 다 말한다. 조각 1의 첫 일로 잰다(정해야 할 것 4).
- 같이 잴 것(설계는 여기에 기대지 않는다): 빌링 승인에 `Idempotency-Key` 가 먹는지 · 같은 `orderId` 를 다시 보내면 무엇이 오는지 · `authKey` 유효 시간 · 카드 최소 결제 금액(차액 하한) · 승인 지연(`response-timeout` 30초로 충분한가) · 실패를 테스트 코드 헤더로 재현할 수 있는지.
- 갱신 작업이 죽으면 `ACTIVE` 인 채 기간이 지나 무료 이용이 된다(과금이 안 되는 쪽이라 돈 사고는 아니다) — 찾아내는 운영자 목록은 「다음」. 기한 판정은 판정하는 인스턴스의 시계다(장부와 같은 전제 — NTP).

## 14. 결정 (2026-10-09, 주인)

1. **빌링키는 암호화해서 저장한다**(`crypto` 모듈). 응답 · 로그 · `toString` 에 평문이 나오지 않는다. 구독을 켰는데 암호 키가 없으면 기동 실패.
2. **정지 · 삭제 유예 계정의 갱신 과금을 막는다**: 앱 계약 `SubscriptionRenewalGuard`(기본 = 항상 허용), 견본 앱이 계정 상태를 읽어 거절하고 그 구독은 기간 끝에 종료.
3. **견본 앱의 플랜 변경 과금은 남은 기간 차액만**(2차 — 1차 설계가 이를 막지 않게만).
4. **실측**: 테스트 시크릿 키로 빌링 API 에 닿는다(`NOT_SUPPORTED_METHOD` 가 아니라 `404 NOT_FOUND_BILLING`). 카드 등록 창 · 실제 발급 · 과금은 사람이 눌러 확인한다(조각 4).
