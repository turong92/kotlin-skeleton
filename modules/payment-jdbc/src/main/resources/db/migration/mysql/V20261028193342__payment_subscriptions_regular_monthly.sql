-- modules:payment-jdbc — 구독을 시작할 때의 「같은 등급 월 정가」 (mysql)
-- 월보다 긴 주기의 구독이 쓴 몫을 셀 때 쓰는 값을 시작 때 줄에 박는다 - 나중에 카탈로그에서 월 가격이 중지되거나 바뀌어도 해지 환불이 변하지 않게. 옛 줄 · 코드 플랜 · 월 구독은 NULL(해지 때 지금 표에서 찾는다).
alter table payment_subscriptions add column regular_monthly_amount bigint;
