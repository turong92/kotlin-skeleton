-- modules:job-queue-jdbc — MySQL 테이블 기반 재시도 큐 (Redis 없음, 단일/다중 인스턴스 모두 FOR UPDATE SKIP LOCKED 로 안전)
create table if not exists skeleton_jobs (
    id            bigint auto_increment primary key,
    job_type      varchar(128)  not null,
    payload_json  text          not null,
    status        varchar(16)   not null,            -- PENDING | RUNNING | DONE | DEAD
    attempts      int           not null default 0,
    max_attempts  int           not null,
    next_run_at   datetime(6)   not null,
    locked_by     varchar(128)  null,
    locked_at     datetime(6)   null,
    last_error    text          null,
    created_at    datetime(6)   not null,
    updated_at    datetime(6)   not null,
    index idx_skeleton_jobs_claim (status, next_run_at),
    index idx_skeleton_jobs_running (status, locked_at)
);
