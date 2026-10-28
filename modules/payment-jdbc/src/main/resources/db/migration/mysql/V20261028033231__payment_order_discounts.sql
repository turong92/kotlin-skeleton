-- modules:payment-jdbc — 주문의 할인 (mysql). PostgreSQL 판과 같은 뜻.
alter table payment_orders add column original_amount bigint;
alter table payment_orders add column discount_amount bigint not null default 0;

create table if not exists payment_order_discounts (
    order_id   varchar(40) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    seq        int          not null,
    kind       varchar(16)  not null,
    source_ref varchar(64) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    label      varchar(100) not null,
    amount     bigint       not null,
    currency   char(3)      not null,
    status     varchar(16)  not null,
    created_at datetime(6)  not null,
    updated_at datetime(6)  not null,
    primary key (order_id, seq),
    constraint ck_payment_order_discounts_amount check (amount > 0)
    /* [jooq ignore start] */,
    foreign key (order_id) references payment_orders (id),
    index idx_payment_order_discounts_source (kind, source_ref)
    /* [jooq ignore stop] */
);
