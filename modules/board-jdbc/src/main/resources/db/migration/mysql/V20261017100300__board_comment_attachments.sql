-- board_comment_attachments (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- MySQL 8.4 에는 `add column if not exists` 가 없다 — Flyway 가 한 번만 적용한다 (schema.sql 모드는 docs/job-queue-jdbc.md 의 같은 주의 참고)

-- 댓글 첨부: 글 첨부와 같은 모양 — 저장 키 문자열만 들고 업로드는 앱이 한다. 기본은 꺼짐(skeleton.board.comment-max-attachments=0)
create table if not exists board_comment_attachments (
    comment_id  bigint        not null,
    sort_order  int           not null,
    storage_key varchar(1024) not null,
    primary key (comment_id, sort_order)
    /* [jooq ignore start] */,
    foreign key (comment_id) references board_comments (id)
    /* [jooq ignore stop] */
);

alter table board_comments add column attachment_count int not null default 0;
