-- ads_web_rewarded_retention_index (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:ads-web-rewarded-jdbc — PostgreSQL 판과 같은 뜻: 보관 기한 지우기(opened_at < cutoff)용 색인. 인라인이 아닌 별도 문장이라 jOOQ 마커는 필요 없다.
create index idx_ads_web_rewarded_sessions_opened on ads_web_rewarded_sessions (opened_at);
