-- board_author_ref (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)

-- 공개 작성자 id. NULL = 이 기능 이전의 행 (공개 id = author_id). author_id(계정 id · 탈퇴 뒤 톰스톤)의 뜻은 그대로다.
alter table board_posts    add column author_ref varchar(64);
alter table board_comments add column author_ref varchar(64);

-- "아직 안 옮긴 행이 있나"(author_ref is null)를 싸게
create index idx_board_posts_author_ref    on board_posts (author_ref);
create index idx_board_comments_author_ref on board_comments (author_ref);
