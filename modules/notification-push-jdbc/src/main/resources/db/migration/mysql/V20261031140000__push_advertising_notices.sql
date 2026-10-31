-- modules:notification-push-jdbc — 광고 수신 동의 재확인 안내를 이 동의 주기에 이미 보냈다는 표시 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists push_advertising_notices (
    account_id  varchar(128) not null,
    type        varchar(128) not null,
    agreed_at   datetime(6)  not null,
    notified_at datetime(6)  not null,
    primary key (account_id, type, agreed_at)
);
