-- modules:video-jdbc — 움직이는 GIF · WebP 의 표시본 변환 일 (PostgreSQL). 설계: docs/modules/video.md 「움직이는 이미지 표시본」
-- state: QUEUED | RUNNING | DONE | FAILED (varchar — 열거값을 마이그레이션에 못 박지 않는다). key_hash = 이미지 모듈 상태 줄(storage_images)의 열쇠 — 외래 키는 두지 않는다(모듈끼리 기대지 않는다).
-- 실패하거나 끝나지 않아도 이미지는 사본 display 로 계속 쓰인다 — 이 표는 일꾼에게 일을 나눠 줄 뿐이다.
create table if not exists video_display_jobs (
    key_hash    char(64)       primary key,
    object_key  varchar(1024)  not null,
    state       varchar(8)     not null,
    attempts    int            not null default 0,
    lease_owner varchar(64),
    lease_until timestamptz,
    fail_code   varchar(64),
    created_at  timestamptz    not null,
    updated_at  timestamptz    not null
);
create index if not exists idx_video_display_jobs_state on video_display_jobs (state, created_at);
