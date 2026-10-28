-- modules:reward-jdbc — 보상 지급 건 (PostgreSQL). 한 행 = 한 번의 지급, (source_type, source_key) 유니크가 같은 조건의 두 번 판정을 한 건으로 접는다.
-- 상태: PENDING -> ISSUED(끝) · PENDING -> FAILED(작업이 DEAD, 운영자 「다시 지급」으로 PENDING) · PENDING -> CANCELED(운영자, 지급 전만).
create table if not exists reward_grants (
    id           varchar(40)  primary key,
    account_id   varchar(64)  not null,
    reward_type  varchar(32)  not null,
    params_json  text         not null,                 -- Map<String,String> 의 JSON
    source_type  varchar(32)  not null,                 -- attendance · referral …
    source_key   varchar(200) not null,                 -- 예: <캠페인>:<계정>:<날>:daily:0
    status       varchar(16)  not null,
    result_ref   varchar(64),                           -- 만들어진 것의 id (쿠폰 id · 포인트 내역 id)
    last_error   varchar(200),
    created_at   timestamptz  not null,
    issued_at    timestamptz,
    constraint uq_reward_grants_source unique (source_type, source_key)
);
create index if not exists idx_reward_grants_status on reward_grants (status, created_at);
create index if not exists idx_reward_grants_account on reward_grants (account_id, created_at);
