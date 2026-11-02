-- modules:storage-image-jdbc — 이미지 원본 하나당 한 줄의 변환 상태 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists storage_images (
    key_hash    char(64)      primary key,
    object_key  varchar(1024) not null,
    status      varchar(16)   not null,
    format      varchar(16),
    width       int,
    height      int,
    animated    boolean       not null default false,
    variants    varchar(400),
    reason      varchar(64),
    queued      boolean       not null default false,
    created_at  datetime(6)   not null,
    updated_at  datetime(6)   not null
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_storage_images_status (status, updated_at)
    /* [jooq ignore stop] */
);
