-- modules:notification-push-jdbc — 사람 × 알림 종류 「푸시만 끄기」 (PostgreSQL). 끈 것만 줄이 있다(다시 켜면 줄을 지운다). 모든 채널 끄기(notification_mutes)와 별개의 표.
create table if not exists push_type_mutes (
    account_id varchar(128) not null,
    type       varchar(128) not null,
    created_at timestamptz  not null,
    primary key (account_id, type)
);
