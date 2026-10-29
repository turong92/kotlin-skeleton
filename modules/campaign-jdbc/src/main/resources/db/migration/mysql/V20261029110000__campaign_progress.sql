-- modules:campaign-jdbc — 업적 수치 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists campaign_progress (
    account_id  varchar(64)  not null,
    metric      varchar(64)  not null,
    value       bigint       not null,
    updated_at  datetime(6)  not null,
    primary key (account_id, metric)
);
