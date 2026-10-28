-- modules:payment-jdbc — 주문의 할인 (postgresql). 설계: docs/superpowers/specs/2026-10-28-events-rewards-design.md §3.5
-- 동결된 V20261009124513__payment_ledger.sql 을 고치지 않고 더한다.
-- payment_orders.amount 는 여전히 **최종 금액**이다 - 결제사 승인 대조 · 환불 상한(paid_amount ?: amount) · 중도 해지 환불이 그대로 맞는다.
alter table payment_orders add column original_amount bigint;                        -- 할인 전 금액. 할인도 면제도 없는 주문(옛 줄 포함)은 NULL. 결제 면제 주문은 카탈로그 가격(얼마어치를 면제했나)
alter table payment_orders add column discount_amount bigint not null default 0;     -- 깎인 금액의 합. 면제는 할인이 아니라 0

-- 주문에 붙은 할인 내역 - 수단(쿠폰)이 내준 줄 하나 = 한 줄. 부분 인덱스 · JSON 열 없음.
create table if not exists payment_order_discounts (
    order_id   varchar(40)  not null references payment_orders (id),
    seq        int          not null,                    -- 주문 안의 순번(1 부터). 첫 판은 주문당 한 줄
    kind       varchar(16)  not null,                    -- 할인 수단 (coupon)
    source_ref varchar(64)  not null,                    -- 그 수단 안의 식별 (쿠폰 id) - 확정 · 복원이 쓴다
    label      varchar(100) not null,                    -- 화면에 보이는 이름
    amount     bigint       not null,                    -- 깎인 금액 (통화 최소 단위, 양수)
    currency   char(3)      not null,
    status     varchar(16)  not null,                    -- RESERVED | CONFIRMED | RELEASED | RESTORED
    created_at timestamptz  not null,
    updated_at timestamptz  not null,
    primary key (order_id, seq),
    constraint ck_payment_order_discounts_amount check (amount > 0)
);
create index if not exists idx_payment_order_discounts_source on payment_order_discounts (kind, source_ref);   -- 이 쿠폰을 쓴 주문 찾기
