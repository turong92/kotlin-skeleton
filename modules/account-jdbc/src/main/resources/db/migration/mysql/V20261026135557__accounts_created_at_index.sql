-- modules:account-jdbc — 대시보드의 「기간 안 가입」 질의가 가입 시각으로 범위를 끊는다. 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
create index idx_accounts_created on accounts (created_at);
