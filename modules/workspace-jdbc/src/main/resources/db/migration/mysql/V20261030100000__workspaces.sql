-- modules:workspace-jdbc — 워크스페이스 · slug 기록 · 멤버 · 초대 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists workspaces (
    id          varchar(40)  primary key,
    name        varchar(100) not null,
    slug        varchar(40)  not null,
    status      varchar(8)   not null,
    created_by  varchar(64)  not null,
    created_at  datetime(6)  not null,
    updated_at  datetime(6)  not null,
    closed_at   datetime(6),
    constraint uq_workspaces_slug unique (slug)
);

create table if not exists workspace_slug_history (
    slug          varchar(40) primary key,
    workspace_id  varchar(40) not null,
    retired_at    datetime(6) not null
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_workspace_slug_history_workspace (workspace_id)
    /* [jooq ignore stop] */
);

create table if not exists workspace_members (
    workspace_id  varchar(40) not null,
    account_id    varchar(64) not null,
    role          varchar(8)  not null,
    owner_key     varchar(40),
    joined_at     datetime(6) not null,
    invited_by    varchar(64),
    primary key (workspace_id, account_id),
    constraint uq_workspace_members_owner unique (owner_key)
    /* [jooq ignore start] */,
    index idx_workspace_members_account (account_id)
    /* [jooq ignore stop] */
);

create table if not exists workspace_invitations (
    id             varchar(40)  primary key,
    workspace_id   varchar(40)  not null,
    email          varchar(254) not null,
    role           varchar(8)   not null,
    token_hash     char(64)     not null,
    status         varchar(8)   not null,
    pending_key    varchar(300),
    invited_by     varchar(64)  not null,
    expires_at     datetime(6)  not null,
    sent_count     int          not null default 1,
    last_sent_at   datetime(6)  not null,
    accepted_by    varchar(64),
    accepted_at    datetime(6),
    created_at     datetime(6)  not null,
    updated_at     datetime(6)  not null,
    constraint uq_workspace_invitations_token unique (token_hash),
    constraint uq_workspace_invitations_pending unique (pending_key)
    /* [jooq ignore start] */,
    index idx_workspace_invitations_workspace (workspace_id, status)
    /* [jooq ignore stop] */
);
