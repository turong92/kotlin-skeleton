-- modules:notification-broadcast-jdbc — 공지(전체 · 대상 공지 → 알림함 + 푸시) 한 줄과 채널별 전달 숫자 (PostgreSQL). 받는 사람 데이터는 없다(수만 명을 담지 않는다).
-- id 는 알림 이벤트 id 로도 쓴다(`bc_…`). run_token · next_seq · cursor_id 는 단계 작업이 이어 달리는 자리(작업은 토큰과 순번이 이 줄과 같을 때만 움직인다).
create table if not exists broadcasts (
    id                varchar(40)   not null,
    status            varchar(16)   not null,
    type              varchar(128)  not null,
    title             varchar(255)  not null,
    body              varchar(2000),
    link              varchar(1000),
    channels          varchar(255)  not null,
    audience_kind     varchar(64)   not null,
    audience_param    varchar(128),
    scheduled_at      timestamptz,
    run_token         varchar(32),
    next_seq          integer       not null default 0,
    cursor_id         varchar(128),
    estimate          bigint,
    recipients        bigint        not null default 0,
    created_by        varchar(128)  not null,
    sent_by           varchar(128),
    cancelled_by      varchar(128),
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    send_requested_at timestamptz,
    started_at        timestamptz,
    finished_at       timestamptz,
    cancelled_at      timestamptz,
    failure_reason    varchar(500),
    primary key (id)
);
-- 목록(상태로 거르고 최근 순)
create index if not exists idx_broadcasts_status on broadcasts (status, id);

create table if not exists broadcast_channel_stats (
    broadcast_id varchar(40) not null,
    channel      varchar(32) not null,
    queued       bigint      not null default 0,
    delivered    bigint      not null default 0,
    failed       bigint      not null default 0,
    dropped      bigint      not null default 0,
    opened       bigint      not null default 0,
    primary key (broadcast_id, channel)
);
