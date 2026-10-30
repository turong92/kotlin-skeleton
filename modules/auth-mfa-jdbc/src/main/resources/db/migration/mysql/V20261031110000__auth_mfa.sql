-- modules:auth-mfa-jdbc — 2단계 인증 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists mfa_totp (
    account_id          varchar(64)  primary key,
    secret_enc          varchar(512),
    enabled_at          datetime(6),
    last_used_step      bigint,
    pending_secret_enc  varchar(512),
    pending_expires_at  datetime(6),
    updated_at          datetime(6)  not null
);

create table if not exists mfa_recovery_codes (
    id          varchar(40)  primary key,
    account_id  varchar(64)  not null,
    code_hash   char(64)     not null,
    used_at     datetime(6),
    created_at  datetime(6)  not null,
    constraint uq_mfa_recovery_codes_account_hash unique (account_id, code_hash)
);
