-- ads_web_rewarded (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:ads-web-rewarded-jdbc — PostgreSQL 판과 같은 뜻: 웹 보상형 광고 세션과 하루 보상 카운터.
-- 키 열은 이진 정렬이다(point_entries 와 같다) - collate 는 jOOQ DDLDatabase 가 못 읽어 마커로 가린다. 인라인 index 도 같은 마커 안에 둔다.
create table if not exists ads_web_rewarded_sessions (
    id               varchar(40)  /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ primary key,
    account_id       varchar(128) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    purpose          varchar(64)  /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    client_key       varchar(64)  /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    opened_at        datetime(6)  not null,
    expires_at       datetime(6)  not null,
    granted_at       datetime(6),
    reward_type      varchar(32),
    reward_ref       varchar(200),
    token_hash       varchar(64)  /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */,
    token_expires_at datetime(6),
    redeemed_at      datetime(6),
    constraint uq_ads_web_rewarded_sessions_token unique (token_hash)
    /* [jooq ignore start] */,
    index idx_ads_web_rewarded_sessions_account (account_id)
    /* [jooq ignore stop] */
);

create table if not exists ads_web_rewarded_daily_counts (
    scope     varchar(16)  not null,
    subject   varchar(128) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    day_start datetime(6)  not null,
    granted   bigint       not null default 0,
    primary key (scope, subject, day_start),
    constraint ck_ads_web_rewarded_daily_counts_granted check (granted >= 0)
);
