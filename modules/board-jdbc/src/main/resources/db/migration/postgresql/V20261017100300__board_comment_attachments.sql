-- board_comment_attachments (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)

-- 댓글 첨부: 글 첨부와 같은 모양 — 저장 키 문자열만 들고 업로드는 앱이 한다. 기본은 꺼짐(skeleton.board.comment-max-attachments=0)
create table if not exists board_comment_attachments (
    comment_id  bigint        not null references board_comments (id),
    sort_order  int           not null,
    storage_key varchar(1024) not null,
    primary key (comment_id, sort_order)
);

alter table board_comments add column if not exists attachment_count int not null default 0;
