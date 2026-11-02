-- modules:notification-push-jdbc — 푸시를 눌러 연 첫 한 번의 기록 (MySQL). (이벤트, 받는 사람) 쌍당 한 줄이라 같은 클릭 보고를 몇 번 받아도 한 번으로 센다. 계정 지우기가 이 표도 지운다.
create table if not exists push_opens (
    event_id     varchar(128) not null,
    recipient_id varchar(128) not null,
    opened_at    datetime(6)  not null,
    primary key (event_id, recipient_id)
);
-- 계정 지우기(recipient_id)
create index idx_push_opens_recipient on push_opens (recipient_id);
