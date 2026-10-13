-- modules:payment-jdbc — 주문에 접속 나라 (mysql)
-- 동결된 V20261009124513__payment_ledger.sql 을 고치지 않고 열 하나를 더한다. 서버가 주문을 만들 때 정한 접속 나라(ISO-3166 alpha-2 대문자 두 글자) - NULL = 나라를 정하지 못했다(또는 이 열 이전의 주문).
alter table payment_orders add column country char(2);
