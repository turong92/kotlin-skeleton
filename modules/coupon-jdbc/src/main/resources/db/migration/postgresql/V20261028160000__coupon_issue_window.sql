-- modules:coupon-jdbc — 발행 기간 + 사용 시작 (PostgreSQL). 동결된 V20261028152100__coupons.sql 을 고치지 않고 더한다.
alter table coupon_definitions add column issue_starts_at timestamptz;   -- 발행 기간 [시작, 끝) - 밖이면 운영자 발급 · 코드 등록 · 보상 지급이 거절된다. NULL = 그쪽이 열려 있다
alter table coupon_definitions add column issue_ends_at   timestamptz;
alter table coupon_definitions add column valid_from      timestamptz;   -- 사용 시작 - 이 시각 전에는 결제 할인으로 쓸 수 없다. NULL = 받자마자
