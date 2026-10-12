-- modules:payment-jdbc — 환불 줄에 「처음 요청한 금액」 (postgresql)
-- 동결된 V20261009124513__payment_ledger.sql 을 고치지 않고 열 하나를 더한다. 운영자가 금액을 고쳐 승인하면 amount 가 승인 금액이 되고 처음 요청한 금액이 여기 남는다 (NULL = 고치지 않음).
alter table payment_refunds add column requested_amount bigint;
