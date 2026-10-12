-- modules:payment-jdbc — 구독에 구매자 나라 (postgresql)
-- 동결된 V20261009134223__payment_subscriptions.sql 을 고치지 않고 열 하나를 더한다. ISO-3166 alpha-2 대문자 두 글자 — 해지·환불 정책을 고르는 열쇠이고, NULL = 나라 없음(기본 정책).
alter table payment_subscriptions add column country char(2);
