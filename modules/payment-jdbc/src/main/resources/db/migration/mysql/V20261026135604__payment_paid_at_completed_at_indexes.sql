-- modules:payment-jdbc — 대시보드의 순매출 · 결제 건수 질의가 결제 시각(paid_at)과 환불 완료 시각(completed_at)으로 범위를 끊는다. 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
create index idx_payment_orders_paid on payment_orders (paid_at);
create index idx_payment_refunds_completed on payment_refunds (completed_at);
