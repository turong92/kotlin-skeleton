-- modules:payment-jdbc — 구독의 할인 일정 (mysql). PostgreSQL 판과 같은 뜻.
create table if not exists payment_subscription_discounts (
    subscription_id varchar(40) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ primary key,
    kind            varchar(16)  not null,
    source_ref      varchar(64) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    label           varchar(100) not null,
    periods_used    int          not null default 0,
    status          varchar(16)  not null,
    created_at      datetime(6)  not null,
    updated_at      datetime(6)  not null
    /* [jooq ignore start] */,
    foreign key (subscription_id) references payment_subscriptions (id)
    /* [jooq ignore stop] */
);

create table if not exists payment_subscription_discount_stages (
    subscription_id varchar(40) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    seq             int         not null,
    type            varchar(16) not null,
    value           bigint      not null,
    max_discount    bigint,
    periods         int,
    primary key (subscription_id, seq)
    /* [jooq ignore start] */,
    foreign key (subscription_id) references payment_subscriptions (id)
    /* [jooq ignore stop] */
);

-- 이 쿠폰으로 시작한 구독 찾기(쿠폰 현황의 할인 합계)
create index idx_payment_subscription_discounts_source on payment_subscription_discounts (kind, source_ref);
