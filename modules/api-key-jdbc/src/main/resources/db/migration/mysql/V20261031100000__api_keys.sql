-- modules:api-key-jdbc — API 키 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists api_keys (
    id              varchar(40)   primary key,
    owner_type      varchar(32)   not null,
    owner_id        varchar(64)   not null,
    created_by      varchar(64)   not null,
    name            varchar(100)  not null,
    token_hash      char(64)      not null,
    token_hint      varchar(24)   not null,
    scopes          varchar(1000) not null,
    expires_at      datetime(6),
    revoked_at      datetime(6),
    revoked_by      varchar(64),
    revoked_reason  varchar(200),
    last_used_at    datetime(6),
    last_used_ip    varchar(45),
    created_at      datetime(6)   not null,
    constraint uq_api_keys_token_hash unique (token_hash)
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_api_keys_owner (owner_type, owner_id, created_at)
    /* [jooq ignore stop] */
);
