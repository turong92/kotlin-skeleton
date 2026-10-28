-- modules:reward-jdbc — 보상 지급 건 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists reward_grants (
    id           varchar(40)  primary key,
    account_id   varchar(64)  not null,
    reward_type  varchar(32)  not null,
    params_json  text         not null,
    source_type  varchar(32)  not null,
    source_key   varchar(200) not null,
    status       varchar(16)  not null,
    result_ref   varchar(64),
    last_error   varchar(200),
    created_at   datetime(6)  not null,
    issued_at    datetime(6),
    constraint uq_reward_grants_source unique (source_type, source_key)
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_reward_grants_status (status, created_at),
    index idx_reward_grants_account (account_id, created_at)
    /* [jooq ignore stop] */
);
