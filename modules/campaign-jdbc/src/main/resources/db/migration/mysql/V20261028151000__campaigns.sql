-- modules:campaign-jdbc — 이벤트 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists campaigns (
    id           varchar(40)  primary key,
    kind         varchar(32)  not null,
    name         varchar(100) not null,
    enabled      boolean      not null,
    starts_at    datetime(6)  not null,
    ends_at      datetime(6),
    config_json  text         not null,
    created_at   datetime(6)  not null,
    updated_at   datetime(6)  not null
);

create table if not exists campaign_attendance (
    campaign_id  varchar(40)  not null,
    account_id   varchar(64)  not null,
    day          date         not null,
    created_at   datetime(6)  not null,
    primary key (campaign_id, account_id, day)
);

create table if not exists campaign_referral_codes (
    account_id   varchar(64)  primary key,
    code         varchar(16)  not null,
    created_at   datetime(6)  not null,
    constraint uq_campaign_referral_code unique (code)
);

create table if not exists campaign_referrals (
    invitee_id   varchar(64)  primary key,
    inviter_id   varchar(64)  not null,
    campaign_id  varchar(40)  not null,
    status       varchar(16)  not null,
    claimed_at   datetime(6)  not null,
    qualified_at datetime(6)
    -- 인라인 index 는 jOOQ DDLDatabase 가 못 읽는다 → [jooq ignore] 마커 (설명: modules/job-queue-jdbc 의 마이그레이션)
    /* [jooq ignore start] */,
    index idx_campaign_referrals_inviter (inviter_id, campaign_id)
    /* [jooq ignore stop] */
);
