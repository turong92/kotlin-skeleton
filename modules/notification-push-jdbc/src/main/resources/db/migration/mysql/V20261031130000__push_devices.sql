-- modules:notification-push-jdbc — 푸시 기기 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists push_devices (
    id              varchar(40)   primary key,
    account_id      varchar(128)  not null,
    installation_id varchar(64)   not null,
    session_id      varchar(64),
    kind            varchar(16)   not null,
    platform        varchar(16)   not null,
    address         varchar(2048) not null,
    address_hash    char(64)      not null,
    p256dh          varchar(128),
    auth_secret     varchar(64),
    vapid_key_id    varchar(16),
    label           varchar(100),
    created_at      datetime(6)   not null,
    last_seen_at    datetime(6)   not null,
    last_success_at datetime(6),
    constraint uq_push_devices_installation unique (installation_id),
    constraint uq_push_devices_address unique (address_hash)
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_push_devices_account (account_id),
    index idx_push_devices_session (session_id),
    index idx_push_devices_last_seen (last_seen_at)
    /* [jooq ignore stop] */
);
