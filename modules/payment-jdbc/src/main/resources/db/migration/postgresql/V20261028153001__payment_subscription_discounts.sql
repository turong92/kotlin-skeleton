-- modules:payment-jdbc — 구독의 할인 일정 (postgresql). 설계: docs/superpowers/specs/2026-10-28-events-rewards-design.md §3.5 · §5
-- 구독 시작 때 쿠폰의 단계 목록을 **복사**한다(쿠폰 정의를 고쳐도 진행 중인 구독은 그대로 - Stripe 관행). JSON 열 · 부분 인덱스 없음.
create table if not exists payment_subscription_discounts (
    subscription_id varchar(40)  primary key references payment_subscriptions (id),
    kind            varchar(16)  not null,        -- 할인 수단 (coupon)
    source_ref      varchar(64)  not null,        -- 그 수단 안의 식별 (쿠폰 id) - 첫 주문이 쿠폰을 쥘 때 쓴다
    label           varchar(100) not null,
    periods_used    int          not null default 0,   -- 할인이 붙은 구독 주문이 PAID 된 횟수 = 다음 과금이 걸릴 단계의 위치
    status          varchar(16)  not null,        -- ACTIVE | ENDED
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null
);

create table if not exists payment_subscription_discount_stages (
    subscription_id varchar(40) not null references payment_subscriptions (id),
    seq             int         not null,         -- 0 부터 - 적용 순서
    type            varchar(16) not null,         -- FREE | PERCENT | FIXED
    value           bigint      not null,         -- PERCENT 1~100 · FIXED 최소 단위 금액 · FREE 는 0
    max_discount    bigint,                       -- PERCENT 의 상한. NULL = 없음
    periods         int,                          -- 몇 회차 이어지나. NULL = 끝까지(마지막 단계만)
    primary key (subscription_id, seq)
);
