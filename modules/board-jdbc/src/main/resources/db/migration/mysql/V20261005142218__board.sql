-- modules:board-jdbc — 게시판 (글 · 댓글 트리 · 종류가 있는 반응). 설계: docs/modules/board.md
create table if not exists boards (
    code        varchar(40)  primary key,
    name        varchar(100) not null,
    description varchar(500),
    created_at  datetime(6)  not null
);

create table if not exists board_posts (
    id               bigint auto_increment primary key,
    board_code       varchar(40)  not null,
    author_id        varchar(128) not null,
    title            varchar(255) not null,
    body             mediumtext   not null,               -- text 는 64KB 라 4바이트 글자 2만 자 본문이 안 들어간다
    status           varchar(16)  not null,               -- DRAFT | PUBLISHED | HIDDEN | DELETED (소프트 삭제: 행은 남는다)
    pinned           boolean      not null default false,
    view_count       bigint       not null default 0,
    comment_count    bigint       not null default 0,     -- PUBLISHED 댓글 수. 같은 트랜잭션에서 +1/-1 (원자적 UPDATE)
    reaction_count   bigint       not null default 0,     -- 모든 종류의 반응 합. 종류별 개수는 board_reactions 를 묶어 센다
    attachment_count int          not null default 0,
    created_at       datetime(6)  not null,
    updated_at       datetime(6)  not null
    -- 인라인 index · foreign key 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    foreign key (board_code) references boards (code),
    -- 목록: where board_code = ? and status in (...) order by pinned desc, created_at desc, id desc — 이 인덱스를 거꾸로 훑는다
    index idx_board_posts_latest (board_code, status, pinned, created_at, id),
    index idx_board_posts_by_reactions (board_code, status, pinned, reaction_count, created_at),
    index idx_board_posts_by_comments (board_code, status, pinned, comment_count, created_at),
    index idx_board_posts_author (board_code, author_id, created_at)
    /* [jooq ignore stop] */
);

create table if not exists board_post_attachments (
    post_id     bigint        not null,
    sort_order  int           not null,
    storage_key varchar(1024) not null,
    primary key (post_id, sort_order)
    /* [jooq ignore start] */,
    foreign key (post_id) references board_posts (id)
    /* [jooq ignore stop] */
);

create table if not exists board_comments (
    id             bigint auto_increment primary key,
    post_id        bigint       not null,
    parent_id      bigint,                                -- null = 최상위. (자기 참조 FK 는 두지 않는다 — 서비스가 같은 글의 부모만 허용한다)
    root_id        bigint,                                -- 최상위 댓글의 id. 최상위 자신은 null (모델이 id 로 읽는다)
    depth          int          not null,                 -- 최상위 0. 상한은 skeleton.board.max-comment-depth (서비스가 지킨다)
    author_id      varchar(128) not null,
    body           mediumtext   not null,
    status         varchar(16)  not null,                 -- PUBLISHED | HIDDEN | DELETED (소프트 삭제: 스레드 모양을 남긴다)
    reaction_count bigint       not null default 0,
    created_at     datetime(6)  not null,
    updated_at     datetime(6)  not null
    /* [jooq ignore start] */,
    foreign key (post_id) references board_posts (id),
    -- 최상위 댓글 페이지: where post_id = ? and parent_id is null order by created_at, id
    index idx_board_comments_roots (post_id, parent_id, created_at, id),
    index idx_board_comments_roots_by_reactions (post_id, parent_id, reaction_count, created_at),
    -- 자손 전부 한 쿼리: where root_id in (...) order by created_at, id
    index idx_board_comments_thread (root_id, created_at, id)
    /* [jooq ignore stop] */
);

-- 반응: 종류는 문자열 코드(skeleton.board.reaction.types)라 종류를 늘려도 스키마는 그대로다.
-- 기본 키 = 유니크 키 (대상, 계정, 종류): PER_TYPE 은 이 키 그대로, SINGLE 은 서비스가 같은 트랜잭션에서 다른 종류를 지운다 — 두 모드가 같은 테이블.
create table if not exists board_reactions (
    target_type   varchar(16)  not null,                  -- POST | COMMENT
    target_id     bigint       not null,
    account_id    varchar(128) not null,
    reaction_type varchar(64)  not null,
    created_at    datetime(6)  not null,
    primary key (target_type, target_id, account_id, reaction_type)
    /* [jooq ignore start] */,
    index idx_board_reactions_by_type (target_type, target_id, reaction_type)
    /* [jooq ignore stop] */
);
