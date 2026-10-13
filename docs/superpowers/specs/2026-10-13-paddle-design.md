# Paddle(해외 결제) — 짧은 설계 초안

날짜 2026-10-12. 근거: `overseas-subscription-providers.md`, `korean-indie-overseas-payments.md`.

## 형 결정 (원문)
- "그럼 패들로 해보자" / 오베이션 "해외도 바로 같이 오픈" / 돈 받는 형태 "아직 미정" → 단건·구독 둘 다 받을 수 있게.
- "다 하면 paddle 도 해놓고. 로그인 화면 aside ovation profile 에 열어놨으니까 거기서 확인하면서"

## 토스와 무엇이 다른가
| | 토스(지금) | Paddle |
|---|---|---|
| 판매자 | 우리 | Paddle(MoR) — 해외 세금 신고·납부 대행 |
| 구독 갱신 | 우리 서버가 때마다 빌링 호출 | **Paddle 이 알아서 결제**, 우리는 웹훅으로 결과만 받음 |
| 금액의 출처 | 우리 장부 | Paddle 의 가격(price id). 우리는 웹훅 금액을 기록 |
| 결제창 | 토스 창 | Paddle.js 오버레이 결제창(카드·PayPal·Apple Pay 등, 나라·세금 자동) |
| 환불 | 우리가 요청 → 토스 | 우리가 요청(adjustment) → **Paddle 이 검토 후 처리**, 결과는 웹훅 |
| 나라 | 우리 화면에서 받음 | Paddle 결제창이 받음, 웹훅으로 옴 |

## 1차(최소 버전) — 끝에서 끝까지 한 번 도는 것
1. **`payment-paddle` 모듈**: 설정(API 키·클라이언트 토큰·웹훅 비밀값·환경 sandbox/live), Paddle API 호출(구독 조회·해지), 웹훅 서명 검증(`Paddle-Signature`, HMAC).
2. **구독 시작**: 프론트가 Paddle.js 로 결제창을 연다(price id + `customData` 에 우리 계정 id·우리 구독 줄 id). 결제가 끝나면 Paddle 이 웹훅 → 우리 구독 줄을 `ACTIVE` 로.
3. **웹훅 → 장부**: `transaction.completed`(결제 = 주문 한 줄 PAID, 금액은 웹훅 값), `subscription.created/updated/canceled/past_due`(구독 상태 미러링). 기존 웹훅 수신함(중복 제거·재처리)을 그대로 쓴다.
4. **우리 갱신 작업은 Paddle 구독을 건너뛴다** — 갱신은 Paddle 이 한다(이중 과금 방지의 핵심).
5. **해지**: 「기간 끝에 해지」 → Paddle API `cancel(effective_from = next_billing_period)`. 결과는 웹훅.
6. **단건 결제**: 같은 결제창으로 1회 가격 → `transaction.completed` 로 주문 PAID.
7. **견본 앱**: 나라/설정으로 결제사를 고른다 — 한국 = 토스, 그 밖 = Paddle(일단 설정 한 줄로 전체 전환도 가능하게). 「내 구독」·결제 내역 화면은 그대로.
8. **e2e**: 가짜 Paddle(웹훅을 서명해서 쏘는 시험 서버)로 시작·갱신·해지 한 흐름.

## 1차에서 안 하는 것 (2차)
플랜 올리기(Paddle `PATCH subscription` + 차액 방식), 환불 요청(adjustment), 운영자 화면의 Paddle 전용 동작, 상품·가격 DB 와의 동기화(지금은 설정에 price id 를 적음), 중도 해지 환불(Paddle 정책을 따름 — 우리 한국 정책 부품은 토스 경로 전용).

## 밖에서 할 일
- **웹훅 공개 주소**: `skel.ovation.fans` 는 Cloudflare Access 가 막고 있음 → `/api/v1/payments/webhooks/paddle` 경로만 Access 예외(Bypass) 정책. 형이 대시보드에서(내가 단계 안내).
- **Paddle 샌드박스**(형 계정, Aside ovation 프로필): 상품·가격 만들기, 웹훅 대상 등록(위 주소, 이벤트 선택), 클라이언트 토큰·API 키 발급. 설정 변경은 내가 할 때마다 무엇을 바꾸는지 먼저 말하고 OK 받고 누름. **키·비밀값은 형이 `.env.local` 에 직접**(`PADDLE_API_KEY`, `PADDLE_CLIENT_TOKEN`, `PADDLE_WEBHOOK_SECRET`, `PADDLE_ENVIRONMENT=sandbox`).
- 진짜 Paddle 샌드박스 결제는 테스트 카드로 형이 누름(카드 입력은 내가 못 함).

## 크기 (예상)
조각 4(모듈·웹훅 / 장부 연결·갱신 건너뛰기 / 프론트 결제창·견본 / 가짜 Paddle e2e), developer 1명, 리뷰 1 + 확인 1, 끝 검사 1. 3~4시간 + 샌드박스 설정 30분.

## 정해야 할 것
1. 견본 앱에서 Paddle 을 언제 쓰나 — **형 결정: 한국 아니면 Paddle** (설정으로 전체 전환도)
2. 통화 — **형 결정: 여러 통화** (Paddle 가격에 통화별 금액, 구매자 지역 통화로 결제창이 보여 줌. 우리 장부는 웹훅의 통화·금액을 그대로 기록)
