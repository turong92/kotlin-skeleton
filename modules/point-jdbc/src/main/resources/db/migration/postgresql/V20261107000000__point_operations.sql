-- point_operations (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:point-jdbc — 포인트 작업 기록: 앱이 요청한 차감 · 적립 · 사용 취소마다 영수증 한 줄과 시도 줄들. 마지막 성공 줄만이 아니라 접수 · 시도 · 결과 전이를 남긴다.
-- 칸에는 토큰 · 결제 키 · 본문 · 사유가 없다 - 금액 · 키 · 코드뿐이다. 원장 줄과 FK 없음(계정 삭제 고리가 같이 지운다).

-- (source_type, source_ref) 는 원장 줄의 키와 같은 값이다 - 작업 하나 = 키 하나, 같은 키의 재시도는 attempts 로 이어 붙는다.
create table if not exists point_operations (
    operation_id        varchar(40)  primary key,                 -- op_ + ULID
    account_id          varchar(128) not null,
    kind                varchar(16)  not null,                    -- SPEND | CANCEL_SPEND | EARN
    amount              bigint,                                   -- 요청한 금액. 남은 전부를 되돌리는 사용 취소는 NULL
    source_type         varchar(32)  not null,
    source_ref          varchar(200) not null,
    fingerprint         varchar(64)  not null,                    -- sha256 hex (종류 · 계정 · 금액 · 원 차감 키)
    actor               varchar(64)  not null,                    -- 호출한 쪽(서비스 이름이나 계정 id)
    status              varchar(16)  not null,                    -- ACCEPTED | PROCESSING | SUCCEEDED | FAILED | UNKNOWN | ABANDONED
    origin_operation_id varchar(40),                              -- 보상 작업이면 원 작업
    compensation        varchar(16)  not null default 'NONE',     -- NONE | REQUESTED | DONE | FAILED
    result_entry_id     varchar(40),                              -- 성공한 원장 줄
    result_code         varchar(64),                              -- OK 또는 POINT.* 코드 - 안전한 문자열뿐
    attempts            integer      not null default 0,
    accepted_at         timestamptz  not null,
    last_attempt_at     timestamptz,
    finished_at         timestamptz,
    constraint uq_point_operations_source unique (source_type, source_ref)
);
create index if not exists idx_point_operations_account on point_operations (account_id, accepted_at);

create table if not exists point_operation_attempts (
    operation_id varchar(40) not null,
    n            integer     not null,
    started_at   timestamptz not null,
    finished_at  timestamptz,
    outcome      varchar(16),                                     -- SUCCEEDED | FAILED | UNKNOWN (끝나기 전에는 NULL)
    error_code   varchar(64),
    primary key (operation_id, n)
);
