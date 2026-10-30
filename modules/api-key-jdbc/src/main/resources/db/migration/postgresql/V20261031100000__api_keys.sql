-- modules:api-key-jdbc — API 키 (PostgreSQL). 설계: docs/modules/api-key.md · docs/superpowers/specs/2026-10-30-mfa-apikey-design.md §4
-- 시각은 timestamptz (UTC). 토큰 원문은 어디에도 없다 - token_hash 는 SHA-256(16진 64자).

-- owner_type 'ACCOUNT' 는 owner_id 가 계정 id. 다음 판의 소유자 종류가 같은 칸을 쓴다. created_by 는 만든 계정.
-- revoked_by · revoked_reason 은 회수한 계정과 사유(운영 회수가 적는다) - 본인 회수는 revoked_by 만.
create table if not exists api_keys (
    id              varchar(40)   primary key,
    owner_type      varchar(32)   not null,
    owner_id        varchar(64)   not null,
    created_by      varchar(64)   not null,
    name            varchar(100)  not null,
    token_hash      char(64)      not null,
    token_hint      varchar(24)   not null,
    scopes          varchar(1000) not null,          -- 공백으로 구분한 범위 키
    expires_at      timestamptz,
    revoked_at      timestamptz,
    revoked_by      varchar(64),
    revoked_reason  varchar(200),
    last_used_at    timestamptz,
    last_used_ip    varchar(45),
    created_at      timestamptz   not null,
    constraint uq_api_keys_token_hash unique (token_hash)
);
create index if not exists idx_api_keys_owner on api_keys (owner_type, owner_id, created_at);
