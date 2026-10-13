-- modules:payment-jdbc — 결제사가 갱신까지 하는 구독 (mysql)
-- 동결된 V20261009134223__payment_subscriptions.sql 을 고치지 않고 열 둘을 더한다. provider_subscription_id 가 있으면 결제사(Paddle)가 갱신을 하고 우리 갱신 작업은 과금하지 않는다.
-- provider_synced_at = 마지막으로 반영한 구독 웹훅의 발생 시각(순서가 뒤바뀐 웹훅을 거르는 기준). NULL 은 서로 겹치지 않아 옛 줄과 토스 줄은 영향이 없다. 이진 정렬(utf8mb4_bin)은 장부의 다른 키 열과 같다.
alter table payment_subscriptions add column provider_subscription_id varchar(100) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */;
alter table payment_subscriptions add column provider_synced_at datetime(6);
alter table payment_subscriptions add constraint uq_payment_subscriptions_provider_sub unique (provider, provider_subscription_id);
