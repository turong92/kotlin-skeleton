-- modules:idempotency-jdbc — 멱등 키 저장 (PostgreSQL). 시각은 timestamptz (UTC).
-- scoped_key = "<범위>:<Idempotency-Key>" (범위는 IdempotencyScopeResolver 가 정한다 - 로그인한 주체 또는 클라이언트 IP). 한 키는 한 줄.
-- state: IN_PROGRESS(예약만 함) | COMPLETED(첫 응답이 status · headers · body 에 있다). claim 은 예약한 요청 하나를 가리는 난수라 동시에 온 같은 키 중 누가 이겼는지 안다.
-- 만료한 줄은 예약이 지우거나 청소 작업이 지운다 (expires_at 색인).
create table if not exists idempotency_keys (
    scoped_key   varchar(400) primary key,
    fingerprint  varchar(128) not null,
    state        varchar(16)  not null,
    claim        varchar(40)  not null,
    status       int,
    content_type varchar(255),
    headers      text,
    body         bytea,
    expires_at   timestamptz  not null,
    created_at   timestamptz  not null
);
create index if not exists idx_idempotency_keys_expires on idempotency_keys (expires_at);
