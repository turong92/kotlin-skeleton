-- modules:coupon-jdbc — 쿠폰 정의 · 쿠폰함 · 공용 코드 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists coupon_definitions (
    id                 varchar(40)  primary key,
    name               varchar(100) not null,
    status             varchar(16)  not null,
    stages_json        text         not null,
    currency           char(3),
    min_amount         bigint,
    scope_json         text         not null,
    first_purchase_only boolean     not null default false,
    valid_days         int,
    valid_until        datetime(6),
    issue_limit        int,
    issued_count       int          not null default 0,
    per_account_limit  int          not null default 1,
    created_at         datetime(6)  not null,
    updated_at         datetime(6)  not null
);

create table if not exists coupons (
    id            varchar(40)  primary key,
    definition_id varchar(40)  not null,
    account_id    varchar(64)  not null,
    status        varchar(12)  not null,
    holder_ref    varchar(64),
    source_type   varchar(8)   not null,
    source_ref    varchar(200) not null,
    expires_at    datetime(6),
    used_at       datetime(6),
    created_at    datetime(6)  not null,
    updated_at    datetime(6)  not null,
    constraint uq_coupons_source unique (source_type, source_ref)
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_coupons_account (account_id, status),
    index idx_coupons_holder (holder_ref),
    index idx_coupons_definition (definition_id, account_id)
    /* [jooq ignore stop] */
);

create table if not exists coupon_codes (
    code             varchar(64) primary key,
    definition_id    varchar(40) not null,
    max_redemptions  int,
    redeemed_count   int         not null default 0,
    expires_at       datetime(6),
    created_at       datetime(6) not null
);

create table if not exists coupon_code_redemptions (
    code        varchar(64) not null,
    account_id  varchar(64) not null,
    primary key (code, account_id)
    /* [jooq ignore start] */,
    index idx_coupon_code_redemptions_account (account_id)
    /* [jooq ignore stop] */
);
