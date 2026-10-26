-- modules:dashboard-jdbc — 대시보드의 일별 집계와 계정별 화면 설정 (mysql). PostgreSQL 판과 같은 뜻.
-- 차원 · 계정 id 는 이진 정렬이다 — MySQL 기본 정렬은 대소문자를 같게 봐서 `Google.com` 과 `google.com` 이 한 줄로 합쳐진다.
-- collate 는 jOOQ DDLDatabase 가 못 읽는 절이라 마커로 가린다 (docs/persistence-jooq.md).
create table if not exists dashboard_daily (
    day        date          not null,
    source     varchar(32)   not null,
    kind       varchar(32)   not null,
    dimension  varchar(200) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null default '',
    value      bigint        not null,
    updated_at datetime(6)   not null,
    primary key (day, source, kind, dimension)
);

create table if not exists dashboard_layouts (
    account_id varchar(128) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ primary key,
    layout     varchar(4000) not null,
    updated_at datetime(6)   not null
);
