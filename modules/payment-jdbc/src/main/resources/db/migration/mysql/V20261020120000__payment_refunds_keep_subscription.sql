-- modules:payment-jdbc — 환불 줄에 「구독은 그대로 둔다」 선택 (mysql)
-- 동결된 V20261009124513__payment_ledger.sql 을 고치지 않고 열 하나를 더한다. 운영자가 「구독도 해지」를 끄고 보낸 환불이면 true — 그 줄이 주문을 전액 환불로 만들어도 구독을 끝내지 않는다.
-- 기본값이 있어 옛 줄은 false(지금까지의 동작: 전액 환불이면 구독을 끝낸다).
alter table payment_refunds add column keep_subscription boolean not null default false;
