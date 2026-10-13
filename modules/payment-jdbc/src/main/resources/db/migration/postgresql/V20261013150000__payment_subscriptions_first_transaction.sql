-- modules:payment-jdbc — 구독의 첫 결제 거래 id (postgresql)
-- 서버가 결제사(Paddle)에 첫 결제 거래를 미리 만들고 그 id 를 줄에 박는다. 웹훅의 첫 결제는 이 id 로 PENDING 줄을 찾는다(브라우저가 넘기는 값을 믿지 않는다). NULL 은 서로 겹치지 않는다.
alter table payment_subscriptions add column first_transaction_id varchar(100);
alter table payment_subscriptions add constraint uq_payment_subscriptions_first_txn unique (provider, first_transaction_id);
