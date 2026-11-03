-- modules:waiting-room-jdbc — 대기열(가상 대기실) (mysql). PostgreSQL 판과 같은 뜻. 설계: docs/modules/waiting-room.md
-- 범위 키 · 계정 id 는 이진 정렬이다 — MySQL 기본 정렬은 대소문자를 같게 봐서 대소문자만 다른 둘이 한 줄로 합쳐진다.
-- collate 는 jOOQ DDLDatabase 가 못 읽는 절이라 마커로 가린다 (docs/persistence-jooq.md).
create table if not exists waiting_rooms (
    id                 varchar(40)  primary key,
    scope_key          varchar(200) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    opens_at           datetime(6)  not null,
    prequeue_starts_at datetime(6)  not null,
    entry_seconds      integer      not null,
    status             varchar(8)   not null,
    shuffled_at        datetime(6),
    queue_tail         bigint       not null default 0,
    created_at         datetime(6)  not null,
    constraint uq_waiting_rooms_scope unique (scope_key),
    constraint ck_waiting_rooms_entry check (entry_seconds >= 1)
);

create table if not exists waiting_room_rates (
    room_id    varchar(40)  not null,
    from_at    datetime(6)  not null,
    per_minute integer      not null,
    primary key (room_id, from_at),
    constraint ck_waiting_room_rates_rate check (per_minute >= 0)
);

create table if not exists waiting_room_tickets (
    id          varchar(40)  primary key,
    room_id     varchar(40)  not null,
    account_id  varchar(128) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    queue_no    bigint,
    shuffle_key bigint       not null,
    created_at  datetime(6)  not null,
    constraint uq_waiting_room_tickets_account unique (room_id, account_id),
    constraint uq_waiting_room_tickets_no unique (room_id, queue_no),
    constraint ck_waiting_room_tickets_no check (queue_no is null or queue_no >= 1)
);
