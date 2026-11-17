-- ads_web_rewarded (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:ads-web-rewarded-jdbc — 웹 보상형 광고 세션과 하루 보상 카운터. FK 없음(계정 삭제 고리가 같이 지운다).

-- 세션: 열 때 한 줄, 보상 요청이 granted_at 을 한 번 채운다(잠금 읽기 + 조건부 갱신). 토큰 모드의 토큰은 해시만 둔다(원문은 응답 한 번뿐).
create table if not exists ads_web_rewarded_sessions (
    id               varchar(40)  primary key,                -- wrs_ + ULID
    account_id       varchar(128) not null,
    purpose          varchar(64)  not null,
    client_key       varchar(64)  not null,                   -- IP 상한의 키(ClientIps.limitKey, IPv6 는 /64)
    opened_at        timestamptz  not null,
    expires_at       timestamptz  not null,
    granted_at       timestamptz,                             -- 보상한 시각. 보상 전에는 NULL
    reward_type      varchar(32),                             -- 지급기 종류. 토큰 모드면 NULL
    reward_ref       varchar(200),                            -- 지급기가 돌려준 결과 id. 토큰 모드면 NULL
    token_hash       varchar(64),                             -- 토큰 모드: SHA-256 hex
    token_expires_at timestamptz,
    redeemed_at      timestamptz,                             -- 토큰을 쓴 시각
    constraint uq_ads_web_rewarded_sessions_token unique (token_hash)
);
create index if not exists idx_ads_web_rewarded_sessions_account on ads_web_rewarded_sessions (account_id);   -- 계정 삭제

-- 하루 보상 카운터: scope = ACCOUNT | IP, subject = 계정 id | IP 키, day_start = 「하루」의 시작(day-zone 자정)
create table if not exists ads_web_rewarded_daily_counts (
    scope     varchar(16)  not null,
    subject   varchar(128) not null,
    day_start timestamptz  not null,
    granted   bigint       not null default 0,
    primary key (scope, subject, day_start),
    constraint ck_ads_web_rewarded_daily_counts_granted check (granted >= 0)
);
