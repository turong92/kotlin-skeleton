-- point_reservations (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:point-jdbc — PostgreSQL 판과 같은 뜻: 잡아 두기(reserve → capture | release). 잡은 양은 잔액에서 이미 빠져 있고, 어느 묶음에서 덜었는지는 point_spend_allocations 가 들고 있다.
-- 줄 id · 키는 이진 정렬이다(point_entries 와 같다) - collate 는 jOOQ DDLDatabase 가 못 읽어 마커로 가린다. 인라인 index 도 같은 마커 안에 둔다.
create table if not exists point_reservations (
    id              varchar(40)  /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ primary key,
    account_id      varchar(128) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    amount          bigint       not null,
    captured_amount bigint,
    status          varchar(16)  not null,
    source_type     varchar(32)  /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    source_ref      varchar(200) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    hold_until      datetime(6)  not null,
    created_at      datetime(6)  not null,
    settled_at      datetime(6),
    constraint uq_point_reservations_source unique (source_type, source_ref),
    constraint ck_point_reservations_amount check (amount > 0),
    constraint ck_point_reservations_captured check (captured_amount is null or (captured_amount > 0 and captured_amount <= amount))
    /* [jooq ignore start] */,
    index idx_point_reservations_due (status, hold_until),
    index idx_point_reservations_account (account_id, status)
    /* [jooq ignore stop] */
);
