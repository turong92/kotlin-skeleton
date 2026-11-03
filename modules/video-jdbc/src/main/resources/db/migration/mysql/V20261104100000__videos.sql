-- modules:video-jdbc — 영상 (MySQL). PostgreSQL 판과 같은 뜻.
-- id 는 대소문자를 구분해야 한다(base64url) — MySQL 기본 정렬은 구분하지 않으니 이진 정렬로 둔다. collate 는 jOOQ DDLDatabase 가 못 읽는 절이라 마커로 가린다.
create table if not exists videos (
    id                      char(22) /* [jooq ignore start] */ character set ascii collate ascii_bin /* [jooq ignore stop] */ not null primary key,
    owner_account_id        varchar(128)  not null,
    purpose                 varchar(32)   not null,
    visibility              varchar(8)    not null,
    status                  varchar(16)   not null,
    upload_key              varchar(255),
    upload_id               varchar(512),
    upload_bytes            bigint        not null default 0,
    last_upload_activity_at datetime(6)   not null,
    src_container           varchar(64),
    src_codec               varchar(32),
    src_codec_string        varchar(64),
    src_width               int,
    src_height              int,
    src_rotation            int,
    src_duration_ms         bigint,
    src_fps                 decimal(8, 3),
    src_bitrate             bigint,
    src_audio_codec         varchar(32),
    orig_key                varchar(255),
    orig_bytes              bigint,
    orig_sha256             varchar(64),
    compat_status           varchar(16)   not null default 'NONE',
    compat_key              varchar(255),
    compat_bytes            bigint,
    ladder_status           varchar(16)   not null default 'NONE',
    ladder_build            varchar(32),
    ladder_bytes            bigint,
    ladder_rungs            text,
    poster_key              varchar(255),
    poster_w                int,
    poster_h                int,
    progress                smallint,
    attempts                int           not null default 0,
    lease_owner             varchar(64),
    lease_until             datetime(6),
    lane                    varchar(16),
    fail_code               varchar(64),
    fail_detail             varchar(500),
    created_at              datetime(6)   not null,
    updated_at              datetime(6)   not null
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_videos_status_lease (status, lease_until),
    index idx_videos_compat (compat_status),
    index idx_videos_ladder (ladder_status),
    index idx_videos_owner_purpose (owner_account_id, purpose)
    /* [jooq ignore stop] */
);

create table if not exists video_workers (
    id            bigint       not null auto_increment primary key,
    name          varchar(64)  not null,
    token_sha256  varchar(64)  not null,
    lanes         varchar(64)  not null,
    created_at    datetime(6)  not null,
    last_seen_at  datetime(6),
    revoked_at    datetime(6)
    /* [jooq ignore start] */,
    unique index uk_video_workers_token (token_sha256)
    /* [jooq ignore stop] */
);

create table if not exists video_wake_runs (
    id        bigint       not null auto_increment primary key,
    woke_at   datetime(6)  not null,
    ok        boolean      not null,
    execution varchar(200),
    error     varchar(200)
    /* [jooq ignore start] */,
    index ix_video_wake_runs_at (woke_at)
    /* [jooq ignore stop] */
);
