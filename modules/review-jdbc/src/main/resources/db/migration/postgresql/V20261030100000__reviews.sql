-- modules:review-jdbc — 리뷰 · 대상별 집계 · 도움돼요 · 신고 · 구매 신호 · 운영자 답글 · 사진 (PostgreSQL). 설계: docs/modules/review.md · docs/superpowers/specs/2026-10-30-review-search-design.md §A3
-- 시각은 timestamptz (UTC). 신고 · 답글 · 사진 표는 운영 조각이 채운다 - 표는 처음부터 한 벌로 둬서 나중에 마이그레이션을 더하지 않는다.

-- 리뷰 한 줄. 줄은 지워지지 않는다(DELETED 로 바뀔 뿐) - 같은 대상에 다시 쓰면 같은 줄이 되살아난다
create table if not exists reviews (
    id                   varchar(40)   primary key,
    target_type          varchar(32)   not null,
    target_id            varchar(64)   not null,
    account_id           varchar(64)   not null,           -- 탈퇴하면 계정마다 하나인 톰스톤(deleted:…)으로 바뀐다
    rating               smallint      not null,           -- 1..5 (서비스가 검사)
    body                 varchar(2000) not null,
    status               varchar(10)   not null,           -- PUBLISHED | HIDDEN | DELETED
    verified             boolean       not null default false,
    helpful_count        int           not null default 0,
    report_count         int           not null default 0,
    reported_at          timestamptz,
    reports_reviewed_at  timestamptz,
    moderated_by         varchar(64),
    moderation_reason    varchar(200),
    photo_count          smallint      not null default 0,
    created_at           timestamptz   not null,
    updated_at           timestamptz   not null,
    published_at         timestamptz   not null,
    constraint uq_reviews_target_account unique (target_type, target_id, account_id)
);
create index if not exists idx_reviews_target_status on reviews (target_type, target_id, status, published_at);
create index if not exists idx_reviews_account on reviews (account_id);

-- 대상별 집계 - PUBLISHED 만 센다. 원자적 UPDATE 로만 바꾼다
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
    updated_at    timestamptz not null,
    primary key (target_type, target_id)
);

create table if not exists review_helpful (
    review_id   varchar(40) not null,
    account_id  varchar(64) not null,
    created_at  timestamptz not null,
    primary key (review_id, account_id)
);
create index if not exists idx_review_helpful_account on review_helpful (account_id);

create table if not exists review_reports (
    review_id   varchar(40)  not null,
    account_id  varchar(64)  not null,
    reason      varchar(32)  not null,
    detail      varchar(500),
    created_at  timestamptz  not null,
    primary key (review_id, account_id)
);
create index if not exists idx_review_reports_account on review_reports (account_id);

-- 구매 신호(앱이 ReviewPurchases.record 로 넣는다). ref 가 같으면 한 줄
create table if not exists review_purchases (
    id           varchar(40)  primary key,
    account_id   varchar(64)  not null,
    target_type  varchar(32)  not null,
    target_id    varchar(64)  not null,
    ref          varchar(200) not null,
    created_at   timestamptz  not null,
    revoked_at   timestamptz,
    constraint uq_review_purchases_ref unique (ref)
);
create index if not exists idx_review_purchases_account on review_purchases (account_id, target_type, target_id);

-- 운영자 답글 - 리뷰당 하나
create table if not exists review_replies (
    review_id   varchar(40)   primary key,
    body        varchar(2000) not null,
    actor_id    varchar(64)   not null,
    created_at  timestamptz   not null,
    updated_at  timestamptz   not null
);

-- 사진 - 저장 키만 둔다(보여 줄 때 서버가 짧은 서명 주소로 넘긴다)
create table if not exists review_photos (
    review_id    varchar(40)  not null,
    idx          smallint     not null,
    storage_key  varchar(500) not null,
    primary key (review_id, idx)
);
