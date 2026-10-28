# 이벤트 · 보상(쿠폰 · 포인트) · 결제 면제 — 설계

> 2026-10-28. 요청(형 원문 요지): 출석 · 기념일 · 친구초대 · 공유 · 달성 같은 이벤트, 보상은 쿠폰이나 포인트(경우에 따라 배지). 「중간에 허리 끊어놓고 조립식으로」, 「이벤트 자체도 하나의 트랜잭션으로 묶기보다 이벤트처리 / 보상지급으로 나눠서」. 쿠폰은 결제 금액을 깎는다. 포인트는 내부 서비스용(결제 차감은 지금 안 만들지만 붙일 수 있게). 「어드민 권한은 결제 면제 같은거도 있음 좋겠어. 국룰따라서」. 추가: 「n달 무료. 첫 결제 무료 n달 할인 등 복합 쿠폰도 있긴해. 마케팅쪽이 지들 하고싶은대로 막 넣어달라고해서」.
> 대상: kotlin-skeleton(새 `campaign` · `campaign-jdbc` · `reward` · `reward-jdbc` · `coupon` · `coupon-jdbc` · `point` · `point-jdbc`, 기존 `platform` · `payment` · `payment-jdbc` · `account` · `apps/sample`) + react-skeleton(새 `@skeleton/rewards`, 기존 `@skeleton/payment` · `apps/sample`).
> 근거: 결제 연동 조사(2026-10-28, 읽기만 — 금액 출처 `PaymentCatalog.quote()`, 전이표, 0원 차단, 결제사별 금액 계산 주체), 이번에 확인한 코드(`PaymentOrders.create` · `PaymentFulfillment` · `SubscriptionLifecycle.chargeOrder` · `SubscriptionRenewTaskHandler` · `SubscriptionPlanChanges` · `SubscriptionCancellations` · `KakaoPayBillingProvider.registrationCharges`).

## 형이 정한 것 (대화 원문)

| # | 물음 | 결정 |
|---|---|---|
| 1 | 구조 | **① 이벤트 처리**(조건 판정 → 「지급 건」 기록에서 트랜잭션 끝) **② 지급 일꾼**(따로 돈다, 재시도, 지급 건 단위 멱등) **③ 보상 모듈**(쿠폰 · 포인트 — 각각 단독 사용 가능). ①은 ③을 모르고 「보상 지급기」 계약만 안다 |
| 2 | 쿠폰 | 결제 금액을 깎는다 |
| 3 | 포인트 | 내부 서비스용. 결제 차감은 지금 안 만들되 붙일 자리는 지금 설계에 |
| 4 | 첫 판 범위 | 이벤트 틀 + 출석 · 친구초대, 지급 일꾼, 쿠폰(결제 할인 포함), 포인트(내부용). 기념일 · 공유 · 달성 · 배지는 다음 판 |
| 5 | 결제 면제 | 권한 하나로 「구매했나」를 통과 + 운영자의 무료 이용권 지급 |
| 6 | 쿠폰 모양 | 마케팅이 코드 수정 없이 조합(n달 무료, 첫 결제 무료 + n달 할인)을 늘릴 수 있게 — 구독에서 쓸 수 있는 조합형 |
| 7 | 구독 매 갱신 할인 · n달 무료 | 「넣는다」 → **구독 할인 조각(K7)을 첫 판에 포함**(§5) |
| 8 | 면제 계정의 구매 | 「실제 결제는 안태우고, 상품은 지급하지만 기록은 결제면제로.」 → 결제사 없이 `PAID` + 지급 훅 그대로, 주문은 **결제 면제**로 따로 기록 · 매출 · 건수 · 환불에서 뺀다(§6). 검사 함수 `isExempt` 도 둔다 |
| 9 | 전권도 면제인가 | 「위계 둘 수 있으면 그렇게 해도 되는데 아니면 각각 롤로. 근데 내가 알기로 자바 때 부터 위계 있던걸로 아는데」 → 역할 위계는 지금 없다(§6.1 확인 결과). 전권은 `*` 라 위계 없이도 면제를 갖는다. 일반 위계를 넣을지는 「정해야 할 것」 |
| 10 | 역할 위계를 이번에 넣나 | 「이번에 넣는다」 → **1차 포함**(§6.1 확정안, K9 · R4). 설계 전체: 「승인, 계획으로」 |
| — | 페이들 | 금액을 페이들이 계산하므로 **첫 판 쿠폰은 토스 · 카카오페이 · 네이버페이만**, 페이들은 비지원(형에게 알림) |

## 0. 목표 · 비목표 · 반목표

**목표**: 찍은 앱이 모듈 몇 줄로 「출석하면 포인트, 친구를 데려오면 둘 다 쿠폰, 쿠폰으로 결제 할인, 운영자는 이벤트를 켜고 끄고 쿠폰 · 포인트 · 무료 이용권을 준다」를 얻는다. 각 모듈은 혼자 붙여도 돈다(쿠폰만, 포인트만).

**비목표(다음 판 — 넣을 자리만 적는다)**
- 기념일 · 공유 · 달성 이벤트 → `CampaignKind` 빈 하나씩(§2). 기념일은 매일 도는 훑기 작업, 공유는 공유 증거를 받는 API, 달성은 앱이 부르는 신호 `CampaignSignals.emit(accountId, key)` 로 들어온다.
- 배지 → `badge` 모듈이 `RewardIssuer`(§2)를 구현하면 끝. 이벤트 쪽은 바뀌지 않는다.
- 포인트 결제 차감 → `point` 가 `DiscountSource`(§2)를 구현(종류 `point`, 순서 200). 결제 쪽은 이미 1:N 할인 내역 · 예약/확정/해제/복원을 갖고 있다.
- 쿠폰 여러 장 겹쳐 쓰기(상품 쿠폰 + 장바구니 쿠폰), 1회용 대량 코드, 쿠폰 · 포인트 대시보드 카드.
- 페이들 쿠폰(페이들 discount 를 따로 만들어 맞추는 이중 관리), 스트라이프 쿠폰 시험.
- 다계정 막기의 본인인증(CI) · 기기 지문, 출석 캡차.
- 알림 모듈 빈틈 고치기(§4.6 이 의존하는 것만 적는다).

**반목표(돌아가도 실패)**
- 이벤트 처리 트랜잭션이 쿠폰 · 포인트 표를 직접 만진다(허리가 안 끊긴다).
- 같은 지급 건이 재시도로 두 번 지급된다. 같은 쿠폰이 두 주문에 쓰인다(늦은 PAID 포함, §5.4).
- 브라우저가 보낸 금액 · 할인액이 결제에 쓰인다 — 금액은 여전히 서버(`PaymentCatalog` + 할인 수단)만 정한다.
- 이 모듈들을 안 붙인 앱의 결제 · 권한 동작이 바뀐다(스켈레톤 중립).

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| 이벤트 | 틀(기간 · 켜고 끄기 · 보상 목록) + 출석(매일 · 누적/연속 달성) + 친구초대(양쪽 보상) | 기념일 · 공유 · 달성 |
| 지급 | 지급 건 표 + 일꾼(재시도 · DEAD · 운영자 다시 지급) + 받았어요 알림 | 지급 회수(쿠폰 취소 · 포인트 차감)를 지급 건 단위로 |
| 쿠폰 | 조합형 정의(혜택 단계 목록 §3.3) · 발급(운영자 · 이벤트 · 공용 코드) · 쿠폰함 · 단건 결제 할인 · 0원 결제 | 겹쳐 쓰기 · 1회용 대량 코드 |
| 구독 쿠폰 | 시작 할인 + 매 갱신 할인 · n달 무료 · 영원(결정 7) | 페이들 구독 |
| 포인트 | 잔액 · 내역 · 유효기간(먼저 소멸할 것부터 차감) · 운영자 조정 · 서버용 차감 API | 결제 차감 |
| 결제 면제 | `payment.exempt` 권한 · 검사 함수 · 결제사 없이 완료되는 「결제 면제」 주문(결정 8) | 면제 계정의 구독 |
| 역할 위계 | 역할이 다른 역할을 물려받기(결정 10, §6.1) — yml · DB · 역할 화면 | 기간제 역할 · 대상별 권한 |
| 화면 | 사용자: 출석 카드 · 초대 카드 · 쿠폰함 · 포인트 · 결제의 쿠폰 고르기. 운영자: 이벤트 · 쿠폰 · 포인트 · 지급 건 | |

