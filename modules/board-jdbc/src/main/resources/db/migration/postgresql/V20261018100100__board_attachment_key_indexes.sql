-- board_attachment_key_indexes (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md). 기존 표는 건드리지 않는다.

-- 「이 저장 키가 어딘가에 붙어 있나」를 묻는 길(UploadedKeyUsage 의 고아 회수 · 공개 영역 조정)에 키 색인 - 없으면 첨부 표 전체를 읽는다.
create index if not exists idx_board_post_attachments_key on board_post_attachments (storage_key);
create index if not exists idx_board_comment_attachments_key on board_comment_attachments (storage_key);
create index if not exists idx_board_revision_attachments_key on board_revision_attachments (storage_key);
