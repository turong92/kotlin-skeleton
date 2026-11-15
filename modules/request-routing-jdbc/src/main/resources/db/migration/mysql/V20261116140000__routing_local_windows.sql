-- modules:request-routing-jdbc — 로컬 창(로컬 서버로 보내도 되는 시간) 이력. 줄은 지우지 않는다. 새 표라서 옛 버전은 이 표를 모르고 그대로 돈다(expand).
-- 열린 줄 = closed_at 이 비고 open_until 이 지금보다 뒤인 줄. 사유(reason)는 이 표에만 남고 운영 기록에는 싣지 않는다.
create table if not exists routing_local_windows (
    id          bigint       not null auto_increment primary key,
    opened_by   varchar(64)  not null,
    reason      varchar(200) not null,
    opened_at   datetime(6)  not null,
    open_until  datetime(6)  not null,
    closed_by   varchar(64)  null,
    closed_at   datetime(6)  null
    /* [jooq ignore start] */,
    index idx_routing_local_windows_open_until (open_until)
    /* [jooq ignore stop] */
);
