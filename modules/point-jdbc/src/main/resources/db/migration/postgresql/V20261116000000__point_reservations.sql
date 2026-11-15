-- point_reservations (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:point-jdbc — 잡아 두기(reserve → capture | release): 쓸 수 없게 먼저 잡고, 일을 한 뒤 확정하거나 푼다. 잡은 양은 잔액(point_balances)에서 이미 빠져 있고,
-- 어느 묶음에서 얼마를 덜었는지는 point_spend_allocations 가 (spend_entry_id = 이 표의 id) 들고 있다. 확정하면 같은 id 의 SPEND 줄이 point_entries 에 생기고 그 할당이 그대로 차감의 할당이 된다.
-- 상태는 HELD 에서 한 번만 바뀐다(조건부 갱신 - 확정 · 풀기 · 만료가 겹쳐도 하나만 이긴다). FK 없음(계정 삭제 고리가 같이 지운다).

create table if not exists point_reservations (
    id              varchar(40)  primary key,                -- pt_ + ULID. 확정하면 SPEND 줄의 id 와 같다
    account_id      varchar(128) not null,
    amount          bigint       not null,                   -- 잡은 양
    captured_amount bigint,                                  -- 확정한 양(부분 확정이면 amount 보다 작다). 확정 전에는 NULL
    status          varchar(16)  not null,                   -- HELD | CAPTURED | RELEASED | EXPIRED
    source_type     varchar(32)  not null,                   -- 원장 줄의 키와 같은 공간 - 확정하면 같은 키의 SPEND 줄이 생긴다
    source_ref      varchar(200) not null,
    hold_until      timestamptz  not null,                   -- 이 시각이 지나도 HELD 면 훑기가 푼다
    created_at      timestamptz  not null,
    settled_at      timestamptz,                             -- 확정 · 풀기 · 만료 시각
    constraint uq_point_reservations_source unique (source_type, source_ref),
    constraint ck_point_reservations_amount check (amount > 0),
    constraint ck_point_reservations_captured check (captured_amount is null or (captured_amount > 0 and captured_amount <= amount))
);
create index if not exists idx_point_reservations_due on point_reservations (status, hold_until);       -- 만료 훑기
create index if not exists idx_point_reservations_account on point_reservations (account_id, status);   -- 잡힌 합 · 계정 삭제
