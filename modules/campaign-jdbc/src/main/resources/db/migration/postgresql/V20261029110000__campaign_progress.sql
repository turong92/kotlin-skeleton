-- modules:campaign-jdbc — 업적 수치 (PostgreSQL). 동결된 V20261028152200__campaigns.sql 을 고치지 않고 더한다.
-- 수치는 이벤트와 따로 계정에 붙는다(이벤트를 나중에 만들어도 값은 그대로). 증가만 한다 - 쓰기는 max(기존, 새 값)
create table if not exists campaign_progress (
    account_id  varchar(64)  not null,
    metric      varchar(64)  not null,
    value       bigint       not null,
    updated_at  timestamptz  not null,
    primary key (account_id, metric)
);
