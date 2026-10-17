-- board_moderation (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)

-- 게시판마다 승인 규칙: OFF(기본 — 기존 동작) | GUESTS(비회원만) | ALL(운영자 외 모두). 글 주인이 자기 글의 댓글을 승인 · 가린다(owner_moderates_comments)
alter table boards add column if not exists post_approval varchar(16) not null default 'OFF';
alter table boards add column if not exists comment_approval varchar(16) not null default 'OFF';
alter table boards add column if not exists owner_moderates_comments boolean not null default false;

-- 글마다 댓글 승인 규칙(NULL = 게시판 값). moderated_by = 마지막으로 상태를 바꾼 쪽(OWNER | MODERATOR) — 글 주인이 운영자가 가린 댓글을 되살리지 못하게 한다
alter table board_posts    add column if not exists comment_approval varchar(16);
alter table board_posts    add column if not exists moderated_by varchar(16);
alter table board_comments add column if not exists moderated_by varchar(16);

-- 승인 대기 목록: where post_id = ? and status = 'PENDING' order by created_at, id
create index if not exists idx_board_comments_status on board_comments (post_id, status, created_at, id);
