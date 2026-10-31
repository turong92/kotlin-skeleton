-- modules:notification-push-jdbc — 푸시 기기 (PostgreSQL). 한 행 = 한 설치(installation_id), 주소(웹 푸시 endpoint · FCM 토큰)는 한 줄에만(address_hash 유니크).
-- 암호화 없음: endpoint 는 서버의 VAPID 개인 키 없이는 쓸 수 없는 주소다. 그래도 로그 · 응답에 싣지 않는다.
create table if not exists push_devices (
    id              varchar(40)   primary key,                -- pd_…
    account_id      varchar(128)  not null,                   -- 주인. 등록하는 사람으로 바뀔 수 있다
    installation_id varchar(64)   not null,                   -- 클라이언트가 만든 무작위 값
    session_id      varchar(64),                              -- 등록한 로그인 세션(sid). 없으면 null
    kind            varchar(16)   not null,                   -- WEBPUSH · FCM · …
    platform        varchar(16)   not null,                   -- web · android · ios
    address         varchar(2048) not null,
    address_hash    char(64)      not null,                   -- sha256(kind + 개행 + address) — 긴 주소의 유니크
    p256dh          varchar(128),                             -- 웹 푸시만
    auth_secret     varchar(64),                              -- 웹 푸시만
    vapid_key_id    varchar(16),                              -- 구독할 때의 서버 공개 키 지문 — 다르면 보내지 않는다
    label           varchar(100),
    created_at      timestamptz   not null,
    last_seen_at    timestamptz   not null,
    last_success_at timestamptz,
    constraint uq_push_devices_installation unique (installation_id),
    constraint uq_push_devices_address unique (address_hash)
);
create index if not exists idx_push_devices_account on push_devices (account_id);
create index if not exists idx_push_devices_session on push_devices (session_id);
create index if not exists idx_push_devices_last_seen on push_devices (last_seen_at);
