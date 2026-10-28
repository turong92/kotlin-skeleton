-- modules:campaign-jdbc — 이벤트 (PostgreSQL). 표 넷: 이벤트 · 출석 · 초대 코드 · 초대 관계.
create table if not exists campaigns (
    id           varchar(40)  primary key,
    kind         varchar(32)  not null,                 -- attendance · referral
    name         varchar(100) not null,
    enabled      boolean      not null,
    starts_at    timestamptz  not null,
    ends_at      timestamptz,
    config_json  text         not null,                 -- 종류별 설정 + 보상 목록
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null
);

-- 하루 한 번은 이 키가 지킨다
create table if not exists campaign_attendance (
    campaign_id  varchar(40)  not null,
    account_id   varchar(64)  not null,
    day          date         not null,                 -- 서비스 시간대의 날짜
    created_at   timestamptz  not null,
    primary key (campaign_id, account_id, day)
);

-- 계정당 하나, 처음 볼 때 만든다
create table if not exists campaign_referral_codes (
    account_id   varchar(64)  primary key,
    code         varchar(16)  not null,
    created_at   timestamptz  not null,
    constraint uq_campaign_referral_code unique (code)
);

-- 초대받은 사람은 평생 한 번
create table if not exists campaign_referrals (
    invitee_id   varchar(64)  primary key,
    inviter_id   varchar(64)  not null,
    campaign_id  varchar(40)  not null,
    status       varchar(16)  not null,                 -- CLAIMED · QUALIFIED · CAPPED
    claimed_at   timestamptz  not null,
    qualified_at timestamptz
);
create index if not exists idx_campaign_referrals_inviter on campaign_referrals (inviter_id, campaign_id);
