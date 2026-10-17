-- board_public_attachments (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md). 기존 표는 건드리지 않는다.

-- 「공개 영역에 복사해 둔 첨부」의 기억 - 저장소의 공개 영역(skeleton.storage.web.public)을 board 상태에 맞추는 조정자가 쓴다.
-- 복사가 있는데 글 · 댓글이 더 이상 공개 조건(PUBLISHED)이 아니면 공개 영역에서 지우고 이 줄도 지운다. 기능이 꺼져 있으면 비어 있다.
create table if not exists board_public_attachments (
    target_type varchar(16)   not null,              -- POST | COMMENT
    target_id   bigint        not null,
    key_hash    char(64)      not null,              -- sha256(storage_key) - 긴 키를 기본 키에 넣지 않으려고
    storage_key varchar(1024) not null,
    created_at  timestamptz   not null,
    primary key (target_type, target_id, key_hash)
);
create index if not exists idx_board_public_attachments_key on board_public_attachments (key_hash);
