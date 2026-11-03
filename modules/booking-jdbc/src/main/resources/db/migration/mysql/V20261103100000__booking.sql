-- modules:booking-jdbc — 예약(좌석 지정 · 선착순) (mysql). PostgreSQL 판과 같은 뜻. 설계: docs/modules/booking.md
-- 계정 id 는 이진 정렬이다 — MySQL 기본 정렬은 대소문자를 같게 봐서 대소문자만 다른 두 계정이 한 줄로 합쳐진다.
-- collate 는 jOOQ DDLDatabase 가 못 읽는 절이라 마커로 가린다 (docs/persistence-jooq.md). 인라인 index 도 같은 마커 안에 둔다.
create table if not exists booking_pools (
    id                varchar(40)   primary key,
    name              varchar(200)  not null,
    opens_at          datetime(6)   not null,
    closes_at         datetime(6),
    max_per_hold      integer       not null,
    per_account_limit integer,
    currency          varchar(8),
    hold_minutes      integer       not null default 10,
    waiting_room_id   varchar(64),
    status            varchar(8)    not null,
    created_at        datetime(6)   not null,
    constraint ck_booking_pools_limits check (max_per_hold >= 1 and hold_minutes >= 1 and (per_account_limit is null or per_account_limit >= 1))
);

create table if not exists booking_seats (
    id           varchar(40)  primary key,
    pool_id      varchar(40)  not null,
    section      varchar(64)  not null,
    row_label    varchar(32)  not null,
    number       integer      not null,
    grade        varchar(32)  not null,
    price_amount bigint,
    status       varchar(10)  not null,
    hold_id      varchar(40),
    held_until   datetime(6),
    version      bigint       not null default 0,
    created_at   datetime(6)  not null,
    constraint uq_booking_seats_place unique (pool_id, section, row_label, number),
    constraint ck_booking_seats_price check (price_amount is null or price_amount >= 0)
);

create table if not exists booking_holds (
    id                  varchar(40)  primary key,
    pool_id             varchar(40)  not null,
    account_id          varchar(128) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    seat_count          integer      not null default 0,
    amount              bigint,
    status              varchar(12)  not null,
    active_slot         smallint,
    expires_at          datetime(6)  not null,
    checkout_started_at datetime(6),
    confirmed_order_id  varchar(64),
    created_at          datetime(6)  not null,
    constraint uq_booking_holds_open unique (pool_id, account_id, active_slot),
    constraint ck_booking_holds_seat_count check (seat_count >= 0)
    /* [jooq ignore start] */,
    index idx_booking_holds_expiry (status, expires_at),
    index idx_booking_holds_account (account_id, status)
    /* [jooq ignore stop] */
);

create table if not exists booking_hold_seats (
    hold_id varchar(40) not null,
    seat_id varchar(40) not null,
    primary key (hold_id, seat_id)
);

create table if not exists booking_usage (
    pool_id    varchar(40)  not null,
    account_id varchar(128) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    seats      integer      not null default 0,
    primary key (pool_id, account_id),
    constraint ck_booking_usage_nonneg check (seats >= 0)
);
