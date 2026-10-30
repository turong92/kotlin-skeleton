-- modules:campaign-jdbc — 초대 보류 · 운영자 확인 · 취소와 같은 네트워크 표시 (PostgreSQL). 동결된 V20261028152200__campaigns.sql 을 고치지 않고 열만 더한다.
-- 상태(status)는 문자열이라 REVIEW · HELD · CANCELED 를 더해도 스키마가 바뀌지 않는다. 새 열은 모두 비어 있을 수 있다 - 기존 줄은 그대로 읽힌다.
alter table campaign_referrals add column qualify_event varchar(64);       -- 초대자 보상을 정한 신호의 이름
alter table campaign_referrals add column release_at timestamptz;          -- 보류가 끝나는 때 (HELD)
alter table campaign_referrals add column settled_at timestamptz;          -- QUALIFIED · CAPPED · CANCELED 가 된 때
alter table campaign_referrals add column settled_by varchar(64);          -- 승인 · 취소한 운영자 id, 앱이면 'app', 자동이면 NULL
alter table campaign_referrals add column cancel_reason varchar(200);
alter table campaign_referrals add column flags varchar(64);               -- 쉼표 목록 - 지금은 SAME_NETWORK 하나
alter table campaign_referrals add column claim_network varchar(64);       -- claim 때의 네트워크 키. 30일 뒤 해제 작업이 NULL 로 지운다 (판정 결과는 flags 로만 남는다)
create index if not exists idx_campaign_referrals_release on campaign_referrals (status, release_at);

-- 초대자가 GET /referrals/me 를 부른 네트워크 - 30일 뒤 해제 작업이 NULL 로 지운다
alter table campaign_referral_codes add column last_network varchar(64);
alter table campaign_referral_codes add column last_seen_at timestamptz;
