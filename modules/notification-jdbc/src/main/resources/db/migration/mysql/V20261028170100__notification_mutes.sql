-- modules:notification-jdbc — 사람 × 알림 종류 수신 끄기. 끈 것만 줄이 있다(다시 켜면 줄을 지운다). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
create table if not exists notification_mutes (
    recipient_id varchar(128) not null,
    type         varchar(128) not null,
    created_at   datetime(6)  not null,
    primary key (recipient_id, type)
);