## 2. 모듈 · 의존 방향 · 확장 지점

```
campaign(-jdbc) ──api──▶ reward(-jdbc) ──▶ platform{common/reward: RewardIssuer}
                                                     ▲ 구현
coupon(-jdbc) ─────────────────────────────▶ platform{RewardIssuer, common/discount: DiscountSource}
point(-jdbc)  ─────────────────────────────▶ platform{RewardIssuer}            (다음 판: DiscountSource)
payment(-jdbc) ────────────────────────────▶ platform{DiscountSource, authz OperatorAccess}
reward ··compileOnly··▶ notification (board 와 같은 선택 연동, noOptionalTest 로 증명)
```

- `campaign` 은 `coupon` · `point` 를, `payment` 는 `coupon` 을, `coupon` 은 `payment` 를 모른다. 둘을 잇는 계약 타입은 `platform` 에 둔다 — `compileOnly` 로 서로 걸면 `new-project.sh` 가 상대 소스를 끌고 온다(대시보드 설계의 지휘 결정 1 과 같은 선례).
- `reward-jdbc` 는 `job-queue-jdbc` 위에 일꾼을 얹는다(`payment-jdbc` 의 `PaymentTasks` 어댑터와 같은 꼴). `reward` 는 큐를 모른다.

| 지점 | 자리 | 고른 이유 |
|---|---|---|
| `RewardIssuer`(`type` · `validate(params)` · `issue(grant): IssuedReward` · `describe(params)`) | `platform` `common/reward` | 허리(결정 1). 지금 구현 둘(쿠폰 · 포인트), 다음 판 배지 |
| `RewardGrants.record(accountId, type, params, sourceType, sourceKey)` — 호출자 트랜잭션 안에서 지급 건 + 작업을 넣는다 | `reward` | ①이 아는 유일한 것. 지급 건 기록과 enqueue 가 같은 커밋 |
| `CampaignKind`(`kind` · 설정 검증 · 보상 키 계산) | `campaign` | 지금 둘(출석 · 초대), 다음 판 셋 |
| `DiscountSource`(`kind` · `order` · `quote` · `reserve` · `confirm` · `release` · `restore`) + `DiscountStage` · `DiscountMath` | `platform` `common/discount` | 결제 쪽 할인 수단 공통 틀. 지금 쿠폰, 다음 판 포인트. 결제 면제는 할인이 아니다 — `payment` 안의 따로 된 길(§6) |
| `AccountFacts.createdAt(accountId)` | 계약 `platform`, 구현 `account` | 초대 「새 계정만」 판정을 `campaign` 이 `account` 를 모르고 한다(`RoleHolders` 선례). 없으면 판정 생략 + 모듈 문서에 적는다 |
| `RewardNotifier`(기본: `notification` 이 있으면 받은편지함으로) | `reward` | 앱이 문구 · 채널을 바꾼다 |

## 3. 데이터 (두 방언 — PG `timestamptz` · MySQL `datetime(6)` UTC, 금액 `bigint` 최소 단위 + 통화, 날짜 `date`, JSON 은 `*_json text`(notification · jobs 선례). `payment-jdbc` 는 자기 규칙대로 JSON 열 없이 자식 표)

### 3.1 `campaign-jdbc`
- `campaigns(id pk, kind varchar(32), name varchar(100), enabled boolean, starts_at, ends_at null, config_json text, created_at, updated_at)` — `config_json` = 종류별 설정 + 보상 목록 `[{type:"point", params:{amount:"100", expiresInDays:"365"}}]`. 저장 전에 `CampaignKind` · `RewardIssuer.validate` 로 검증.
- `campaign_attendance(campaign_id, account_id, day date, created_at, pk(campaign_id, account_id, day))` — 하루 한 번은 이 키가 지킨다.
- `campaign_referral_codes(account_id pk, code varchar(16) unique, created_at)` — 계정당 하나, 처음 볼 때 만든다(헷갈리는 글자 뺀 8자).
- `campaign_referrals(invitee_id pk, inviter_id, campaign_id, status varchar(16), claimed_at, qualified_at null, index(inviter_id, campaign_id))` — 초대받은 사람은 평생 한 번.

### 3.2 `reward-jdbc`
- `reward_grants(id pk, account_id, reward_type varchar(32), params_json text, source_type varchar(32), source_key varchar(200), status varchar(16), result_ref varchar(64) null, last_error varchar(200) null, created_at, issued_at null, unique(source_type, source_key), index(status, created_at), index(account_id, created_at))`.
- `source_key` 예: 출석 `<campaign>:<account>:<day>:daily:0`, 달성 `<campaign>:<account>:milestone:7:0`, 초대 `<campaign>:<invitee>:inviter:0`. 같은 조건이 두 번 판정돼도 유니크가 한 건으로 접는다.
- 상태: `PENDING → ISSUED`(끝) · `PENDING → FAILED`(작업 DEAD, 운영자 「다시 지급」으로 `PENDING`) · `CANCELED`(운영자, 지급 전만).

### 3.3 `coupon-jdbc` — 조합형 정의 (국룰: Stripe coupon 의 `duration once / repeating N / forever` × `percent_off / amount_off` + Paddle discount 의 `recur · maximum_recurring_intervals`, 그리고 무료 체험을 「100% 단계」로)
- `coupon_definitions(id pk, name, status ACTIVE|PAUSED|ARCHIVED, stages_json text, currency null, min_amount null, scope_json text, first_purchase_only boolean, valid_days null, valid_until null, issue_limit null, issued_count, per_account_limit default 1, created_at, updated_at)`.
- **혜택 단계 목록** `stages_json`: 순서대로 이어지는 단계들. 단계 = `{type: FREE|PERCENT|FIXED, value, maxDiscount?, periods: N | null(영원)}`. 단건 결제는 첫 단계만 본다. 예:
  - 1,000원 할인: `[{FIXED,1000,periods:1}]` · 30%(최대 5,000): `[{PERCENT,30,max:5000,periods:1}]` · 무료 이용권: `[{FREE,periods:1}]`
  - 3달 무료: `[{FREE,periods:3}]` · 첫 결제 무료 + 이후 3달 50%: `[{FREE,1},{PERCENT,50,3}]` · 평생 10%: `[{PERCENT,10,null}]`
  - 단계는 5개까지, `null` 은 마지막 단계만. 마케팅이 새 조합을 원하면 행 하나 — 코드 수정 없음.
