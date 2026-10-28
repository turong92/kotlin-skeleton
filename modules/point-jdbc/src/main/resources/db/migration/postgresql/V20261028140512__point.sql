-- modules:point-jdbc — 포인트 원장 (postgresql). 설계: docs/modules/point-jdbc.md
-- 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- 표 둘을 한 번에 만든다 — 잠근 뒤에는 고칠 수 없다. 금액은 포인트 정수(bigint) · 시각은 timestamptz · JSON 열 없음 · 부분 인덱스 없음.

-- 계정마다 잔액 한 줄. 잔액 = 그 계정 적립 묶음(point_entries.remaining)의 합. 쓰기는 이 줄을 잠그고 직렬로 돈다. 음수 잔액은 표가 거절한다.
create table if not exists point_balances (
    account_id varchar(128) primary key,
    balance    bigint        not null,
    updated_at timestamptz   not null,
    constraint ck_point_balances_nonneg check (balance >= 0)
);

-- 원장 — 지급 · 차감 · 소멸 · 조정 한 번 = 한 줄. 줄은 고치지 않고(remaining 만 줄어든다) 더하기만 한다.
create table if not exists point_entries (
    id          varchar(40)   primary key,                -- pt_ + ULID
    account_id  varchar(128)  not null,                   -- FK 없음 (다른 모듈의 표)
    delta       bigint        not null,                   -- 늘면 +, 줄면 −
    kind        varchar(16)   not null,                   -- EARN | SPEND | EXPIRE | ADJUST
    remaining   bigint        not null default 0,         -- 적립 묶음(EARN · 양수 ADJUST)의 아직 안 쓴 양. 나머지 줄은 0
    expires_at  timestamptz,                              -- 묶음의 유효기간. NULL 이면 안 사라진다
    source_type varchar(32)   not null,                   -- GRANT(지급 건) | ADJUST(운영자) | EXPIRE(소멸 대상 묶음) | 쓰는 쪽이 정한 이름(주문 …)
    source_ref  varchar(200)  not null,
    reason      varchar(200),                             -- 운영자 조정의 사유
    created_at  timestamptz   not null,
    constraint uq_point_entries_source unique (source_type, source_ref),   -- 같은 지급 건 · 주문을 두 번 처리해도 한 줄
    constraint ck_point_entries_remaining check (remaining >= 0)
);
create index if not exists idx_point_entries_account on point_entries (account_id, created_at, id);   -- 내역 (거꾸로 훑는다) · 차감 순서
create index if not exists idx_point_entries_expires on point_entries (expires_at);                   -- 소멸 작업
