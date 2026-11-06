-- point_operations (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:point-jdbc — PostgreSQL 판과 같은 뜻: 포인트 작업 기록(영수증 + 시도). 칸에는 토큰 · 결제 키 · 본문 · 사유가 없다. FK 없음.
-- 키 칸은 이진 정렬이다(point_entries 와 같다 - 대소문자만 다른 두 키가 한 작업으로 합쳐지지 않게). collate 는 jOOQ DDLDatabase 가 못 읽는 절이라 마커로 가린다.
create table if not exists point_operations (
    operation_id        varchar(40)  /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ primary key,
    account_id          varchar(128) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    kind                varchar(16)  not null,
    amount              bigint,
    source_type         varchar(32)  /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    source_ref          varchar(200) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    fingerprint         varchar(64)  not null,
    actor               varchar(64)  not null,
    status              varchar(16)  not null,
    origin_operation_id varchar(40)  /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */,
    compensation        varchar(16)  not null default 'NONE',
    result_entry_id     varchar(40)  /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */,
    result_code         varchar(64),
    attempts            integer      not null default 0,
    accepted_at         datetime(6)  not null,
    last_attempt_at     datetime(6),
    finished_at         datetime(6),
    constraint uq_point_operations_source unique (source_type, source_ref)
    /* [jooq ignore start] */,
    index idx_point_operations_account (account_id, accepted_at)
    /* [jooq ignore stop] */
);

create table if not exists point_operation_attempts (
    operation_id varchar(40) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    n            integer     not null,
    started_at   datetime(6) not null,
    finished_at  datetime(6),
    outcome      varchar(16),
    error_code   varchar(64),
    primary key (operation_id, n)
);
