-- modules:alert-jdbc — 주인 경보 기록 (MySQL). PostgreSQL 판과 같은 뜻 — 한 행 = (종류 · 키), 접기는 조건부 갱신.
-- 주의: MySQL 의 UPDATE 는 SET 을 왼쪽부터 평가한다 — `last_suppressed = suppressed_count` 가 `suppressed_count = 0` 보다 앞에 있어야 옛 값을 읽는다.
create table if not exists alerts (
    id                bigint        auto_increment primary key,
    kind              varchar(64)   not null,
    alert_key         varchar(80)   not null default '',
    severity          varchar(10)   not null,
    title             varchar(200)  not null,
    detail            varchar(1000) not null default '',
    first_at          datetime(6)   not null,
    occurred_at       datetime(6)   not null,
    sent_at           datetime(6)   not null,
    occurrences       bigint        not null default 1,
    suppressed_count  int           not null default 0,
    last_suppressed   int           not null default 0,
    constraint uq_alerts_kind_key unique (kind, alert_key)
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_alerts_occurred (occurred_at)
    /* [jooq ignore stop] */
);
