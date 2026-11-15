-- modules:notification-scheduled-jdbc — 사람마다 따로 예약하는 푸시 한 통 (MySQL). push_key 는 앱이 정하는 중복 키(같은 키 = 같은 예약).
-- PENDING → SENT | SKIPPED. claimed_until 은 훑기가 집은 줄의 임대 만료(집은 쪽이 죽으면 그 뒤 다시 집힌다). 끝난 줄은 같은 키의 중복 예약을 막는 표시로 남는다.
create table if not exists scheduled_pushes (
    push_key      varchar(200)  not null,
    recipient_id  varchar(128)  not null,
    run_at        datetime(6)   not null,
    title         varchar(100)  not null,
    body          varchar(2000),
    link          varchar(1000),
    advertising   boolean       not null default false,
    status        varchar(16)   not null,
    skip_reason   varchar(32),
    claimed_until datetime(6),
    attempts      integer       not null default 0,
    created_at    datetime(6)   not null,
    updated_at    datetime(6)   not null,
    finished_at   datetime(6),
    primary key (push_key)
);
-- 시각이 된 PENDING 을 집는다
create index idx_scheduled_pushes_due on scheduled_pushes (status, run_at);
-- 탈퇴 삭제 · 하루 상한(그 사람이 최근 보낸 수)
create index idx_scheduled_pushes_recipient on scheduled_pushes (recipient_id, status, finished_at);
-- 끝난 줄 정리(시간당 한 번 지운다)
create index idx_scheduled_pushes_finished on scheduled_pushes (status, finished_at);
