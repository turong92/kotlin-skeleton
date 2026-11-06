-- point_spend_allocations (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:point-jdbc — 차감이 어느 적립 묶음에서 얼마를 뺐는지(할당)와, 사용 취소(환원)가 어느 차감을 되돌렸는지(연결). 환원이 원래 묶음에 원래 유효기간으로 돌려놓으려는 기록이다.
-- 이 표들이 생기기 전의 차감은 할당이 없다 - 되돌릴 수 없다(사용 취소는 「원 차감 없음」으로 거절한다). FK 없음(계정 삭제 고리가 원장 줄과 함께 지운다).

-- 차감 줄 하나가 묶음마다 뺀 양. restored = 그중 이미 되돌린 양(0..amount). 한 차감의 취소 합계 = restored 의 합.
create table if not exists point_spend_allocations (
    spend_entry_id varchar(40) not null,                 -- point_entries.id (SPEND 줄)
    bundle_id      varchar(40) not null,                 -- point_entries.id (적립 묶음)
    amount         bigint      not null,
    restored       bigint      not null default 0,
    primary key (spend_entry_id, bundle_id),
    constraint ck_point_spend_alloc_amount check (amount > 0),
    constraint ck_point_spend_alloc_restored check (restored >= 0 and restored <= amount)
);

-- 환원 줄(RESTORE)이 되돌린 차감 줄. 같은 키의 다시 부름이 같은 차감을 가리키는지 가르는 데 쓴다.
create table if not exists point_restore_links (
    restore_entry_id varchar(40) primary key,            -- point_entries.id (RESTORE 줄)
    spend_entry_id   varchar(40) not null
);
