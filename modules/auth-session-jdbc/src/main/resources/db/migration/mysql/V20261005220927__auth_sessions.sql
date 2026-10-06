-- modules:auth-session-jdbc — 로그인 세션 · 리프레시 토큰. 토큰 원문은 저장하지 않는다 (SHA-256 hex 만). 설계: docs/modules/auth-session.md
create table if not exists auth_sessions (
    id              varchar(40)  primary key,
    account_id      varchar(64)  not null,
    device_name     varchar(80),
    user_agent      varchar(255),
    ip              varchar(64),
    created_at      datetime(6)  not null,
    last_used_at    datetime(6)  not null,
    expires_at      datetime(6)  not null,
    revoked_at      datetime(6),
    revoked_reason  varchar(40)
    /* [jooq ignore start] */,
    index idx_auth_sessions_account (account_id, created_at)
    /* [jooq ignore stop] */
);

-- 세션 하나가 새로고침마다 토큰 행을 하나씩 얻는다. used_at 이 비어 있는 행이 "지금 쓸 수 있는 토큰", 채워진 행은 재사용 탐지용 기억이다
create table if not exists auth_refresh_tokens (
    token_hash  varchar(64)  primary key,
    session_id  varchar(40)  not null,
    created_at  datetime(6)  not null,
    used_at     datetime(6)
    /* [jooq ignore start] */,
    index idx_auth_refresh_tokens_session (session_id, used_at),
    foreign key (session_id) references auth_sessions (id) on delete cascade
    /* [jooq ignore stop] */
);
