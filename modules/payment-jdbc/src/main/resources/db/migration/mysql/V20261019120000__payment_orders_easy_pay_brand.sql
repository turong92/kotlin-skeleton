-- modules:payment-jdbc — 주문에 간편결제 브랜드 (mysql)
-- 동결된 V20261009124513__payment_ledger.sql 을 고치지 않고 열 하나를 더한다. 간편결제의 브랜드 코드(KAKAOPAY · NAVERPAY · TOSSPAY …, 결제사 중립 대문자) - 토스 경유는 확인 응답의 easyPay.provider 에서, 카카오페이 · 네이버페이 직접은 결제사 자신. NULL = 간편결제가 아니거나 브랜드를 모른다(또는 이 열 이전의 주문).
alter table payment_orders add column easy_pay_brand varchar(24);
