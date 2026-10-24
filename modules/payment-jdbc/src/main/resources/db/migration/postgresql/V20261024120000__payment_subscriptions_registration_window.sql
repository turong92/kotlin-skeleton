-- modules:payment-jdbc — 구독의 첫 카드 등록 창 값 (postgresql)
-- 동결된 V20261009134223__payment_subscriptions.sql 을 고치지 않고 열 둘을 더한다. 서버가 결제사에 창을 예약해야 열리는 결제사(카카오페이)의 첫 창 값(JSON, 최대 1000자)과 예약 시각 -
-- 같은 요청 키의 재전송이 결제사를 다시 부르지 않고 같은 창을 돌려받는다(옛 줄 · 다른 결제사는 NULL).
alter table payment_subscriptions add column registration_window varchar(1000);
alter table payment_subscriptions add column registration_window_at timestamptz;
