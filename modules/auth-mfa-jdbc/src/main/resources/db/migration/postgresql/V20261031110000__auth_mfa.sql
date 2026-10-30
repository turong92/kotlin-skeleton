-- modules:auth-mfa-jdbc — 2단계 인증 (PostgreSQL). 설계: docs/modules/auth-mfa.md · docs/superpowers/specs/2026-10-30-mfa-apikey-design.md §3
-- 시각은 timestamptz (UTC). 비밀 · 복구 코드 원문은 어디에도 없다 - secret_enc 는 crypto 의 TextEncryptor 봉투(aes-gcm:v1:…), code_hash 는 SHA-256(16진 64자).

-- 계정당 한 줄. 켜짐 = secret_enc 있음. 재등록은 pending_* 에 두었다가 확인 때 한 문장으로 옮긴다(옛 비밀은 새 것이 확인될 때까지 산다).
-- last_used_step 은 마지막으로 받은 TOTP 걸음 - 같은 걸음의 코드를 두 번 받지 않는 조건부 UPDATE 의 기준(RFC 6238 §5.2).
create table if not exists mfa_totp (
    account_id          varchar(64)  primary key,
    secret_enc          varchar(512),
    enabled_at          timestamptz,
    last_used_step      bigint,
    pending_secret_enc  varchar(512),
    pending_expires_at  timestamptz,
    updated_at          timestamptz  not null
);

-- 복구 코드 - 재발급은 지우고 새로 넣는다. 쓰기는 `update … set used_at = :now where account_id = ? and code_hash = ? and used_at is null`(한 요청만 이긴다).
create table if not exists mfa_recovery_codes (
    id          varchar(40)  primary key,
    account_id  varchar(64)  not null,
    code_hash   char(64)     not null,
    used_at     timestamptz,
    created_at  timestamptz  not null,
    constraint uq_mfa_recovery_codes_account_hash unique (account_id, code_hash)
);
