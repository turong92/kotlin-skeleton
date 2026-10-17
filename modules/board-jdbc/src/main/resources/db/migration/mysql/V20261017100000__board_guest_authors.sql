-- board_guest_authors (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- MySQL 8.4 에는 `add column if not exists` 가 없다 — Flyway 가 한 번만 적용한다 (schema.sql 모드는 docs/job-queue-jdbc.md 의 같은 주의 참고)

-- 비회원 글 · 댓글: author_id 는 `guest:<ULID>`(행마다 새 값, 계정 id 와 겹치지 않는다). 이름은 행에 두고, 비밀은 해시만 둔다 — 해시는 모델 · 응답에 실리지 않는다.
alter table board_posts    add column guest_name varchar(64);
alter table board_posts    add column guest_secret_hash varchar(255);
alter table board_comments add column guest_name varchar(64);
alter table board_comments add column guest_secret_hash varchar(255);

-- 게시판마다 비회원 쓰기: NONE(기본 — 기존 동작) | COMMENTS | ALL
alter table boards add column guest_write varchar(16) not null default 'NONE';
