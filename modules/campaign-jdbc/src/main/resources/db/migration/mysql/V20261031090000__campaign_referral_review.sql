-- modules:campaign-jdbc — 초대 보류 · 운영자 확인 · 취소와 같은 네트워크 표시 (MySQL). PostgreSQL 판과 같은 뜻.
alter table campaign_referrals add column qualify_event varchar(64);
alter table campaign_referrals add column release_at datetime(6);
alter table campaign_referrals add column settled_at datetime(6);
alter table campaign_referrals add column settled_by varchar(64);
alter table campaign_referrals add column cancel_reason varchar(200);
alter table campaign_referrals add column flags varchar(64);
alter table campaign_referrals add column claim_network varchar(64);
create index idx_campaign_referrals_release on campaign_referrals (status, release_at);

alter table campaign_referral_codes add column last_network varchar(64);
alter table campaign_referral_codes add column last_seen_at datetime(6);
