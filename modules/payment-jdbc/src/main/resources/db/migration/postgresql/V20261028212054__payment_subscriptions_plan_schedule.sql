-- modules:payment-jdbc — 구독의 플랜 갈아타기 넓히기: 주기를 바꾸는 변경이 도는 동안의 새 주기와 다음 갱신에 적용할 예약 (postgresql)
-- pending_period: 주기를 늘리는 변경 결제(월 → 연)가 확인되는 동안의 새 주기 - 결제가 확정되면 오늘부터 새 주기가 시작된다.
-- scheduled_*: 등급 내리기 · 주기 줄이기의 예약 - 갱신 작업이 기간 끝에 같은 트랜잭션에서 플랜 · 금액 · 주기를 바꾼 뒤 새 금액으로 과금한다(옛 줄은 NULL = 예약 없음).
alter table payment_subscriptions add column pending_period varchar(32);
alter table payment_subscriptions add column scheduled_plan_code varchar(64);
alter table payment_subscriptions add column scheduled_amount bigint;
alter table payment_subscriptions add column scheduled_period varchar(32);
