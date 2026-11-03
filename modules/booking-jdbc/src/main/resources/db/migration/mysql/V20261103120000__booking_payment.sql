-- modules:booking-jdbc — 결제 연결 (mysql). PostgreSQL 판과 같은 뜻. 앞 마이그레이션(V20261103100000)은 잠겨 있어 새 버전으로 더한다.
-- MySQL 8.4 에는 `create index if not exists` 가 없다 — Flyway 가 한 번만 적용한다.

create table if not exists booking_orders (
    order_id   varchar(64)  primary key,
    hold_id    varchar(40)  not null,
    outcome    varchar(12)  not null,
    created_at datetime(6)  not null
);

create index idx_booking_holds_order on booking_holds (confirmed_order_id);
