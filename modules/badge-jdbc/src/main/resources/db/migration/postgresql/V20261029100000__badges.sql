-- modules:badge-jdbc — 배지 정의 · 보유 · 수여/회수 내역 (PostgreSQL). 설계: docs/modules/badge.md · docs/superpowers/specs/2026-10-29-badges-design.md §3
-- 시각은 timestamptz (UTC).

-- 정의 - code 는 앱 코드와 보상 설정이 부르는 안정된 이름([a-z0-9._-], 만든 뒤 바꾸지 않는다). 그림은 주소 문자열 하나뿐이다
create table if not exists badge_definitions (
    id           varchar(40)  primary key,
    code         varchar(64)  not null,
    name         varchar(100) not null,
    description  varchar(500) not null,
    image_url    varchar(500),
    hidden       boolean      not null default false,   -- 받기 전에는 이름 · 설명 · 그림을 가린다
    sort_order   int          not null default 0,
    status       varchar(12)  not null,                 -- ACTIVE | ARCHIVED
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null,
    constraint uq_badge_definitions_code unique (code)
);

-- 지금 보유 상태 한 줄. 회수한 뒤 다시 받으면 같은 줄이 ACTIVE 로 돌아온다. visible = 본인이 프로필에 보일지
create table if not exists badge_awards (
    id          varchar(40) primary key,
    badge_id    varchar(40) not null,
    account_id  varchar(64) not null,
    status      varchar(8)  not null,                   -- ACTIVE | REVOKED
    visible     boolean     not null default true,
    awarded_at  timestamptz not null,
    revoked_at  timestamptz,
    constraint uq_badge_awards_badge_account unique (badge_id, account_id)
);
create index if not exists idx_badge_awards_account on badge_awards (account_id, status);

-- 수여 · 회수 내역 - 덧붙이기만. (AWARDED, 출처, 출처 키) 유니크가 같은 원인의 재시도를 한 번으로 접는다 (출처 키가 null 인 줄은 겹쳐도 된다)
create table if not exists badge_award_events (
    id           varchar(40)  primary key,
    award_id     varchar(40)  not null,
    badge_id     varchar(40)  not null,
    account_id   varchar(64)  not null,
    action       varchar(8)   not null,                 -- AWARDED | REVOKED
    source_type  varchar(8)   not null,                 -- APP | ADMIN | GRANT
    source_ref   varchar(200),                          -- 지급 건 id · <계정>:<코드>:<앱이 준 키> · 운영자/회수는 null
    actor_id     varchar(64),
    reason       varchar(200),
    created_at   timestamptz  not null,
    constraint uq_badge_award_events_source unique (action, source_type, source_ref)
);
create index if not exists idx_badge_award_events_badge on badge_award_events (badge_id, id);
create index if not exists idx_badge_award_events_account on badge_award_events (account_id, id);
