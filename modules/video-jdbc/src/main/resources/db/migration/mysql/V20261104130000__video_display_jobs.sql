-- modules:video-jdbc — 움직이는 GIF · WebP 의 표시본 변환 일 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists video_display_jobs (
    key_hash    char(64)       not null primary key,
    object_key  varchar(1024)  not null,
    state       varchar(8)     not null,
    attempts    int            not null default 0,
    lease_owner varchar(64),
    lease_until datetime(6),
    fail_code   varchar(64),
    created_at  datetime(6)    not null,
    updated_at  datetime(6)    not null
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_video_display_jobs_state (state, created_at)
    /* [jooq ignore stop] */
);
