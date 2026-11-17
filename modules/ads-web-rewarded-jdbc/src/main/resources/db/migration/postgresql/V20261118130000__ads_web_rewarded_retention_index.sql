-- ads_web_rewarded_retention_index (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:ads-web-rewarded-jdbc — 보관 기한 지우기(opened_at < cutoff)가 표 전체를 훑지 않게 한다. 카운터는 키가 (scope, subject, day_start) 라 day_start 단독 조건은 소량 표라 색인 없이 둔다.
create index if not exists idx_ads_web_rewarded_sessions_opened on ads_web_rewarded_sessions (opened_at);
