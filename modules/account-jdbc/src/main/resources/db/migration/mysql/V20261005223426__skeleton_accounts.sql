-- modules:account-jdbc — 계정 · 로그인 수단(identities) · 역할 · 한 번 쓰는 토큰 · 감사 기록. 설계: docs/modules/account.md · docs/accounts.md
-- 계정에는 로그인 수단이 없다 — 수단(비밀번호 · 소셜 · 매직 링크 …)은 identities 의 행이고 method 는 문자열 코드라서 새 수단이 스키마 변경 없이 들어온다.
create table if not exists skeleton_accounts (
    id               varchar(40)  primary key,
    email            varchar(254) unique,                    -- 정규화(소문자). 없을 수 있다(이메일을 안 주는 소셜 가입) — 유니크는 NULL 을 여럿 허용한다
    email_verified   boolean      not null default false,
    status           varchar(24)  not null,                  -- ACTIVE | PENDING_VERIFICATION | SUSPENDED | DELETED
    display_name     varchar(60),
    locale           varchar(35),
    time_zone        varchar(64),
    created_at       datetime(6)  not null,
    updated_at       datetime(6)  not null,
    last_login_at    datetime(6),
    suspended_reason varchar(200),
    deleted_at       datetime(6),
    purge_after      datetime(6),                            -- 삭제 유예가 끝나 지워질 시각
    index idx_skeleton_accounts_due (status, purge_after)
);

create table if not exists skeleton_account_roles (
    account_id varchar(40) not null,
    role       varchar(40) not null,
    primary key (account_id, role),
    index idx_skeleton_account_roles_role (role),
    foreign key (account_id) references skeleton_accounts (id) on delete cascade
);

-- subject 는 대소문자를 구분하는 이진 정렬 — 제공자 사용자 id 가 대소문자만 다른 두 사람을 같게 보면 안 된다 (MySQL 기본 정렬은 구분하지 않는다)
create table if not exists skeleton_account_identities (
    id           varchar(40)  primary key,
    account_id   varchar(40)  not null,
    method       varchar(32)  not null,                      -- password | magic_link | google | kakao | ... (문자열 코드)
    subject      varchar(254) character set utf8mb4 collate utf8mb4_bin not null,
    verified     boolean      not null default false,
    secret       varchar(255),                               -- 비밀번호 해시 ({bcrypt}…). 다른 수단은 NULL
    metadata     varchar(2000),
    created_at   datetime(6)  not null,
    last_used_at datetime(6),
    unique key uq_skeleton_account_identities_subject (method, subject),
    index idx_skeleton_account_identities_account (account_id),
    foreign key (account_id) references skeleton_accounts (id) on delete cascade
);

-- 이메일 인증 · 비밀번호 재설정 · 이메일 변경 · 매직 링크 · 삭제 확인이 같이 쓰는 한 번 쓰는 토큰. 원문은 저장하지 않는다 (SHA-256 hex 만)
create table if not exists skeleton_account_tokens (
    token_hash  varchar(64)  primary key,
    purpose     varchar(32)  not null,
    subject     varchar(254) not null,                       -- 이 용도 안에서 토큰 주인 (이메일 또는 계정 id)
    account_id  varchar(40),
    payload     varchar(254),
    created_at  datetime(6)  not null,
    expires_at  datetime(6)  not null,
    consumed_at datetime(6),
    index idx_skeleton_account_tokens_owner (purpose, subject),
    index idx_skeleton_account_tokens_expires (expires_at)
);

-- 인증 사건 기록 (skeleton.account.audit.enabled=true 일 때만 쓴다). 토큰 · 이메일 · 비밀번호는 싣지 않는다
create table if not exists skeleton_account_audit (
    id         bigint auto_increment primary key,
    at         datetime(6)  not null,
    type       varchar(40)  not null,
    account_id varchar(40),
    ip         varchar(64),
    detail     varchar(1000),
    index idx_skeleton_account_audit_account (account_id, at),
    index idx_skeleton_account_audit_at (at)
);
