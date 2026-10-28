-- modules:catalog-jdbc — 상품 · 가격 카탈로그 (PostgreSQL). 설계: docs/modules/catalog.md · docs/superpowers/specs/2026-10-29-catalog-pricing-design.md §3
-- 시각은 timestamptz, 금액은 bigint 통화 최소 단위(KRW 원 · USD 센트). JSON 열 · 부분 인덱스 없음 (payment-jdbc 와 같다).

-- 파는 것 하나. kind = SUBSCRIPTION | ONE_TIME (만든 뒤 못 바꾼다). level 은 구독 상품만, 유니크(NULL 은 여럿 가능)
create table if not exists catalog_products (
    id          varchar(40)  primary key,
    code        varchar(64)  not null,
    kind        varchar(16)  not null,
    level       int,
    highlighted boolean      not null default false,
    selling     boolean      not null default true,
    sort_order  int          not null default 0,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    constraint uq_catalog_products_code unique (code),
    constraint uq_catalog_products_level unique (level)
);

-- 언어별 이름 · 설명
create table if not exists catalog_product_texts (
    product_id  varchar(40)  not null,
    locale      varchar(16)  not null,
    name        varchar(100) not null,
    description varchar(500),
    primary key (product_id, locale)
);

-- 가격 한 줄 - 만든 뒤 금액 · 통화 · 주기 · 정가를 고치지 않는다(새 줄 + 판매 중지). code 가 구독 줄의 plan_code · 단건의 item_ref · 결제사 링크의 열쇠다.
-- period = ISO-8601 (구독만), compare_at_amount = 정가, sale_starts_at / sale_ends_at 둘 다 없으면 상시 가격(끝은 열린 구간)
create table if not exists catalog_prices (
    id                varchar(40)  primary key,
    code              varchar(64)  not null,
    product_id        varchar(40)  not null,
    period            varchar(32),
    amount            bigint       not null,
    currency          char(3)      not null,
    compare_at_amount bigint,
    sale_starts_at    timestamptz,
    sale_ends_at      timestamptz,
    selling           boolean      not null default true,
    stopped_at        timestamptz,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    constraint uq_catalog_prices_code unique (code)
);
create index if not exists idx_catalog_prices_product on catalog_prices (product_id);

-- 결제사 가격 id (Paddle pri_…) 링크 - 가격 하나에 결제사마다 하나, 같은 결제사 가격 id 는 한 가격에만
create table if not exists catalog_price_provider_refs (
    price_id    varchar(40)  not null,
    provider    varchar(32)  not null,
    external_id varchar(128) not null,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    primary key (price_id, provider),
    constraint uq_catalog_price_provider_refs_external unique (provider, external_id)
);
