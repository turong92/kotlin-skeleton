-- modules:payment-jdbc — 구독에 요청 키 열 (postgresql)
-- 동결된 V20261009134223__payment_subscriptions.sql 을 고치지 않고 열 하나를 더한다. 구독 id 가 시간순(sub_ + ULID)이 되면서 「같은 요청 = 같은 줄」을 id 가 아니라 이 열의 유니크가 맡는다
-- (주문의 dedupe_key 와 같은 방식: sha256(계정 id + "\n" + Idempotency-Key), NULL 은 서로 겹치지 않는다 — 옛 줄은 NULL 일 수 있고, 일회용 id 변환 스크립트는 옛 id 뒤 32자를 채울 뿐 옛 요청 키의 멱등을 이어 주지는 않는다).
alter table payment_subscriptions add column dedupe_key varchar(100);
alter table payment_subscriptions add constraint uq_payment_subscriptions_dedupe unique (dedupe_key);
