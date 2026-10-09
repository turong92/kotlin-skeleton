-- modules:payment-jdbc — 구독 (postgresql). 설계: docs/modules/payment-jdbc.md · docs/superpowers/specs/2026-10-09-subscription-design.md §4
-- 동결된 V20261009124513__payment_ledger.sql 을 고치지 않고 표 하나를 더한다. 주문 줄은 이미 구독 자리를 가진다(kind · origin_type=SUBSCRIPTION · origin_ref=구독 id · item_type=subscription).
-- 공통 규칙은 장부와 같다: 금액은 bigint 통화 최소 단위 + 통화 열 · 시각은 timestamptz · JSON 열 없음 · 부분 인덱스 없음.

-- 구독 — 계정당 살아 있는 구독은 하나(active_key 유니크). 빌링키는 암호문(crypto 모듈)이고 어떤 응답 · 로그에도 나가지 않는다.
create table if not exists payment_subscriptions (
    id                   varchar(40)  primary key,        -- sub_ + 32자 16진수 = sha256(계정 id + "\n" + Idempotency-Key) 의 앞 32자 — 같은 요청은 같은 줄이다
    account_id           varchar(128) not null,           -- 산 계정. FK 없음 (다른 모듈의 표)
    provider             varchar(40)  not null,           -- 만들 때 서버가 고른 결제사. 바뀌지 않는다
    provider_mode        varchar(8)   not null,           -- TEST | LIVE | UNKNOWN
    status               varchar(16)  not null,           -- PENDING | ACTIVE | PAST_DUE | CANCELING | ENDED
    plan_code            varchar(64)  not null,           -- 만들 때 박힌 플랜 · 금액 · 주기 — 카탈로그를 바꿔도 기존 구독에 조용히 적용되지 않는다
    currency             char(3)      not null,
    amount               bigint       not null,
    period               varchar(24)  not null,           -- ISO-8601: P1M | PT3M …
    anchor_at            timestamptz,                     -- 첫 결제 시각 — 기간 끝은 늘 여기서 센다. 첫 결제 전에는 NULL
    period_seq           int          not null default 0, -- 지급된 기간의 수
    current_period_start timestamptz,
    current_period_end   timestamptz,
    charge_order_id      varchar(40),                     -- 지금 과금 중인(또는 마지막으로 과금한) 주문. 기간은 이 주문이 지급될 때만 넘어간다
    attempt              int          not null default 1, -- 이번 기간의 시도 번호
    customer_key         varchar(64),                     -- 결제사에 댈 고객 키(cus_ + 32자 16진수, 무작위). 빌링키를 지운 뒤 NULL
    billing_token        varchar(512),                   -- 빌링키의 암호문. 결제사에서 지운 뒤 NULL
    card_issuer          varchar(40),                     -- 화면 표시용 — 끝나면 NULL (지워지는 개인정보)
    card_last4           varchar(4),                      -- 화면 표시용 — 끝나면 NULL (지워지는 개인정보)
    active_key           varchar(128),                    -- 살아 있는 동안 = account_id, ENDED 면 NULL → 계정당 하나 (유니크는 아래 uq_payment_subscriptions_active)
    pending_plan_code    varchar(64),                     -- 2차(플랜 변경)용 — 잠긴 파일은 못 고치므로 미리 둔다
    pending_amount       bigint,
    end_reason           varchar(40),                     -- ENDED 일 때: FIRST_PAYMENT_DECLINED | USER_CANCELED | PAYMENT_FAILED | ACCOUNT_ERASED | RENEWAL_BLOCKED | REFUNDED
    ended_at             timestamptz,
    account_erased_at    timestamptz,                     -- 이 계정이 지워진 시각
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    constraint uq_payment_subscriptions_active unique (active_key),   -- NULL 은 서로 겹치지 않는다
    constraint ck_payment_subscriptions_amount check (amount > 0),
    -- 살아 있는 상태(ACTIVE · PAST_DUE · CANCELING)는 자리를 쥐고, ENDED 는 풀었다 — 어긋난 줄이 계정을 잠그거나 자리를 공짜로 풀지 못하게. PENDING 은 카드를 내기 전이라 자리가 없다
    constraint ck_payment_subscriptions_seat check ((status in ('ACTIVE', 'PAST_DUE', 'CANCELING') and active_key is not null) or (status = 'ENDED' and active_key is null) or status = 'PENDING')
);
create index if not exists idx_payment_subscriptions_account on payment_subscriptions (account_id, created_at, id);   -- 내 구독 (거꾸로 훑는다)
