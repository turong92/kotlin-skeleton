-- account_test_marks (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:account-jdbc — 운영자가 「시험 계정」으로 표시한 계정. 통계 · 대시보드 · 매출 · 결제 집계 · 공지 대상 all 이 이 표로 걸러 낸다 (TestAccountFilter).
-- 계정당 한 줄 — 누가 언제 표시했는지만 담는다. 계정 줄이 지워지면 함께 지워진다. 새 표라서 옛 버전은 이 표를 모르고 그대로 돈다(expand).
create table if not exists account_test_marks (
    account_id varchar(40) not null primary key,   -- accounts.id · coupons.account_id 와 같은 기본 정렬(외래 키가 맞는다). payment_orders.account_id 는 utf8mb4_bin 이라 결제 비교는 _bin 으로 된다(오류 없음, 표가 작아 인덱스 영향 무시)
    marked_by  varchar(40) not null,
    marked_at  datetime(6) not null
    /* [jooq ignore start] */,
    foreign key (account_id) references accounts (id) on delete cascade
    /* [jooq ignore stop] */
);
