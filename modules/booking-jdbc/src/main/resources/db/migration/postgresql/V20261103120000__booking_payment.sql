-- modules:booking-jdbc — 결제 연결 (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- 앞 마이그레이션(V20261103100000)은 잠겨 있어 새 버전으로 더한다.

-- 결제된 주문이 보류에 어떻게 맞춰졌는가 - 주문 하나에 한 줄. 이 줄이 「주문 하나에 한 번만 효과」 · 「환불 작업은 주문당 하나」의 근거다.
create table if not exists booking_orders (
    order_id   varchar(64)  primary key,                  -- 결제 모듈의 주문 id
    hold_id    varchar(40)  not null,
    outcome    varchar(12)  not null,                     -- CONFIRMED | UNFULFILLED | DUPLICATE
    created_at timestamptz  not null
);

-- 전액 환불된 주문의 보류를 찾는다
create index if not exists idx_booking_holds_order on booking_holds (confirmed_order_id);
