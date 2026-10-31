-- modules:notification-push-jdbc — 사람 × 알림 종류 「푸시만 끄기」 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists push_type_mutes (
    account_id varchar(128) not null,
    type       varchar(128) not null,
    created_at datetime(6)  not null,
    primary key (account_id, type)
);
