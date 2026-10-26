-- modules:dashboard-jdbc — 대시보드의 일별 집계와 계정별 화면 설정 (postgresql). 설계: docs/modules/dashboard-jdbc.md
-- 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- 표 둘을 한 번에 만든다 — 잠근 뒤에는 고칠 수 없다. 집계 숫자만 담는다: IP · 이메일 · 계정 id 같은 개인을 알아볼 수 있는 값은 일별 표에 없다.

-- 외부 출처(Cloudflare · GA …)의 하루치. 같은 (날짜, 출처, 종류, 차원)은 한 줄 — 다시 적재하면 덮어쓴다.
create table if not exists dashboard_daily (
    day        date          not null,                -- 대시보드 시간대의 날짜
    source     varchar(32)   not null,                -- cloudflare | ga | …
    kind       varchar(32)   not null,                -- visits | pageviews | referrer | path | country | device | channel …
    dimension  varchar(200)  not null default '',     -- 차원 값 (유입 도메인 · 쿼리를 뗀 경로 · 국가 …). 전체 값은 빈 문자열
    value      bigint        not null,
    updated_at timestamptz   not null,                -- 마지막 적재 시각
    primary key (day, source, kind, dimension)
);

-- 계정 하나의 화면 설정(꺼진 카드 · 순서, JSON) 한 줄. 계정이 지워질 때 같이 지운다 (AccountErasureListener)
create table if not exists dashboard_layouts (
    account_id varchar(128)  primary key,
    layout     varchar(4000) not null,
    updated_at timestamptz   not null
);
