-- modules:auth-session-jdbc — 대시보드의 「기간 안 활성 이용자」 질의가 마지막 사용 시각으로 범위를 끊는다. 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
create index idx_auth_sessions_last_used on auth_sessions (last_used_at);
