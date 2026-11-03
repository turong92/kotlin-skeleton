-- modules:booking-jdbc — 예약(좌석 지정 · 선착순) (postgresql). 설계: docs/modules/booking.md
-- 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- 표 다섯을 한 번에 만든다 — 잠근 뒤에는 고칠 수 없다. 금액은 통화의 최소 단위 정수(bigint) · 시각은 timestamptz · JSON 열 없음 · 부분 인덱스 없음.
-- 계정 id(account_id)에는 외래 키가 없다 (다른 모듈의 표).

-- 풀 = 회차 하나. max_per_hold 는 만들 때 필수(기본값 없음 - 행사 성격을 모듈이 모른다).
create table if not exists booking_pools (
    id                varchar(40)   primary key,                -- bkp_ + ULID
    name              varchar(200)  not null,
    opens_at          timestamptz   not null,
    closes_at         timestamptz,
    max_per_hold      integer       not null,
    per_account_limit integer,                                  -- NULL 이면 계정당 한도 없음
    currency          varchar(8),
    hold_minutes      integer       not null default 10,
    waiting_room_id   varchar(64),
    status            varchar(8)    not null,                   -- ACTIVE | PAUSED | CLOSED
    created_at        timestamptz   not null,
    constraint ck_booking_pools_limits check (max_per_hold >= 1 and hold_minutes >= 1 and (per_account_limit is null or per_account_limit >= 1))
);

-- 자리 한 칸 = 경쟁 단위. 상태 전이는 모두 이 줄의 조건부 갱신(status = 'AVAILABLE' 일 때만 잡힌다)이고, 바뀔 때마다 version 이 1 오른다.
create table if not exists booking_seats (
    id           varchar(40)  primary key,                      -- bks_ + ULID
    pool_id      varchar(40)  not null,
    section      varchar(64)  not null,
    row_label    varchar(32)  not null,
    number       integer      not null,
    grade        varchar(32)  not null,
    price_amount bigint,                                        -- NULL 이면 무료
    status       varchar(10)  not null,                         -- AVAILABLE | HELD | SOLD | BLOCKED
    hold_id      varchar(40),                                   -- HELD · SOLD 일 때 그 보류
    held_until   timestamptz,                                   -- HELD 일 때 보류의 expires_at 을 옮겨 적는다 (스냅샷 · 사건이 표 하나로 끝난다)
    version      bigint       not null default 0,
    created_at   timestamptz  not null,
    constraint uq_booking_seats_place unique (pool_id, section, row_label, number),   -- pool_id 로 찾는 색인도 겸한다
    constraint ck_booking_seats_price check (price_amount is null or price_amount >= 0)
);

-- 보류 = 한 계정이 한 풀에서 잡고 있는 자리 묶음. active_slot 은 HELD 일 때만 1 이고 나머지는 NULL - NULL 은 유니크에 걸리지 않으므로 계정당 풀마다 열린 보류 하나가 부분 색인 없이 선다.
create table if not exists booking_holds (
    id                  varchar(40)  primary key,               -- bkh_ + ULID
    pool_id             varchar(40)  not null,
    account_id          varchar(128) not null,
    seat_count          integer      not null default 0,
    amount              bigint,                                 -- 결제로 정해진 금액(무료 NULL)
    status              varchar(12)  not null,                  -- HELD | CONFIRMED | RELEASED | EXPIRED | CANCELED | UNFULFILLED
    active_slot         smallint,
    expires_at          timestamptz  not null,                  -- 보류는 늘 이 시각에 끝난다 (결제를 시작해도 늘지 않는다)
    checkout_started_at timestamptz,                            -- 결제를 시작하면 자리를 고정한다
    confirmed_order_id  varchar(64),
    created_at          timestamptz  not null,
    constraint uq_booking_holds_open unique (pool_id, account_id, active_slot),
    constraint ck_booking_holds_seat_count check (seat_count >= 0)
);
create index if not exists idx_booking_holds_expiry on booking_holds (status, expires_at);      -- 만료 회수
create index if not exists idx_booking_holds_account on booking_holds (account_id, status);     -- 내 보류

-- 보류가 가졌던 자리 (늦은 결제의 다시 잡기 · 내 예약 목록). 보류가 닫혀도 남는다.
create table if not exists booking_hold_seats (
    hold_id varchar(40) not null,
    seat_id varchar(40) not null,
    primary key (hold_id, seat_id)
);

-- 계정이 풀에서 쥔 자리 수 = 그 계정의 열린 · 확정 보류 seat_count 의 합. 이 줄이 계정 안의 쓰기를 직렬로 세운다(잠금 순서의 첫째).
create table if not exists booking_usage (
    pool_id    varchar(40)  not null,
    account_id varchar(128) not null,
    seats      integer      not null default 0,
    primary key (pool_id, account_id),
    constraint ck_booking_usage_nonneg check (seats >= 0)
);
