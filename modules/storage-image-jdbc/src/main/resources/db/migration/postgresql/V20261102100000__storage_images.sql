-- modules:storage-image-jdbc — 이미지 원본 하나당 한 줄의 변환 상태 (PostgreSQL). 설계: docs/superpowers/specs/2026-11-02-files-design.md §3
-- 시각은 timestamptz (UTC). 이미지가 아닌 파일은 줄이 없다. 계정 id 는 줄에도 변환본 키에도 없다.

create table if not exists storage_images (
    key_hash    char(64)      primary key,                -- sha256(원본 키) hex - 긴 키의 유니크 · 변환본 폴더 이름
    object_key  varchar(1024) not null,                   -- 원본 키
    status      varchar(16)   not null,                   -- PENDING | READY | REJECTED | UNSUPPORTED | FAILED
    format      varchar(16),                              -- 판정 형식 (jpeg · png · gif · webp …)
    width       int,                                      -- 회전 반영 뒤 원본 크기
    height      int,
    animated    boolean       not null default false,     -- 움직이는 GIF/WebP
    variants    varchar(400),                             -- {"thumb":{"w":400,"h":300,"ext":"jpg"},"display":{…,"ext":"-"}} ("-" = 원본을 가리킨다)
    reason      varchar(64),                              -- 거절 · 실패 코드 (사용자에게 보일 수 있는 코드만)
    queued      boolean       not null default false,     -- 변환 작업을 넣었는가 - 동시 등록에서 작업을 하나만 넣는 열쇠
    created_at  timestamptz   not null,
    updated_at  timestamptz   not null
);
create index if not exists idx_storage_images_status on storage_images (status, updated_at);
