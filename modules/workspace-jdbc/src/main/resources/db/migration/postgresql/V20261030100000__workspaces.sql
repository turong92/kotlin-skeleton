-- modules:workspace-jdbc — 워크스페이스 · slug 기록 · 멤버 · 초대 (PostgreSQL). 설계: docs/modules/workspace.md · docs/superpowers/specs/2026-10-30-org-design.md §3
-- 시각은 timestamptz (UTC). 초대 표는 초대 조각(workspace 의 초대 흐름)이 쓰지만 좌석 수·계정 삭제 정리가 처음부터 읽으므로 여기서 함께 만든다.

create table if not exists workspaces (
    id          varchar(40)  primary key,
    name        varchar(100) not null,
    slug        varchar(40)  not null,                 -- 화면 주소 이름. 유니크, 바꿀 수 있다
    status      varchar(8)   not null,                 -- ACTIVE | CLOSED
    created_by  varchar(64)  not null,                 -- 계정이 지워지면 묘비 값
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    closed_at   timestamptz,
    constraint uq_workspaces_slug unique (slug)
);

-- 바꾸기 전의 slug - 다른 워크스페이스가 가져갈 수 없다(가로채기 방지). 같은 워크스페이스가 되돌리면 이 줄을 지운다
create table if not exists workspace_slug_history (
    slug          varchar(40) primary key,
    workspace_id  varchar(40) not null,
    retired_at    timestamptz not null
);
create index if not exists idx_workspace_slug_history_workspace on workspace_slug_history (workspace_id);

-- 멤버십. owner_key = OWNER 일 때만 workspace_id → 유니크라서 소유자 둘이 커밋될 수 없다. 「최소 하나」는 모든 변경이 workspaces 줄을 for update 로 잡고 판정해 지킨다
create table if not exists workspace_members (
    workspace_id  varchar(40) not null,
    account_id    varchar(64) not null,
    role          varchar(8)  not null,                -- OWNER | ADMIN | MEMBER
    owner_key     varchar(40),
    joined_at     timestamptz not null,
    invited_by    varchar(64),
    primary key (workspace_id, account_id),
    constraint uq_workspace_members_owner unique (owner_key)
);
create index if not exists idx_workspace_members_account on workspace_members (account_id);

-- 초대. 토큰은 sha256 만 저장한다. pending_key = PENDING 일 때 workspace_id + 줄바꿈 + 이메일 → 같은 주소에 대기 초대는 하나. 만료는 상태가 아니라 expires_at 으로 읽는다
create table if not exists workspace_invitations (
    id             varchar(40)  primary key,
    workspace_id   varchar(40)  not null,
    email          varchar(254) not null,              -- 소문자
    role           varchar(8)   not null,              -- ADMIN | MEMBER
    token_hash     char(64)     not null,
    status         varchar(8)   not null,              -- PENDING | ACCEPTED | REVOKED
    pending_key    varchar(300),
    invited_by     varchar(64)  not null,
    expires_at     timestamptz  not null,
    sent_count     int          not null default 1,
    last_sent_at   timestamptz  not null,
    accepted_by    varchar(64),
    accepted_at    timestamptz,
    created_at     timestamptz  not null,
    updated_at     timestamptz  not null,
    constraint uq_workspace_invitations_token unique (token_hash),
    constraint uq_workspace_invitations_pending unique (pending_key)
);
create index if not exists idx_workspace_invitations_workspace on workspace_invitations (workspace_id, status);
