-- board_revisions (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md). 기존 표는 건드리지 않는다.

-- 승인 대상 작성자가 공개된 글 · 댓글을 고치면 공개본은 그대로 두고 고친 내용을 여기에 둔다 — 대상마다 판 하나(기본 키), 판이 있으면 대기 중이다.
create table if not exists board_revisions (
    target_type      varchar(16)  not null,              -- POST | COMMENT
    target_id        bigint       not null,
    board_code       varchar(40)  not null,
    post_id          bigint       not null,              -- 글이면 target_id 와 같다
    title            varchar(255),                       -- 글만
    body             text         not null,
    attachment_count int          not null default 0,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    primary key (target_type, target_id)
);
-- 운영자의 고친 글 목록 / 글 주인의 고친 댓글 목록
create index if not exists idx_board_revisions_board on board_revisions (board_code, target_type, updated_at);
create index if not exists idx_board_revisions_post on board_revisions (post_id, target_type, updated_at);

create table if not exists board_revision_attachments (
    target_type varchar(16)   not null,
    target_id   bigint        not null,
    sort_order  int           not null,
    storage_key varchar(1024) not null,
    primary key (target_type, target_id, sort_order)
);
