-- modules:notification-push-jdbc — 광고 수신 동의 재확인 안내를 이 동의 주기에 이미 보냈다는 표시 (PostgreSQL). 계정 지우기가 이 표도 지운다.
create table if not exists push_advertising_notices (
    account_id  varchar(128) not null,
    type        varchar(128) not null,
    agreed_at   timestamptz  not null,   -- 이 주기를 연 동의 사건의 시각
    notified_at timestamptz  not null,
    primary key (account_id, type, agreed_at)
);
