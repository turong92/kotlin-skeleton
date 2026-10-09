-- create_cheers (postgresql). 샘플 앱의 「응원하기」 기록 — 결제 장부(payment_orders)에 응원 한 번이 PAID 가 되면 앱의 PaymentOrderListener 가 한 줄을 남긴다.
-- 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md). 장부 표를 외래 키로 가리키지 않는다 — 장부는 다른 모듈의 표이고 탈퇴 때 서로 다르게 다뤄진다.
create table cheers (
    order_id        varchar(40)  primary key,              -- 장부의 주문 id (payment_orders.id) — 같은 주문은 한 줄이라 지급 훅이 두 번 불려도 한 줄이다
    account_id      varchar(128) not null,                 -- 응원한 계정 (Authentication.name). 탈퇴하면 이 계정의 줄은 지운다 (장부의 주문 줄은 법정 보존이라 남는다)
    amount          bigint       not null,                 -- 응원 금액 (통화 최소 단위)
    currency        char(3)      not null,                 -- ISO 4217
    refunded_amount bigint       not null default 0,       -- 환불된 금액의 합 — 일부 환불이면 status 는 PAID 로 남고 이 값만 오른다
    status          varchar(16)  not null,                 -- PAID | REFUNDED (전액 환불)
    created_at      timestamptz  not null,                 -- 결제된 시각 (장부 주문의 paid_at)
    refunded_at     timestamptz,                           -- 전액 환불된 시각
    constraint ck_cheers_status check (status in ('PAID', 'REFUNDED')),
    constraint ck_cheers_amounts check (amount >= 0 and refunded_amount >= 0 and refunded_amount <= amount)
);

-- 내 응원 내역: 최신순 커서 (created_at, order_id) 를 거꾸로 훑는다
create index idx_cheers_account on cheers (account_id, created_at, order_id);
