-- modules:account-jdbc — 계정 · 로그인 수단(identities) · 역할 · 한 번 쓰는 토큰 · 감사 기록. 설계: docs/modules/account.md · docs/accounts.md
-- 계정에는 로그인 수단이 없다 — 수단(비밀번호 · 소셜 · 매직 링크 …)은 identities 의 행이고 method 는 문자열 코드라서 새 수단이 스키마 변경 없이 들어온다.
create table if not exists accounts (
    id               varchar(40)  primary key,
    email            varchar(254) unique,                    -- 정규화(소문자). 없을 수 있다(이메일을 안 주는 소셜 가입) — 유니크는 NULL 을 여럿 허용한다
    email_verified   boolean      not null default false,
    status           varchar(24)  not null,                  -- ACTIVE | PENDING_VERIFICATION | SUSPENDED | DELETED (유예, 복구 가능) | ERASED (개인정보를 지운 뒤 행만 남음)
    display_name     varchar(60),
    locale           varchar(35),
    time_zone        varchar(64),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    last_login_at    timestamptz,
    suspended_reason varchar(200),
    deleted_at       timestamptz,
    purge_after      timestamptz,                            -- 삭제 유예가 끝나 지워질 시각
    erased_at        timestamptz                             -- 개인정보를 지운 시각 (ERASED). 이때 email · display_name · locale · time_zone · suspended_reason · last_login_at 은 NULL
);
create index if not exists idx_accounts_due on accounts (status, purge_after);

create table if not exists account_roles (
    account_id varchar(40) not null references accounts (id) on delete cascade,
    role       varchar(40) not null,
    primary key (account_id, role)
);
create index if not exists idx_account_roles_role on account_roles (role);

create table if not exists account_identities (
    id           varchar(40)  primary key,
    account_id   varchar(40)  not null references accounts (id) on delete cascade,
    method       varchar(32)  not null,                      -- password | magic_link | google | kakao | ... (문자열 코드)
    subject      varchar(254) not null,                      -- 수단 안의 식별자: 이메일 · 제공자 사용자 id
    verified     boolean      not null default false,
    secret       varchar(255),                               -- 비밀번호 해시 ({bcrypt}…). 다른 수단은 NULL
    metadata     varchar(2000),
    created_at   timestamptz  not null,
    last_used_at timestamptz,
    constraint uq_account_identities_subject unique (method, subject)
);
create index if not exists idx_account_identities_account on account_identities (account_id);

-- 이메일 인증 · 비밀번호 재설정 · 이메일 변경 · 매직 링크 · 삭제 확인이 같이 쓰는 한 번 쓰는 토큰. 원문은 저장하지 않는다 (SHA-256 hex 만)
create table if not exists account_tokens (
    token_hash  varchar(64)  primary key,
    purpose     varchar(32)  not null,
    subject     varchar(254) not null,                       -- 이 용도 안에서 토큰 주인 (이메일 또는 계정 id)
    account_id  varchar(40),
    payload     varchar(254),
    created_at  timestamptz  not null,
    expires_at  timestamptz  not null,
    consumed_at timestamptz
);
create index if not exists idx_account_tokens_owner on account_tokens (purpose, subject);
create index if not exists idx_account_tokens_expires on account_tokens (expires_at);

-- 인증 사건 기록 (skeleton.account.audit.enabled=true 일 때만 쓴다). 토큰 · 이메일 · 비밀번호는 싣지 않는다
create table if not exists account_audit (
    id         bigint generated always as identity primary key,
    at         timestamptz  not null,
    type       varchar(40)  not null,
    account_id varchar(40),
    ip         varchar(64),
    detail     varchar(1000)
);
create index if not exists idx_account_audit_account on account_audit (account_id, at);
create index if not exists idx_account_audit_at on account_audit (at);

-- 운영자가 정지한 계정을 지울 때 남기는 재가입 차단. **해시만** 있다 (서버 비밀의 HMAC-SHA256 — 이메일 · 제공자 주체 원문은 어디에도 없다). 개인정보 보존이므로 처리방침에 적는다 (docs/accounts.md)
create table if not exists account_blocks (
    id         bigint generated always as identity primary key,
    kind       varchar(16)  not null,                       -- email | identity
    hash       varchar(64)  not null unique,
    reason     varchar(200),
    created_at timestamptz  not null,
    expires_at timestamptz,                                          -- NULL = 운영자가 지울 때까지
    created_by varchar(40),
    account_id varchar(40)                                  -- 지워진(ERASED) 계정 행. FK 없음 — DELETE 모드에서도 남는다
);
create index if not exists idx_account_blocks_expires on account_blocks (expires_at);
