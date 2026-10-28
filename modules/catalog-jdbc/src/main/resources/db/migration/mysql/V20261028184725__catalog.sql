-- modules:catalog-jdbc — 상품 · 가격 카탈로그 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists catalog_products (
    id          varchar(40)  primary key,
    code        varchar(64)  not null,
    kind        varchar(16)  not null,
    level       int,
    highlighted boolean      not null default false,
    selling     boolean      not null default true,
    sort_order  int          not null default 0,
    created_at  datetime(6)  not null,
    updated_at  datetime(6)  not null,
    constraint uq_catalog_products_code unique (code),
    constraint uq_catalog_products_level unique (level)
);

create table if not exists catalog_product_texts (
    product_id  varchar(40)  not null,
    locale      varchar(16)  not null,
    name        varchar(100) not null,
    description varchar(500),
    primary key (product_id, locale)
);

create table if not exists catalog_prices (
    id                varchar(40)  primary key,
    code              varchar(64)  not null,
    product_id        varchar(40)  not null,
    period            varchar(32),
    amount            bigint       not null,
    currency          char(3)      not null,
    compare_at_amount bigint,
    sale_starts_at    datetime(6),
    sale_ends_at      datetime(6),
    selling           boolean      not null default true,
    stopped_at        datetime(6),
    created_at        datetime(6)  not null,
    updated_at        datetime(6)  not null,
    constraint uq_catalog_prices_code unique (code)
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_catalog_prices_product (product_id)
    /* [jooq ignore stop] */
);

create table if not exists catalog_price_provider_refs (
    price_id    varchar(40)  not null,
    provider    varchar(32)  not null,
    external_id varchar(128) not null,
    created_at  datetime(6)  not null,
    updated_at  datetime(6)  not null,
    primary key (price_id, provider),
    constraint uq_catalog_price_provider_refs_external unique (provider, external_id)
);
