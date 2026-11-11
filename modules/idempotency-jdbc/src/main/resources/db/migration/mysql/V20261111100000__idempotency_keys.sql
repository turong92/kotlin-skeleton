-- modules:idempotency-jdbc — 멱등 키 저장 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists idempotency_keys (
    -- 키는 대소문자 · 악센트를 구분한다(PostgreSQL 과 같게) - MySQL 기본 정렬은 구분하지 않아 서로 다른 키를 같게 본다. collate 는 jOOQ 마커로 가린다
    scoped_key   varchar(400) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ primary key,
    fingerprint  varchar(128) not null,
    state        varchar(16)  not null,
    claim        varchar(40)  not null,
    status       int,
    content_type varchar(255),
    headers      text,
    body         longblob,
    expires_at   datetime(6)  not null,
    created_at   datetime(6)  not null
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_idempotency_keys_expires (expires_at)
    /* [jooq ignore stop] */
);
