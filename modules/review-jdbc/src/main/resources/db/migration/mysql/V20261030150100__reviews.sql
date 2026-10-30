-- modules:review-jdbc — 리뷰 · 대상별 집계 · 도움돼요 · 신고 · 구매 신호 · 운영자 답글 · 사진 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists reviews (
    id                   varchar(40)   primary key,
    target_type          varchar(32)   not null,
    target_id            varchar(64)   not null,
    account_id           varchar(64)   not null,
    rating               smallint      not null,
    body                 varchar(2000) not null,
    status               varchar(10)   not null,
    verified             boolean       not null default false,
    helpful_count        int           not null default 0,
    report_count         int           not null default 0,
    reported_at          datetime(6),
    reports_reviewed_at  datetime(6),
    moderated_by         varchar(64),
    moderation_reason    varchar(200),
    photo_count          smallint      not null default 0,
    created_at           datetime(6)   not null,
    updated_at           datetime(6)   not null,
    published_at         datetime(6)   not null,
    constraint uq_reviews_target_account unique (target_type, target_id, account_id)
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_reviews_target_status (target_type, target_id, status, published_at),
    index idx_reviews_account (account_id)
    /* [jooq ignore stop] */
);

create table if not exists review_target_stats (
    target_type   varchar(32) not null,
    target_id     varchar(64) not null,
    review_count  int         not null default 0,
    rating_sum    int         not null default 0,
    r1            int         not null default 0,
    r2            int         not null default 0,
    r3            int         not null default 0,
    r4            int         not null default 0,
    r5            int         not null default 0,
    updated_at    datetime(6) not null,
    primary key (target_type, target_id)
);

create table if not exists review_helpful (
    review_id   varchar(40) not null,
    account_id  varchar(64) not null,
    created_at  datetime(6) not null,
    primary key (review_id, account_id)
    /* [jooq ignore start] */,
    index idx_review_helpful_account (account_id)
    /* [jooq ignore stop] */
);

create table if not exists review_reports (
    review_id   varchar(40)  not null,
    account_id  varchar(64)  not null,
    reason      varchar(32)  not null,
    detail      varchar(500),
    created_at  datetime(6)  not null,
    primary key (review_id, account_id)
    /* [jooq ignore start] */,
    index idx_review_reports_account (account_id)
    /* [jooq ignore stop] */
);

create table if not exists review_purchases (
    id           varchar(40)  primary key,
    account_id   varchar(64)  not null,
    target_type  varchar(32)  not null,
    target_id    varchar(64)  not null,
    ref          varchar(200) not null,
    created_at   datetime(6)  not null,
    revoked_at   datetime(6),
    constraint uq_review_purchases_ref unique (ref)
    /* [jooq ignore start] */,
    index idx_review_purchases_account (account_id, target_type, target_id)
    /* [jooq ignore stop] */
);

create table if not exists review_replies (
    review_id   varchar(40)   primary key,
    body        varchar(2000) not null,
    actor_id    varchar(64)   not null,
    created_at  datetime(6)   not null,
    updated_at  datetime(6)   not null
);

create table if not exists review_photos (
    review_id    varchar(40)  not null,
    idx          smallint     not null,
    storage_key  varchar(500) not null,
    primary key (review_id, idx)
);
