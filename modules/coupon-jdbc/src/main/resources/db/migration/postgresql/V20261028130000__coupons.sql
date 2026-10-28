-- modules:coupon-jdbc — 쿠폰 정의 · 쿠폰함 · 공용 코드 (PostgreSQL). 설계: docs/modules/coupon.md · docs/superpowers/specs/2026-10-28-events-rewards-design.md §3.3
-- 시각은 timestamptz, 금액은 bigint 통화 최소 단위, JSON 은 *_json text (reward-jdbc 와 같다).

-- 정의 - 마케팅이 행 하나로 만드는 조합. stages_json = 순서대로 이어지는 혜택 단계 [{type: FREE|PERCENT|FIXED, value, maxDiscount, periods(null=영원)}]
create table if not exists coupon_definitions (
    id                 varchar(40)  primary key,
    name               varchar(100) not null,
    status             varchar(16)  not null,             -- ACTIVE | PAUSED | ARCHIVED
    stages_json        text         not null,
    currency           char(3),                           -- 정액 단계 · 최소 금액이 쓰는 통화. 있으면 다른 통화 주문에는 쓸 수 없다
    min_amount         bigint,
    scope_json         text         not null,             -- {purposes, itemTypes, itemRefs, planCodes} - 빈 목록 = 전부
    first_purchase_only boolean     not null default false,
    valid_days         int,                               -- 발급 + N일
    valid_until        timestamptz,                       -- 이 시각까지 (둘 다 있으면 이른 쪽)
    issue_limit        int,
    issued_count       int          not null default 0,
    per_account_limit  int          not null default 1,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null
);

-- 쿠폰함의 한 장. holder_ref = 마지막으로 쥔 주문 id (풀려도 지우지 않는다 - 늦은 결제 확정이 주인을 찾는다)
create table if not exists coupons (
    id            varchar(40)  primary key,
    definition_id varchar(40)  not null,
    account_id    varchar(64)  not null,
    status        varchar(12)  not null,                  -- AVAILABLE | RESERVED | USED | EXPIRED | REVOKED
    holder_ref    varchar(64),
    source_type   varchar(8)   not null,                  -- GRANT | CODE | ADMIN
    source_ref    varchar(200) not null,                  -- 지급 건 id · <코드>:<계정> · 운영자 발급 id - 일꾼 재시도에도 한 장
    expires_at    timestamptz,
    used_at       timestamptz,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    constraint uq_coupons_source unique (source_type, source_ref)
);
create index if not exists idx_coupons_account on coupons (account_id, status);
create index if not exists idx_coupons_holder on coupons (holder_ref);
create index if not exists idx_coupons_definition on coupons (definition_id, account_id);

-- 공용 코드 - 코드 하나를 여러 사람이 계정당 한 번씩. code 는 대문자로 맞춘 값
create table if not exists coupon_codes (
    code             varchar(64) primary key,
    definition_id    varchar(40) not null,
    max_redemptions  int,
    redeemed_count   int         not null default 0,
    expires_at       timestamptz,
    created_at       timestamptz not null
);

create table if not exists coupon_code_redemptions (
    code        varchar(64) not null,
    account_id  varchar(64) not null,
    primary key (code, account_id)
);
create index if not exists idx_coupon_code_redemptions_account on coupon_code_redemptions (account_id);
