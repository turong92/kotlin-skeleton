-- modules:badge-jdbc — 배지 정의 · 보유 · 수여/회수 내역 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists badge_definitions (
    id           varchar(40)  primary key,
    code         varchar(64)  not null,
    name         varchar(100) not null,
    description  varchar(500) not null,
    image_url    varchar(500),
    hidden       boolean      not null default false,
    sort_order   int          not null default 0,
    status       varchar(12)  not null,
    created_at   datetime(6)  not null,
    updated_at   datetime(6)  not null,
    constraint uq_badge_definitions_code unique (code)
);

create table if not exists badge_awards (
    id          varchar(40) primary key,
    badge_id    varchar(40) not null,
    account_id  varchar(64) not null,
    status      varchar(8)  not null,
    visible     boolean     not null default true,
    awarded_at  datetime(6) not null,
    revoked_at  datetime(6),
    constraint uq_badge_awards_badge_account unique (badge_id, account_id)
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_badge_awards_account (account_id, status)
    /* [jooq ignore stop] */
);

create table if not exists badge_award_events (
    id           varchar(40)  primary key,
    award_id     varchar(40)  not null,
    badge_id     varchar(40)  not null,
    account_id   varchar(64)  not null,
    action       varchar(8)   not null,
    source_type  varchar(8)   not null,
    source_ref   varchar(200),
    actor_id     varchar(64),
    reason       varchar(200),
    created_at   datetime(6)  not null,
    constraint uq_badge_award_events_source unique (action, source_type, source_ref)
    /* [jooq ignore start] */,
    index idx_badge_award_events_badge (badge_id, id),
    index idx_badge_award_events_account (account_id, id)
    /* [jooq ignore stop] */
);
