-- modules:inquiry-jdbc — 문의 · 의견 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists inquiries (
    id           bigint        not null auto_increment primary key,
    kind         varchar(16)   not null,
    body         text          not null,
    reply_email  varchar(254),
    account_id   varchar(128),
    context      varchar(100),
    lang         varchar(16)   not null,
    status       varchar(8)    not null,
    created_at   datetime(6)   not null,
    handled_at   datetime(6)
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_inquiries_status_created (status, created_at),
    index idx_inquiries_account (account_id)
    /* [jooq ignore stop] */
);