- `scope_json`: `{purposes:[ONE_TIME, SUBSCRIPTION], itemTypes:[], itemRefs:[], planCodes:[]}` — 빈 목록 = 전부.
- `coupons(id pk, definition_id, account_id, status AVAILABLE|RESERVED|USED|EXPIRED|REVOKED, holder_ref null(주문 id), source_type GRANT|CODE|ADMIN, source_ref, expires_at null, used_at null, created_at, updated_at, unique(source_type, source_ref), index(account_id, status))` — 지급 건 id 가 `source_ref` 라 일꾼 재시도에도 한 장.
- `coupon_codes(code pk(대문자 정규화), definition_id, max_redemptions null, redeemed_count, expires_at null, created_at)` + `coupon_code_redemptions(code, account_id, pk(code, account_id))` — 공용 코드 하나를 여러 사람이 한 번씩.
- 발급 한도 · 계정당 한도는 정의 줄을 잠그고 센다.

### 3.4 `point-jdbc`
- `point_balances(account_id pk, balance bigint, updated_at)`.
- `point_entries(id pk, account_id, delta bigint, kind EARN|SPEND|EXPIRE|ADJUST, remaining bigint(적립 묶음의 남은 양), expires_at null, source_type, source_ref, reason varchar(200) null, created_at, unique(source_type, source_ref), index(account_id, created_at), index(expires_at))`.
- 국룰: 차감은 **먼저 소멸할 적립부터**(유효기간 없는 것은 맨 뒤), 잔액보다 큰 차감은 거절(운영자 조정 포함), 소멸은 하루 한 번 작업이 `EXPIRE` 줄로 남긴다. 유효기간 기본은 없음 — 보상 설정 · 운영자 조정에서 고른다(중립).

### 3.5 `payment-jdbc` (새 V 파일 — 잠긴 파일은 고치지 않는다)
- `payment_orders` 에 `original_amount bigint null`(할인 없으면 null) · `discount_amount bigint not null default 0`. `amount` 는 여전히 **최종 금액** — 승인 대조 · 환불 상한(`paidAmount ?: amount`) · 중도 해지 환불(`payment.net`)이 그대로 맞는다.
- `payment_order_discounts(order_id, seq, kind varchar(16), source_ref varchar(64), label varchar(100), amount bigint, currency, status RESERVED|CONFIRMED|RELEASED|RESTORED, created_at, updated_at, pk(order_id, seq), index(kind, source_ref))`.
- `payment_subscription_discounts(subscription_id pk, kind, source_ref, label, periods_used int, status ACTIVE|ENDED, created_at, updated_at)` + `payment_subscription_discount_stages(subscription_id, seq, type, value bigint, max_discount null, periods null, pk(subscription_id, seq))` — 시작 때 단계를 **복사**한다(Stripe 관행: 쿠폰 정의를 고쳐도 진행 중인 구독은 그대로).

## 4. 핵심 흐름

### 4.1 출석 체크 → 지급 건 → 일꾼 → 포인트 → 알림
1. `POST /api/v1/campaigns/{id}/attendance` → 켜져 있고 기간 안인지 확인 → 오늘 날짜를 **서비스 시간대**로 계산(국룰: 서비스 기준 자정 리셋, 보는 사람 시간대가 아님 — 시간대를 옮겨 두 번 찍기 방지). 시간대: `skeleton.campaign.zone` → `skeleton.time.default-zone` → `Asia/Seoul`(`auth-session` · `dashboard` 와 같은 사다리, 프로젝트 모드면 프로젝트마다).
2. 한 트랜잭션: 출석 줄 insert-ignore(이미 있으면 「오늘 이미 출석」 200, 지급 없음) → 누적 · 연속 일수 계산 → 매일 보상 + 이번에 닿은 달성(예: 누적 7일 · 연속 7일) 보상마다 `RewardGrants.record` → 커밋. **여기서 끝.**
3. 일꾼(`reward.issue` 작업, 지급 건 하나): 한 트랜잭션에서 지급 건을 잠그고 `PENDING` 이 아니면 끝 → `RewardIssuer(type).issue(grant)`(포인트: `(GRANT, grantId)` 유니크로 한 번) → `ISSUED` + `result_ref`. 같은 DB 라는 전제(결제 리스너와 같은 조건)를 모듈 문서에 적는다.
4. 던지면 큐가 백오프 재시도, 다 쓰면 DEAD → 지급 건 `FAILED`(큐의 `JobDeadListener`) → 운영자 화면에서 「다시 지급」. 지급기가 영구 실패(정의 보관됨 등)를 알리면 `PermanentJobFailureException`.

### 4.2 친구초대 (양쪽 보상)
1. 초대자: `GET /api/v1/referrals/me` → 코드 · 링크 · 초대 수. 링크 `/?ref=CODE`.
2. 새 사람: 화면이 `ref` 를 30일 보관(국룰 귀속 기간) → 가입 · 로그인 직후 `POST /api/v1/referrals/claim {code}` 한 번 → 지우고 끝.
3. 판정(한 트랜잭션): 진행 중인 초대 이벤트가 있어야 · 자기 코드 아님 · 이미 초대받은 적 없음(`invitee_id` pk) · **새 계정**(가입 후 `config.claimWithinDays`, 기본 7일 — `AccountFacts`) · 맞초대 아님(초대자가 이 사람의 초대로 들어온 사람이면 거절) · 초대자 보상 상한(`config.inviterCap`, 없으면 무제한). 통과 → 관계 줄 + **초대받은 사람 보상** 지급 건.
4. 초대자 보상 시점은 이벤트 설정 `qualifyOn`: `CLAIM`(바로) 또는 `SIGNAL`(앱이 `Referrals.qualify(inviteeId)` 를 부를 때 — 예: 첫 결제 리스너에서). 국룰은 「초대받은 사람이 의미 있는 행동을 했을 때」라 견본 앱은 `SIGNAL` + 첫 결제(`PaymentOrderListener.onPaid`)로 보여 준다. 스켈레톤 기본값은 정하지 않는다(설정 필수).

### 4.3 쿠폰 받기
- 이벤트 · 운영자 발급: 지급 건 → 쿠폰 `issue` → 쿠폰함에 한 장(만료 = `valid_until` 과 발급 + `valid_days` 중 이른 것).
- 공용 코드: `POST /api/v1/coupons/redeem {code}` → 같은 코드 계정당 한 번, 한도 · 만료 확인 → 쿠폰함으로(국룰: 코드는 「등록」, 결제에서는 쿠폰함에서 고른다).

