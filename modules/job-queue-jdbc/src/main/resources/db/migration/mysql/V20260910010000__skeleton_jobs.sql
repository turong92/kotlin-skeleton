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
    updated_at    datetime(6)   not null
    -- 인라인 index 는 MySQL 문법이라 jOOQ DDLDatabase(H2 파서) 가 못 읽는다. schema.sql 로 복사해 코드 생성에 쓸 때를 위해
    -- [jooq ignore] 마커로 감싼다 (parseIgnoreComments=true 일 때만 건너뜀). 앞의 쉼표까지 안에 넣어야 파서가 `not null,)` 를 안 만난다.
    -- 별도 `create index` 로 빼지 않는 이유: MySQL 8.4 엔 `create index if not exists` 가 없어 schema.sql 재실행(매 기동)에서 깨진다.
    /* [jooq ignore start] */,
    index idx_skeleton_jobs_claim (status, next_run_at),
    index idx_skeleton_jobs_running (status, locked_at)
    /* [jooq ignore stop] */
);
