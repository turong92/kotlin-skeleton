-- modules:account-jdbc — 계정 · 로그인 수단(identities) · 역할 · 한 번 쓰는 토큰 · 감사 기록. 설계: docs/modules/account.md · docs/accounts.md
-- 계정에는 로그인 수단이 없다 — 수단(비밀번호 · 소셜 · 매직 링크 …)은 identities 의 행이고 method 는 문자열 코드라서 새 수단이 스키마 변경 없이 들어온다.
create table if not exists accounts (
    id               varchar(40)  primary key,
    -- 정규화(소문자). 없을 수 있다(이메일을 안 주는 소셜 가입) — 유니크는 NULL 을 여럿 허용한다.
    -- 이진 정렬: MySQL 기본 정렬은 악센트 · 대소문자를 같게 봐서 `victim@gmäil.com` 이 `victim@gmail.com` 계정을 찾는다 (docs/accounts.md "이메일 정규화").
    -- collate 는 jOOQ DDLDatabase 가 못 읽는 절이라 마커로 가린다 (docs/persistence-jooq.md) — 코드 생성 타입은 그대로 varchar
    email            varchar(254) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ unique,
    email_verified   boolean      not null default false,
    status           varchar(24)  not null,                  -- ACTIVE | PENDING_VERIFICATION | SUSPENDED | DELETED (유예, 복구 가능) | ERASED (개인정보를 지운 뒤 행만 남음)
    display_name     varchar(60),
    locale           varchar(35),
    time_zone        varchar(64),
    created_at       datetime(6)  not null,
    updated_at       datetime(6)  not null,
    last_login_at    datetime(6),
    suspended_reason varchar(200),
    deleted_at       datetime(6),
    purge_after      datetime(6),                            -- 삭제 유예가 끝나 지워질 시각
    erased_at        datetime(6)                             -- 개인정보를 지운 시각 (ERASED). 이때 email · display_name · locale · time_zone · suspended_reason · last_login_at 은 NULL
    /* [jooq ignore start] */,
    index idx_accounts_due (status, purge_after)
    /* [jooq ignore stop] */
);

create table if not exists account_roles (
    account_id varchar(40) not null,
    role       varchar(40) not null,
    primary key (account_id, role)
    /* [jooq ignore start] */,
    index idx_account_roles_role (role),
    foreign key (account_id) references accounts (id) on delete cascade
    /* [jooq ignore stop] */
);

-- subject 는 대소문자를 구분하는 이진 정렬 — 제공자 사용자 id 가 대소문자만 다른 두 사람을 같게 보면 안 된다 (MySQL 기본 정렬은 구분하지 않는다). collate 는 jOOQ 마커로 가린다
create table if not exists account_identities (
    id           varchar(40)  primary key,
    account_id   varchar(40)  not null,
    method       varchar(32)  not null,                      -- password | magic_link | google | kakao | ... (문자열 코드)
    subject      varchar(254) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    verified     boolean      not null default false,
    secret       varchar(255),                               -- 비밀번호 해시 ({bcrypt}…). 다른 수단은 NULL
    metadata     varchar(2000),
    created_at   datetime(6)  not null,
    last_used_at datetime(6)
    /* [jooq ignore start] */,
    unique key uq_account_identities_subject (method, subject),
    index idx_account_identities_account (account_id),
    foreign key (account_id) references accounts (id) on delete cascade
    /* [jooq ignore stop] */
);

-- 이메일 인증 · 비밀번호 재설정 · 이메일 변경 · 매직 링크 · 삭제 확인이 같이 쓰는 한 번 쓰는 토큰. 원문은 저장하지 않는다 (SHA-256 hex 만)
create table if not exists account_tokens (
    token_hash  varchar(64)  primary key,
    purpose     varchar(32)  not null,
    -- 이 용도 안에서 토큰 주인 (이메일 또는 계정 id) — 이진 정렬: 악센트 · 대소문자만 다른 주소가 남의 열린 링크를 닫지 않게
    subject     varchar(254) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    account_id  varchar(40),
    payload     varchar(254),
    created_at  datetime(6)  not null,
    expires_at  datetime(6)  not null,
    consumed_at datetime(6)
    /* [jooq ignore start] */,
    index idx_account_tokens_owner (purpose, subject),
    index idx_account_tokens_expires (expires_at)
    /* [jooq ignore stop] */
);

-- 인증 사건 기록 (skeleton.account.audit.enabled=true 일 때만 쓴다). 토큰 · 이메일 · 비밀번호는 싣지 않는다
create table if not exists account_audit (
    id         bigint auto_increment primary key,
    at         datetime(6)  not null,
    type       varchar(40)  not null,
    account_id varchar(40),
    ip         varchar(64),
    detail     varchar(1000)
    /* [jooq ignore start] */,
    index idx_account_audit_account (account_id, at),
    index idx_account_audit_at (at)
    /* [jooq ignore stop] */
);

-- 운영자가 정지한 계정을 지울 때 남기는 재가입 차단. **해시만** 있다 (서버 비밀의 HMAC-SHA256 — 이메일 · 제공자 주체 원문은 어디에도 없다). 개인정보 보존이므로 처리방침에 적는다 (docs/accounts.md)
create table if not exists account_blocks (
    id         bigint auto_increment primary key,
    kind       varchar(16)  not null,                       -- email | identity
    hash       varchar(64)  not null unique,
    reason     varchar(200),
    created_at datetime(6)  not null,
    expires_at datetime(6),                                          -- NULL = 운영자가 지울 때까지
    created_by varchar(40),
    account_id varchar(40)                                  -- 지워진(ERASED) 계정 행. FK 없음 — DELETE 모드에서도 남는다
    /* [jooq ignore start] */,
    index idx_account_blocks_expires (expires_at)
    /* [jooq ignore stop] */
);

-- 주기 정리를 인스턴스 하나만 하게 하는 짧은 임대 (이름 하나에 한 줄). locked_until 이 지나면 누구나 가져간다
create table if not exists account_locks (
    name         varchar(64) primary key,
    locked_until datetime(6) not null
);
