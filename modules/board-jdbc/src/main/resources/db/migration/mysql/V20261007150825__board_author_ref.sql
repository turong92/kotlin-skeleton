-- board_author_ref (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- MySQL 8.4 에는 `add column if not exists` · `create index if not exists` 가 없다 — Flyway 가 한 번만 적용한다 (schema.sql 모드는 docs/job-queue-jdbc.md 의 같은 주의 참고)

-- 공개 작성자 id. NULL = 이 기능 이전의 행 (공개 id = author_id). author_id(계정 id · 탈퇴 뒤 톰스톤)의 뜻은 그대로다.
alter table board_posts    add column author_ref varchar(64);
alter table board_comments add column author_ref varchar(64);

-- "아직 안 옮긴 행이 있나"(author_ref is null)를 싸게: pendingAccounts 는 (author_ref is null → author_id) 를, adopt 는 (author_ref is null and author_id = :계정) 를 이 인덱스로 읽는다.
-- 댓글 표에는 author_id 인덱스가 없고 켠 직후에는 전부 NULL 이라 열 하나짜리로는 부족하다.
create index idx_board_posts_author_ref    on board_posts (author_ref, author_id);
create index idx_board_comments_author_ref on board_comments (author_ref, author_id);
