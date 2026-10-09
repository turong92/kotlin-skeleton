-- modules:payment-jdbc — 구독 (mysql). 설계: docs/modules/payment-jdbc.md · docs/superpowers/specs/2026-10-09-subscription-design.md §4
-- 동결된 V20261009124513__payment_ledger.sql 을 고치지 않고 표 하나를 더한다. 주문 줄은 이미 구독 자리를 가진다(kind · origin_type=SUBSCRIPTION · origin_ref=구독 id · item_type=subscription).
-- 공통 규칙은 장부와 같다: 금액은 bigint 통화 최소 단위 + 통화 열 · 시각은 datetime(6) UTC · JSON 열 없음 · 부분 인덱스 없음.
-- 우리 키를 담는 열(id · 계정 id · charge_order_id · active_key)은 이진 정렬(utf8mb4_bin) — 장부와 같은 이유(대소문자만 다른 두 키가 한 줄로 합쳐지지 않게, 주문 줄의 origin_ref 와 정렬을 맞춰 조인한다).
-- collate 는 jOOQ DDLDatabase 가 못 읽는 절이라 마커로 가린다(docs/persistence-jooq.md). 인라인 index 도 같은 이유로 마커 안에 둔다.

-- 구독 — 계정당 살아 있는 구독은 하나(active_key 유니크). 빌링키는 암호문(crypto 모듈)이고 어떤 응답 · 로그에도 나가지 않는다.
create table if not exists payment_subscriptions (
    id                   varchar(40) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ primary key,   -- sub_ + 32자 16진수 = sha256(계정 id + "\n" + Idempotency-Key) 의 앞 32자
    account_id           varchar(128) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    provider             varchar(40)  not null,
    provider_mode        varchar(8)   not null,           -- TEST | LIVE | UNKNOWN
    status               varchar(16)  not null,           -- PENDING | ACTIVE | PAST_DUE | CANCELING | ENDED
    plan_code            varchar(64)  not null,
    currency             char(3)      not null,
    amount               bigint       not null,
    period               varchar(24)  not null,           -- ISO-8601: P1M | PT3M …
    anchor_at            datetime(6),
    period_seq           int          not null default 0,
    current_period_start datetime(6),
    current_period_end   datetime(6),
    charge_order_id      varchar(40) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */,
    attempt              int          not null default 1,
    customer_key         varchar(64),
    billing_token        varchar(512),
    card_issuer          varchar(40),
    card_last4           varchar(4),
    active_key           varchar(128) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */,      -- 살아 있는 동안 = account_id, ENDED 면 NULL → 계정당 하나
    pending_plan_code    varchar(64),
    pending_amount       bigint,
    end_reason           varchar(40),
    ended_at             datetime(6),
    account_erased_at    datetime(6),
    created_at           datetime(6)  not null,
    updated_at           datetime(6)  not null,
    constraint uq_payment_subscriptions_active unique (active_key),   -- NULL 은 서로 겹치지 않는다
    constraint ck_payment_subscriptions_amount check (amount > 0)
    /* [jooq ignore start] */,
    index idx_payment_subscriptions_account (account_id, created_at, id)   -- 내 구독 (거꾸로 훑는다)
    /* [jooq ignore stop] */
);
