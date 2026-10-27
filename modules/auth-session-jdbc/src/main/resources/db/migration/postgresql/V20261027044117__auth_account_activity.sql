-- modules:auth-session-jdbc — 계정별 일별 활동 기록 (postgresql). 하루에 한 줄 (계정 id, 날짜) — 개인 값은 계정 id 뿐이고 계정이 지워질 때 같이 지운다.
-- 대시보드의 「활성 이용자」(기간 안 서로 다른 계정 수)와 「돌아옴」 단계가 읽는다. 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
create table if not exists auth_account_activity (
    account_id varchar(64) not null,
    day        date        not null,        -- 활동 날짜(앱 시간대 skeleton.auth-session.activity.zone)
    primary key (account_id, day)
);
create index if not exists idx_auth_account_activity_day on auth_account_activity (day);   -- 기간 질의 · 보존 기간 청소

-- 참고: V20261026135601 의 idx_auth_sessions_last_used 는 대시보드가 더는 쓰지 않는다(활동 기록으로 바꿨다). 마이그레이션은 서로 기대지 않으므로(docs/schema-management.md) 여기서 지우지 않는다 —
-- 쓰기 비용이 걸리면 앱이 자기 V 파일로 `drop index idx_auth_sessions_last_used;` 한다.
