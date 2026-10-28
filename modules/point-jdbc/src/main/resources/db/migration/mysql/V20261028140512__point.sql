-- modules:point-jdbc — 포인트 원장 (mysql). PostgreSQL 판과 같은 뜻. 설계: docs/modules/point-jdbc.md
-- 계정 id · 출처 키는 이진 정렬이다 — MySQL 기본 정렬은 대소문자를 같게 봐서 대소문자만 다른 두 키가 한 줄로 합쳐진다.
-- collate 는 jOOQ DDLDatabase 가 못 읽는 절이라 마커로 가린다 (docs/persistence-jooq.md). 인라인 index 도 같은 마커 안에 둔다.
create table if not exists point_balances (
    account_id varchar(128) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ primary key,
    balance    bigint        not null,
    updated_at datetime(6)   not null,
    constraint ck_point_balances_nonneg check (balance >= 0)
);

create table if not exists point_entries (
    id          varchar(40)  /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ primary key,
    account_id  varchar(128) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    delta       bigint        not null,
    kind        varchar(16)   not null,
    remaining   bigint        not null default 0,
    expires_at  datetime(6),
    source_type varchar(32)  /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    source_ref  varchar(200) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    reason      varchar(200),
    created_at  datetime(6)   not null,
    constraint uq_point_entries_source unique (source_type, source_ref),
    constraint ck_point_entries_remaining check (remaining >= 0)
    /* [jooq ignore start] */,
    index idx_point_entries_account (account_id, created_at, id),
    index idx_point_entries_expires (expires_at)
    /* [jooq ignore stop] */
);