### 4.4 쿠폰 + 단건 결제
- 미리보기: `POST /api/v1/payments/quote {itemType, itemRef, quantity, discounts:[{type:"coupon", ref}]}` → `{originalAmount, discounts:[{type, label, amount}], amount, currency}`. 부수 효과 없음. 쿠폰함 화면은 각 쿠폰의 적용 가능 여부를 이걸로 본다.
- 주문 만들기: 요청 본문에 같은 `discounts`(첫 판은 쿠폰 1장). 한 트랜잭션: `catalog.quote` → 수단을 `order` 순(쿠폰 100 → 포인트 200, 국룰: 쿠폰 먼저 포인트 나중)으로 `reserve(holderRef = 주문 id)` → 최종가 = 원가 − 할인(0 미만 없음) → 주문 줄(`amount` = 최종가) + 할인 내역 줄 → 커밋. 같은 `Idempotency-Key` 재전송은 처음 줄을 돌려준다.
- 할인 계산(`DiscountMath`, 국룰): 정률은 할인액의 최소 단위 미만 버림 + `maxDiscount` 상한, 정액은 통화가 같아야 적용, `min_amount` 미달 · 범위 밖 · `first_purchase_only` 인데 결제한 적 있음 → `PAYMENT.DISCOUNT_NOT_APPLICABLE`. 쿠폰이 이미 예약 · 사용 · 만료 → `PAYMENT.DISCOUNT_UNAVAILABLE`.
- 쿠폰은 한 장 = 한 개(국룰): `quantity > 1` 이어도 할인은 단가 한 개분에만 걸린다 - 무료 · 100% 쿠폰 한 장으로 수량 1000 개가 0원이 되지 않는다.
- 0 < 최종가 < 결제사 최소 금액 → `PAYMENT.DISCOUNT_BELOW_MINIMUM`(결제사 모듈이 최소 금액을 알려 준다 — 값은 구현 첫 작업으로 각 결제사 문서에서 확인).
- 결제사: 토스 · 카카오페이 · 네이버페이는 줄의 금액을 그대로 넘기므로 손댈 곳이 없다. 페이들 · 스트라이프 경로로 가는 주문에 할인이 있으면 `PAYMENT.DISCOUNT_NOT_SUPPORTED`(페이들은 웹훅 금액으로 `amount` 를 덮어쓰기 때문).

### 4.5 예약 · 확정 · 해제 · 늦은 PAID · 환불 복원 · 0원
| 때 | 자리(이미 있는 한 곳) | 할인 수단 |
|---|---|---|
| 주문 만들기 | `PaymentOrders.create` 트랜잭션 | `reserve` — 쿠폰 `AVAILABLE → RESERVED(holder=주문)` |
| PAID 후 지급 | `PaymentFulfillment.fulfil`(주문 락 안, 한 번) — 앱 리스너보다 먼저 | `confirm` — `RESERVED → USED`. 리스너가 던지면 같이 되돌려지고 재시도 |
| FAILED · EXPIRED · CANCELED | 상태를 바꾸는 곳은 모두 `PaymentLedgerWriter.transition` — 끝 상태로 갈 때 같은 트랜잭션에서 | `release` — 이 주문이 쥔 쿠폰만 `AVAILABLE`(만료 지났으면 `EXPIRED`). **새 공용 훅** |
| 늦은 PAID(FAILED · EXPIRED · CANCELED → PAID, 결제사 증거) | 같은 `fulfil` 의 `confirm` | 쿠폰이 아직 `AVAILABLE` 이면 다시 잡아 `USED`. 그사이 다른 주문이 쥐었으면 둘 다 그대로 두고 이 주문에 검토 표시 `DISCOUNT_CONFLICT`(운영자 판단 — 손님은 이미 할인가로 냈다) |
| 전액 환불(`REFUNDED`) | `RefundLines.landed` 의 `REFUNDED` 전이 · 지급 전 전액 환불 | `restore` — 쥔 쿠폰을 `AVAILABLE`(만료 지났으면 `EXPIRED`). 국룰: **부분 환불은 복원 없음**, 할인액 안분 없음(환불 상한이 이미 최종가) |
| 차지백 되돌림(`REVERSED`) | 기존 「지급은 되살리지 않는다」 | 쿠폰도 되살리지 않는다 |

**0원 결제** — 최종가가 0(100% 쿠폰): 결제사를 부르지 않는다(토스는 0원 승인 불가, 국룰: Stripe 도 0원 청구서는 자동 결제됨). 한 트랜잭션에서 주문을 `PENDING → PAID`(전이표에 이미 있는 칸), `provider = "free"`(라우터가 고르지 않는 예약 id, 모드는 기본 결제사의 모드 — 내 판단), 만료 작업 없음, 지급 작업 enqueue → 응답 `status: PAID` · `checkout: null`. 앱 리스너는 평소처럼 `onPaid` 를 받는다 — 앱의 지급 · 「구매했나」 기록이 그대로 돈다. 0원 주문은 환불 대상이 아니다(`PAYMENT.NOTHING_TO_REFUND`, 운영 화면은 단추를 숨긴다). 쿠폰 0원 주문은 「구매가 일어났다」라 결제 건수에는 들어가고 매출은 0 이다(내 판단 — 쇼핑몰 주문 수 관행). 결제 면제 주문은 다른 길이다(§6).

### 4.6 받았어요 알림
- 일꾼이 `ISSUED` 를 커밋한 **뒤** `RewardNotifier` 를 부른다. 기본 구현: `NotificationPublisher.publish(topic "rewards", type "reward.granted", id = "rwd_" + 지급 건 id, recipient = 계정, title/message = 지급기의 `describe` 문구, payload {rewardType, label, link})`. 실패는 로그만(보상은 이미 쿠폰함 · 포인트 내역에 있다).
- 알림 모듈 빈틈 중 이번이 기대는 것(고치는 것은 범위 밖):
  - ⑦ 브로커가 커밋 뒤에 전달하지 않고 받은편지함 저장 실패가 호출자로 던져진다 → 그래서 우리가 커밋 뒤에 직접 부르고 잡는다. id 를 지급 건에서 만들어 받은편지함의 `(recipient_id, event_id)` insert-ignore 가 중복을 접는다.
  - ⑤ 받는 쪽 번역 렌더가 없다 → 첫 판 문구는 앱 기본 언어로 서버가 만든다. 렌더 자리가 생기면 `type` + `payload` 로 바꾼다.
  - ⑥ 클릭 이동이 없다 → `payload.link`(`/rewards/coupons` 등)는 실어 두지만 지금은 눌리지 않는다.

## 5. 구독 쿠폰 (1차 포함 — 결정 7)

