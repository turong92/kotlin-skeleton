-- modules:waiting-room-jdbc — 대기열(가상 대기실) (postgresql). 설계: docs/modules/waiting-room.md
-- 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- 표 셋을 한 번에 만든다 — 잠근 뒤에는 고칠 수 없다. 시각은 timestamptz · JSON 열 없음 · 부분 인덱스 없음.
-- 계정 id(account_id)에는 외래 키가 없다 (다른 모듈의 표).

-- 대기실 하나. queue_tail 은 지금까지 매긴 번호의 끝 - 번호를 줄 때 이 줄을 먼저 잠근다(번호가 1..n 빈틈 없이).
-- shuffled_at 은 오픈 순간의 섞기를 끝낸 시각으로, 조건부 갱신(shuffled_at is null)을 이긴 한 번만 채워진다.
create table if not exists waiting_rooms (
    id                 varchar(40)  primary key,                -- wr_ + ULID
    scope_key          varchar(200) not null,
    opens_at           timestamptz  not null,
    prequeue_starts_at timestamptz  not null,
    entry_seconds      integer      not null,                   -- 입장 시간 - 번호가 입장 수에 닿은 때부터
    status             varchar(8)   not null,                   -- ACTIVE | CLOSED
    shuffled_at        timestamptz,
    queue_tail         bigint       not null default 0,
    created_at         timestamptz  not null,
    constraint uq_waiting_rooms_scope unique (scope_key),
    constraint ck_waiting_rooms_entry check (entry_seconds >= 1)
);

-- 속도 구간 - from_at 부터 분당 per_minute 명(0 = 멈춤). 입장 수(서빙)는 오픈 시각부터 이 표를 적분한 값이다.
create table if not exists waiting_room_rates (
    room_id    varchar(40)  not null,
    from_at    timestamptz  not null,
    per_minute integer      not null,
    primary key (room_id, from_at),
    constraint ck_waiting_room_rates_rate check (per_minute >= 0)
);

-- 순번표 - 계정당 방마다 하나. queue_no 는 오픈 전 줄이 섞이기 전에는 NULL (NULL 은 유니크에 걸리지 않는다).
create table if not exists waiting_room_tickets (
    id          varchar(40)  primary key,                       -- wrt_ + ULID
    room_id     varchar(40)  not null,
    account_id  varchar(128) not null,
    queue_no    bigint,
    shuffle_key bigint       not null,
    created_at  timestamptz  not null,
    constraint uq_waiting_room_tickets_account unique (room_id, account_id),
    constraint uq_waiting_room_tickets_no unique (room_id, queue_no),
    constraint ck_waiting_room_tickets_no check (queue_no is null or queue_no >= 1)
);
