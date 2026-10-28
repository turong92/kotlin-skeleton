-- modules:coupon-jdbc — 발행 기간 + 사용 시작 (MySQL). PostgreSQL 판과 같은 뜻.
alter table coupon_definitions add column issue_starts_at datetime(6);
alter table coupon_definitions add column issue_ends_at   datetime(6);
alter table coupon_definitions add column valid_from      datetime(6);

create index idx_coupons_definition_id on coupons (definition_id, id);
create index idx_coupons_status_used on coupons (status, used_at);
