-- board_attachment_key_indexes (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md). 기존 표는 건드리지 않는다.
-- MySQL 8.4 에는 `create index if not exists` 가 없다 — Flyway 가 한 번만 적용한다. 키는 varchar(1024)(utf8mb4 4096바이트)라 색인 한도(3072)를 넘는다 - 앞 191자만 색인한다(접두사 색인).

-- 「이 저장 키가 어딘가에 붙어 있나」를 묻는 길(UploadedKeyUsage 의 고아 회수 · 공개 영역 조정)에 키 색인 - 없으면 첨부 표 전체를 읽는다.
create index idx_board_post_attachments_key on board_post_attachments (storage_key(191));
create index idx_board_comment_attachments_key on board_comment_attachments (storage_key(191));
create index idx_board_revision_attachments_key on board_revision_attachments (storage_key(191));