- **시작**: `POST /api/v1/payments/subscriptions` 에 `discounts`. 쿠폰 범위에 `SUBSCRIPTION` · 그 플랜이 있어야 한다. 쿠폰은 첫 주문(`PaymentOrderIds.initialOrderId`, 미리 정해진 id)에 `reserve` → 단계를 `payment_subscription_discount_*` 로 복사.
- **첫 주문 금액** = 단계 적용가. `prepareBilling` 의 `BillingPrepareRequest.amount` 와 카카오페이 `RegisterWithChargeRequest.amount` 도 **이 금액**(지금은 `plan.amount` / `order.amount`) — 카카오페이는 1회차 결제로 빌링키(SID)를 받으므로 결제창 금액이 곧 첫 결제액이다.
- **첫 기간 무료(0원)**: 카드는 그래도 등록받는다(국룰: 무료 체험도 결제 수단 등록). 토스 · 네이버페이(등록이 따로)는 빌링키 발급 뒤 결제사 승인 대신 0원 주문을 내부에서 `PAID` 로 → `onOrderPaid` 가 평소처럼 기간을 연다. 카카오페이(`registrationCharges`)는 **0원 1회차로 SID 를 받는다**(K1 확인: 공식 문서 「상품 총액(total_amount) 0원으로 단건 결제를 처리하면 승인은 발생하지 않고, SID만 발급 받을 수 있습니다」 · 「프로모션 등으로 인한 결제금액이 없이 SID만 발급할 경우 1회차 금액을 0원으로」 — https://developers.kakaopay.com/docs/payment/online/subscription). 그래서 무료 첫 단계도 카카오페이 구독에서 받는다: 1회차 `ready` 를 0원으로 보내고, `approve` 는 결제 없이 `sid` 만 돌려준다. 가짜 서버 `FakeKakaoPayServer` 의 `total_amount <= 0` 거절은 정기 cid 에서 0원을 받도록 K7 이 고친다.
- **갱신 금액**: `SubscriptionRenewTaskHandler` 가 `lifecycle.chargeOrder(…)` 를 부를 때 `amount = SubscriptionDiscounts.amountFor(subscription)`(기본 `subscription.amount`). 단계 위치 = `periods_used`(이번 과금이 몇 번째 할인 회차인가). `periods_used` 는 **할인이 붙은 구독 주문이 PAID 될 때만** `onOrderPaid` 안(구독 락)에서 1 오른다 → 같은 기간의 재시도는 같은 금액, 실패한 시도는 회차를 쓰지 않는다. 단계를 다 쓰면 `ENDED`, 이후 정가.
- **갱신이 0원**(n달 무료 중간): 결제사를 부르지 않고 0원 주문을 `PAID` 로 — 기간은 같은 길로 넘어간다. 빌링키가 없는 구독(토큰 유실 표시)도 무료 기간은 넘어간다.
- **플랜 변경**: 남은 단계는 새 플랜 금액에 이어서 적용(국룰: Stripe 는 할인이 구독에 붙어 플랜이 바뀌어도 남는다). 정액은 새 금액을 넘지 않게 자른다. 변경 차액 결제(`SUBSCRIPTION_CHANGE`)에는 할인을 붙이지 않고 회차도 쓰지 않는다(내 판단 — 차액은 일할 계산이라 정률을 또 깎으면 두 번 깎인다). `FULL_RESTART` 로 `periodSeq` 가 1 로 돌아가도 `periods_used` 는 따로라 어긋나지 않는다.
- **해지**: 해지 예약(`CANCELING`) 중엔 남은 단계 보존, 구독이 `ENDED` 가 되면 할인도 `ENDED`. 다시 구독하면 새 줄 — 쿠폰은 되살아나지 않는다.
- **환불**: 갱신 기간 환불 · 중도 해지 환불은 그 주문의 실제 결제액(`payment.net`) 기준이라 이미 맞다. 소진한 회차는 돌려주지 않는다(Stripe 관행). **첫 주문 전액 환불**(7일 철회 등)이면 단건과 같은 규칙으로 쿠폰 `restore`.
- **페이들 구독**(`isProviderManaged`): 쿠폰 거절 `DISCOUNT_NOT_SUPPORTED`.

## 6. 결제 면제

- 현황: 결제 모듈에 「구매했나」 검사는 **없다**. 구매 기록은 앱의 몫(견본 앱은 `CheerOrderListener` 가 응원 줄을 남길 뿐)이고, 구독은 `PaymentSubscriptions.current(accountId)?.status?.isInUse` 를 앱이 본다.
- 새로 둔다:
  - 권한 `payment.exempt` — `PaymentPermissions` 선언에 더한다(기본 역할 없음). 운영자는 기존 역할 화면에서 「무료 이용 계정」 같은 역할을 만들어 이 권한을 주고 계정에 배정한다 — 판정은 `OperatorAccess`(계정 모듈이 있으면 매 요청 저장소로, 회수 즉시).
  - `PaymentExemption.isExempt(accountId | authentication)` — 앱의 「구매했나 / 구독 중인가」 검사에 `|| exemption.isExempt(id)` 한 줄. 모듈 문서 · 견본 앱에 이 꼴을 보인다.
  - **결제 면제 주문**(결정 8): 면제 계정의 단건 주문은 결제사를 부르지 않고 바로 완료하되, 0원 쿠폰 주문과 **다른 기록**으로 남긴다.
    - 국룰: 결제사 밖에서 정산된 거래를 「장외 결제」로 표시한다(Stripe 청구서의 `paid_out_of_band`). 우리 장부는 「누가 돈을 받았나」를 이미 `provider` 칸에 두므로 **예약 결제사 id `exempt`** 로 적는다(쿠폰 0원은 `free`). 새 칸이 아니라 기존 칸이라 라우터 · 조회 작업 · 웹훅 · 결제사와 맞추기가 이 줄을 건드리지 않는 효과도 그대로 얻는다.
    - 금액: `original_amount` = 카탈로그 가격(얼마어치를 면제했나), `amount` = 0, `discount_amount` = 0, 할인 내역 줄 없음(할인이 아니다).
    - 이력: `ORDER_CREATED` 다음 새 이력 `EXEMPTED`(행위자 = 그 계정, 근거 = 권한 `payment.exempt`) → `PENDING → PAID` → 지급 작업. **지급 훅(`onPaid`)은 평소처럼 돈다** — 상품이 나간다. 앱이 면제 주문을 가르고 싶으면 `order.provider == "exempt"`(상수 `PaymentOrders.EXEMPT_PROVIDER`).
    - 빠지는 곳: 대시보드 순매출 · 결제 건수 · 퍼널 「결제」 단계(`payment-jdbc` 기여 질의에 `provider <> 'exempt'`), 환불(사용자 요청 · 운영자 환불 모두 `PAYMENT.NOTHING_TO_REFUND`, 화면은 단추 숨김), 구독 쿠폰 「첫 결제만」 판정(`first_purchase_only`)의 「결제한 적 있음」.
    - 보이는 곳: 사용자 주문 목록 · 운영 주문 목록 · 상세에 「결제 면제」 배지(react 라벨 `exempt` / `free` → 「결제 면제」 / 「무료(쿠폰)」), 운영 주문 목록 필터에 「결제 면제」.
    - 면제 + 쿠폰이면 쿠폰은 예약하지 않는다(면제가 이긴다). 같은 `Idempotency-Key` 재전송 · 주문 속도 제한은 그대로.

### 6.1 역할 위계 (결정 9 · 10 — 1차 포함)

**확인 결과(2026-10-28 코드)**: 위계는 없다. Spring Security `RoleHierarchy` 를 쓰는 곳도 없다(`modules/*/src/main` 검색 0건). 2026-10-24 권한 설계가 「역할 상속」을 비목표 · 다음에 넓힐 것으로 뺐다(그 문서 20행 · 31행). 판정은 역할 → 권한 평면 매핑이고, 전권만 와일드카드다 — `ALL_PERMISSIONS = "*"`(`platform` `common/authz/OperatorAccess.kt:7`), `has()` 는 `*` 면 참(같은 파일 24행), `*` 는 전권 역할에만(`AuthzAutoConfiguration.kt:37` · 다른 역할은 `authz` `RoleService.kt:125` 가 막는다). 그래서 **ADMIN 은 위계와 무관하게 `payment.exempt` 를 갖는다.** 위계는 그 밖의 묶음(`STAFF` 가 `CS` · `EXEMPT` 를 물려받기)에 쓴다.

**확정안** — 국룰: Spring Security `RoleHierarchy` 의 뜻(`ROLE_STAFF > ROLE_CS` = 위가 아래의 권한을 모두 물려받는다, 전이적, 순환 금지). 저장 · 편집은 기존 `authz-jdbc` 역할 표와 역할 화면에 얹는다.
- **모양**: 역할마다 「물려받는 역할」 `includes`(여러 개 — Spring 식 표현으로 여러 `>` 줄). 전권 역할은 그대로 `*` 이고 `includes` 를 갖지 않으며, 다른 역할이 전권 역할을 물려받을 수 없다(`*` 를 간접으로 주는 길).
- **판정 한 곳**: `RolePermissionSource.permissionsOf(role)` 의 뜻을 「**물려받은 것까지 펼친** 권한」으로 바꾸고 `includesOf(role): Set<String>`(기본 빈 집합)를 더한다. 펼침은 `platform` `RoleClosure`(방문 집합 + 깊이 상한 8). 그래서 `TokenOperatorAccess` · `AccountOperatorAccess` · 계정 모듈의 상승 막기(`AccountCore.requireCanManage` 와 역할 부여) · `RoleService` 의 상승 · 자기 잠금 판정이 고치지 않고 위계를 따른다. `rolesOf` · `/auth/me` 의 `roles` 는 **배정된 역할 그대로**, `permissions` 만 펼친 결과.
- **yml(`authz` 없음)**: `skeleton.authz.role-includes: { STAFF: [CS, EXEMPT] }`. 순환 · 없는 역할 · 전권 포함 · 전권에 `includes` → 기동 실패(지금 `skeleton.authz.roles` 를 검사하는 같은 자리, `AuthzAutoConfiguration` 의 `problems`).
- **표(`authz-jdbc` 새 V 파일, 두 방언)**: `authz_role_includes(role varchar(40) not null references authz_roles(name) on delete cascade, included varchar(40) not null references authz_roles(name), primary key(role, included))`. 첫 기동 씨앗은 yml `role-includes` 를 기존 씨앗과 같은 「처음 한 번 · insert-ignore」로.
- **API(`authz`)**: 역할 응답에 `includes: string[]` · `effectivePermissions: string[]`(화면 표시용). `POST` · `PUT /api/v1/admin/roles…` 본문에 `includes` — `PUT` 에서 빠지면 **그대로 둔다**(내 판단: 옛 화면이 위계를 모르고 지우지 않게).
- **안전장치(`AUTHZ.*`, 프런트 `errorCodes.ts` 에도)**: 순환 → 409 `ROLE_CYCLE` · 없는 역할 → 400 `INCLUDE_UNKNOWN` · 전권 관련 → 409 `ROLE_PROTECTED` · **상승**: 더하거나 빼는 `includes` 의 펼친 권한이 호출자 권한의 부분집합이 아니면 403 `ESCALATION`(기존 규칙을 펼친 권한으로) · **자기 잠금**: 변경 뒤 호출자의 펼친 권한에서 `authz.roles` 가 빠지면 409 `SELF_LOCKOUT` · **지우기**: 다른 역할이 물려받는 역할은 409 `ROLE_INCLUDED`(그쪽에서 먼저 뺀다), 기존 `ROLE_IN_USE` · 배정 거두기는 그대로.
- **동시성 · 여러 인스턴스**: `includes` 를 바꾸는 편집은 전권 역할 줄을 `select … for update` 로 잠근다(늘 있고 고정인 줄 하나 = 전역 잠금, 역할 편집은 드물다) — 다른 인스턴스에서 A→B · B→A 를 동시에 넣어 순환이 생기지 않게. 메모리 저장소는 기존 `RoleService` 프로세스 잠금.
- **캐시**: `CachingRolePermissionSource` 는 펼친 권한을 역할별로 담고, 어느 역할이든 바뀌면 같은 인스턴스는 `invalidate` 로 **전체**를 비운다(아래 역할이 바뀌면 위 역할의 펼친 값도 바뀐다). 다른 인스턴스는 지금처럼 `skeleton.authz.cache-ttl`(기본 30초) 안에 반영 — 2026-10-24 와 같은 약속, 계정 배정 · 회수는 그대로 다음 요청부터.
- **운영 기록**: `authz.role.created` detail 에 `includes`, `updated` 에 `includesAdded` · `includesRemoved`(기존 `added` · `removed` 옆). 실패한 변경은 부르지 않는다.
- **화면(react `@skeleton/auth/admin` `AdminRoles`)**: 「물려받는 역할」 다중 선택(자기 · 전권 · 고르면 순환이 되는 후보는 비활성 + 이유), 권한 체크 묶음에서 물려받은 권한은 체크 · 잠금 + 「STAFF 에서 물려받음」, 목록 행에 물려받는 역할 칩, 오류 코드 셋 안내(`labels`).
- **공수**: kotlin 중 1(K9) + react 소 1(R4). 이벤트 · 보상과 독립이라 나란히 돈다.

## 7. 남용 방지 (국룰)

| 위험 | 막는 것 |
|---|---|
| 출석 두 번 · 동시 클릭 | `(campaign, account, day)` pk, 날짜는 서버 시간대 · `TimeProvider` 시각 |
| 시간대 옮겨 다시 찍기 | 보는 사람 시간대를 쓰지 않는다 |
| 자기 초대 · 맞초대 · 옛 계정 끌어오기 | 코드 주인 = 본인 거절 · 맞초대 거절 · 가입 후 N일 안만 |
| 다계정 농사 | 초대받은 사람은 평생 한 번 · 새 계정은 이메일 확인을 거쳐야 생긴다(`account` 의 가입 흐름) · 초대자 상한 · 초대자 보상을 `SIGNAL`(첫 결제)로. 본인인증 · 기기 지문은 다음 판 |
| 쿠폰 두 번 쓰기 | 쿠폰 줄 상태 전이 + `holder_ref`, 주문 락 안 확정, 늦은 PAID 충돌 표시 |
| 0원 주문 반복 | 기존 주문 속도 제한(`http.order-rate-limit`) 그대로, 0원도 센다 |
| 일꾼 재시도 중복 | 지급기마다 `(GRANT, 지급 건 id)` 유니크 |
| 탈퇴 | 네 모듈 모두 `AccountErasureListener`(지급 건 · 쿠폰 · 포인트 · 출석 · 초대 관계 삭제, 결제 장부의 할인 내역은 장부라 남긴다) |

## 8. 권한 · 운영 API (모두 `skeleton.<module>.admin.enabled` 기본 꺼짐 → 404, 견본 앱 yml 에서 켬)

| 모듈 | 권한 | 경로 |
|---|---|---|
| campaign | `campaign.manage` | `GET/POST /api/v1/admin/campaigns`, `PUT /{id}`, `PATCH /{id}/enabled` |
| coupon | `coupon.manage` | `GET/POST /api/v1/admin/coupon-definitions`, `PATCH /{id}/status`, `POST /{id}/issue {accountIds}`, `POST /{id}/codes`, `GET /api/v1/admin/coupons?accountId`, `POST /api/v1/admin/coupons/{id}/revoke`(AVAILABLE 만) |
| point | `point.manage` | `GET /api/v1/admin/points/{accountId}`(잔액 · 내역), `POST /api/v1/admin/points/{accountId}/adjustments {delta, reason, expiresInDays?}` |
| reward | `reward.operate` | `GET /api/v1/admin/reward-grants?status`, `POST /{id}/retry`, `POST /{id}/cancel` |
| payment | `payment.exempt` | 경로 없음(주문 · 검사 함수에서 판정) |

- 모두 `PermissionDeclaration` 으로 선언, 기본 역할 없음(전권만) — 결제 선례. 운영 기록은 `OperatorAuditLog.record` 를 부른다(`coupon.issued` · `point.adjusted` · `campaign.updated` · `reward.retried`, 대상은 id 만) — 기본은 아무것도 안 하는 계약 자리 그대로.
- 사용자 경로는 모듈을 붙이면 켜진다(board · notification 선례): `GET /api/v1/campaigns`(진행 중 + 내 상태) · `POST /{id}/attendance` · `GET /{id}/attendance?month` · `GET /api/v1/referrals/me` · `POST /api/v1/referrals/claim` · `GET /api/v1/coupons?status` · `POST /api/v1/coupons/redeem` · `GET /api/v1/points` · `GET /api/v1/points/history`(커서). 결제 쪽 `POST /api/v1/payments/quote` 는 `skeleton.payment.http.enabled` 를 따른다. 포인트 **사용**은 HTTP 없이 서버 API `PointWallet.spend(accountId, amount, sourceType, sourceRef)` 만(내부 서비스용).

## 9. 화면 (react)

- 새 `@skeleton/rewards`(백엔드 `campaign` · `reward` · `coupon` · `point` 를 한 패키지로, 하위 경로로 나눈다 — 쿠폰만 쓰는 앱도 `@skeleton/rewards/coupon` 만 가져간다. 내 판단: 패키지 넷은 카탈로그 비용만 크다):
  - `/campaign`: `AttendanceCard`(오늘 출석 단추 · 달력 · 누적/연속 · 다음 달성까지), `InviteCard`(코드 · 링크 복사 · 초대 수), `useReferralCapture()`(URL `ref` 보관 → 로그인 뒤 claim 한 번).
  - `/coupon`: `CouponWallet`(쓸 수 있음 · 지난 것 탭, 단계 요약 문구 「첫 달 무료, 이후 3개월 50%」), `RedeemCodeForm`, `CouponPicker`(결제 화면용 — 고르면 미리보기 호출).
  - `/point`: `PointBalance`(잔액 · 30일 안 소멸 예정), `PointHistory`.
  - `/admin`: `AdminCampaigns`(목록 · 켜고 끄기 스위치 · 만들기 폼 — 종류별 설정 · 보상 목록 편집), `AdminCouponDefinitions`(단계 편집기: 단계 더하기 · 종류 · 값 · 회차/영원, 「무료 이용권」 · 「n달 무료」 틀 단추, 발급 · 공용 코드), `AdminPoints`(계정 찾기 → 잔액 · 내역 · 조정), `AdminRewardGrants`(실패 목록 · 다시 지급). 단추는 `can(key)` prop 으로 숨긴다(결제 운영 화면 선례 — 패키지가 `@skeleton/auth` 를 import 하지 않는다).
- `@skeleton/payment`: `CreateOrderInput.discounts`, `createPaymentApi().quote`, `OrderSummary` · `PaymentResult` · `OrderList` 에 원가 · 할인 줄 · 최종가, 0원 주문은 결제창 없이 결과로, 구독 화면(`MySubscriptionCard`)에 「다음 결제 n원(할인 m회 남음)」. `@skeleton/rewards` 를 import 하지 않는다 — 앱이 `CouponPicker` 결과를 넘긴다.
- 문구는 ko/en 라벨 사전. 눌리는 것은 눌리게(테두리 · 채움). 스토리 + `play` 는 부품마다, 화면은 Patterns(목록 · 폼 · 설정)에서 복사.

## 10. 견본 앱에서 눌러 볼 흐름 (kotlin `apps/sample` + react `apps/sample`)

조립: 네 모듈 쌍 + `job-queue-jdbc`(이미 있음). 씨앗(로컬 프로필만): 출석 이벤트(매일 10포인트, 누적 3일 쿠폰 「1,000원 할인」), 초대 이벤트(`qualifyOn: SIGNAL`, 초대받은 사람 「응원 30% 할인」, 초대자 500포인트 — 견본 앱의 응원 결제 리스너가 첫 결제 때 `qualify`), 쿠폰 정의 「무료 이용권」 · 「첫 달 무료 + 3달 50%」, 공용 코드 `WELCOME`.
1. user 로그인 → `/rewards` 출석 → 몇 초 안에 알림 종 「10포인트 받았어요」, 잔액 10.
2. 응원하기에서 쿠폰 고르기 → 미리보기 할인 → 토스 테스트 결제 → 결과에 원가 · 할인 · 최종가, 쿠폰함에서 「사용함」. 결제창을 닫아 만료되면 쿠폰이 돌아온다. 운영자가 전액 환불하면 쿠폰이 돌아온다.
3. 「무료 이용권」으로 응원 → 결제창 없이 바로 완료.
4. 초대 링크로 새 계정 가입 → 초대받은 쿠폰 도착 → 그 계정의 첫 응원 결제 → 초대자에게 500포인트.
5. `PT3M` 견본 플랜을 「첫 달 무료 + 3달 50%」로 구독 → 첫 0원 → 갱신마다 반값 → 다섯 번째 결제부터 정가.
6. admin: 출석 이벤트 끄기 → 사용자 화면에서 출석 카드 사라짐. 포인트 −5 조정. 역할 화면에서 `payment.exempt` 역할을 user 에 주기 → 응원이 결제창 없이 완료 · 응원 기록 생김 · 주문 목록에 「결제 면제」 · 대시보드 매출 · 건수 그대로, 회수 → 다음 주문은 결제창.

## 11. 설정 (`docs/config/modules/<module>.yml`, 앱 yml 은 다른 값만)

- `skeleton.campaign`: `zone`(비면 앱 시간대), `admin.enabled`(false), `http.enabled`(true).
- `skeleton.reward`: `admin.enabled`(false), `worker.max-attempts`(큐 기본 따름), `notify.enabled`(true — `notification` 이 있을 때만 의미).
- `skeleton.coupon` · `skeleton.point`: `admin.enabled`(false), `http.enabled`(true). 포인트 소멸 작업 시각은 `scheduler` 관례.
- 결제: 새 키 없음(할인 수단 빈이 있으면 `discounts` 를 받고, 없으면 `discounts` 가 오면 `DISCOUNT_NOT_APPLICABLE`).

## 12. 검증 (깨지면 사고 나는 것만)

**kotlin**
- 허리: 출석 트랜잭션이 쿠폰 · 포인트 표를 만지지 않는다(이벤트 처리 시험은 지급기 빈 없이 돈다). 지급기가 두 번 던진 뒤 성공 → 포인트 한 번. 같은 지급 건 작업 두 개를 동시에 → 한 번. DEAD → `FAILED` → 다시 지급 → 한 번.
- 출석: 23:59 / 00:00 서비스 시간대 경계, 동시 두 요청 → 한 줄, 누적 · 연속 달성 보상이 한 번씩.
- 초대: 자기 코드 · 맞초대 · 두 번째 claim · 가입 8일 지난 계정 거절, 상한, `SIGNAL` 전에는 초대자 지급 건 없음.
- 쿠폰 · 결제: 예약 → PAID → USED / 만료 → AVAILABLE / 만료된 뒤 늦은 PAID(쿠폰 그대로면 USED, 다른 주문이 쥐었으면 `DISCOUNT_CONFLICT`) / 전액 환불 → AVAILABLE, 부분 환불 → 그대로 / 지급 전 전액 환불 → AVAILABLE / 0원 주문 → 결제사 호출 0회, `onPaid` 한 번, 환불 거절 / 페이들 경로 거절 / 승인 금액 대조 · 환불 상한이 최종가.
- `DiscountMath`: 정률 버림 · 상한 · 정액 통화 불일치 · 0 미만 없음 · 최소 금액.
- 구독: 「1 무료 + 3 × 50%」 의 1~5회차 금액, 실패한 갱신 재시도가 회차를 안 씀, 플랜 변경 뒤 남은 회차가 새 금액에, 해지 · 첫 주문 철회 복원, 카카오페이 0원 확인 결과대로.
- 면제: 권한 있음 → 결제사 호출 0회 · `provider = exempt` · `EXEMPTED` 이력 · `onPaid` 한 번 · 환불 거절 · 대시보드 매출/건수/퍼널 불변 · `isExempt` true. 회수 → 다음 요청부터 false(다시 로그인 없이). 전권(ADMIN)은 역할 배정 없이 면제.
- 위계: `STAFF ⊃ CS ⊃ VIEWER` 에서 STAFF 계정이 VIEWER 권한으로 통과(전이), A→B→A 순환 409(두 인스턴스 동시 편집도 — 전역 잠금), 전권 물려받기 409, 자기에게 없는 권한을 가진 역할을 `includes` 에 더하기 403, 물려받던 `authz.roles` 를 잃는 변경 409, 물려받히는 역할 지우기 409, `CS` 의 권한을 바꾸면 같은 인스턴스 STAFF 즉시 · 다른 인스턴스 `cache-ttl` 안, yml 순환 → 기동 실패, `PUT` 에 `includes` 없으면 유지.
- 모듈 단독: `coupon` 만 · `point` 만 · `campaign` + `reward` 만(지급기 없음 → 지급 건이 `FAILED` 로 남고 기동은 된다) 조립이 뜬다. `reward` 는 `notification` 없이(`noOptionalTest`).
- 두 방언 `dbTest`(`postgresTest` · `mysqlTest`) 각 `-jdbc`, `MigrationLockTest` · `CapabilitiesCatalogTest` · `ModuleDocumentationTest`.

**e2e (react sample + kotlin sample)**: §10 의 1 · 2(토스 가짜 서버) · 3 · 6 한 여정씩. 4 · 5 는 서버 시험으로 갈음.

## 13. 작업 조각

| # | 레포 | 조각 | 선행 | 크기 |
|---|---|---|---|---|
| K1 | kotlin | 카카오페이 0원 1회차로 빌링키(SID)를 받을 수 있나 확인(문서 · 테스트 키) — §5 의 갈래를 정한다 | — | 소 |
| K2 | kotlin | `platform` 계약(`RewardIssuer` · `DiscountSource` · `DiscountMath` · `AccountFacts`) + `account` 의 `AccountFacts` + `reward`/`reward-jdbc`(지급 건 · 일꾼 · `JobDeadListener` · 알림 · 운영 API) | — | 중 |
| K3 | kotlin | `point`/`point-jdbc` | K2 | 중 |
| K4 | kotlin | `coupon`/`coupon-jdbc`(단계 정의 · 발급 · 공용 코드 · 쿠폰함 · 운영 API · `RewardIssuer` · `DiscountSource`) | K2 | 대 |
| K5 | kotlin | `campaign`/`campaign-jdbc`(틀 · 출석 · 초대 · 운영 API) | K2 | 대 |
| K6 | kotlin | `payment` 단건 할인 · 0원 주문 · 결제 면제 · 미리보기 · 대시보드 기여 | K2 | 대 |
| K7 | kotlin | `payment` 구독 할인(시작 · 단계 복사 · 갱신 금액 · 0원 기간 · 플랜 변경 · 해지 · 환불) | K6 · K1 | 중~대 |
| K8 | kotlin | 견본 앱 조립 · 씨앗 · `qualify` · 면제 검사 꼴 + 쿠폰 ↔ 결제 통합 시험 + 조합 · 문서 · CHANGELOG | K3~K7 · K9 | 소~중 |
| K9 | kotlin | 역할 위계(§6.1): `platform` 펼침 · yml 검사 · 캐시 + `authz`/`authz-jdbc` 표 · API · 안전장치 · 전역 잠금 · 운영 기록 | — | 중 |
| R1 | react | `@skeleton/rewards` 사용자 부품(출석 · 초대 · 쿠폰함 · 코드 · 포인트) | 계약만 | 중 |
| R2 | react | `@skeleton/payment` 할인(입력 · 미리보기 · 표시 · 0원 · 결제 면제 배지 · 구독 다음 결제) | R1 · 계약 | 중 |
| R3 | react | `@skeleton/rewards/admin` 네 화면 + 견본 라우트 · 메뉴 + e2e | R1 · (e2e) K8 | 대 |
| R4 | react | `@skeleton/auth/admin` `AdminRoles` 위계 | K9 계약 | 소 |

- 줄기: **K1 ∥ K2 ∥ K9 ∥ R1 ∥ R4** → **K3 ∥ K4 ∥ K5 ∥ K6 ∥ R2** → **K7 ∥ R3** → K8 → e2e. 실행 계획: `docs/superpowers/plans/2026-10-28-events-rewards.md`.
- developer 13회(K1 은 조사 한 번). K9 · R4 는 이벤트 · 보상과 독립 — 처음부터 나란히. 묶음(kotlin 끝 · react 끝)마다 리뷰 1 + 확인 1, 전체 스위트는 묶음 끝에 지휘 세션이 한 번.

## 정한 것 (내 판단 · 국룰 — 바꾸려면 말해 달라)

이벤트 · 보상 · 쿠폰 · 포인트를 모듈 넷(+ `-jdbc`)으로 나누고 계약은 `platform` / 이벤트는 `campaign` 이라 부른다(`event-kafka` · 계정 이벤트와 헷갈리지 않게) / 출석 날짜는 서비스 시간대 / 초대 귀속 30일 · 가입 후 7일 안 claim · 맞초대 거절 / 초대자 보상 시점은 이벤트마다 고르고 기본값 없음 / 쿠폰은 단계 목록(Stripe duration × 할인 방식, 무료 = 100% 단계) · 주문당 1장 · 쿠폰 먼저 포인트 나중 · 정률 버림 / 전액 환불만 쿠폰 복원 · 소진한 구독 회차는 안 돌려줌 / 플랜 변경 차액에는 할인 없음 · 남은 회차는 새 플랜에 이어서 / 쿠폰 0원 주문은 `provider = "free"` 로 내부 PAID · 환불 없음 · 결제 건수에는 들어감 / 국룰: 결제 면제 주문은 장외 결제 표시를 `provider = "exempt"` 로 · 매출 · 건수 · 퍼널 · 환불에서 뺌 / 무료 이용권은 FREE 쿠폰 발급 하나로 / 포인트 유효기간 기본 없음 · 소멸 임박 순 차감 · 잔액 초과 차감 거절 / 역할 위계는 Spring `RoleHierarchy` 뜻 · `permissionsOf` 를 펼친 값으로 · PUT 에 `includes` 가 없으면 유지 · 위계 편집은 전권 줄 잠금 · 바뀌면 캐시 전체 비움 / react 는 한 패키지 `@skeleton/rewards` 하위 경로 / 페이들 쿠폰 비지원.

## 정해야 할 것 (형)

없음 — 결정 1~10 이 맨 위 표에 있다.
