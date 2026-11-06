-- point_spend_allocations (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:point-jdbc — PostgreSQL 판과 같은 뜻: 차감이 어느 적립 묶음에서 얼마를 뺐는지(할당)와, 사용 취소(환원)가 어느 차감을 되돌렸는지(연결).
-- 이 표들이 생기기 전의 차감은 할당이 없다 - 되돌릴 수 없다. FK 없음. 줄 id 는 이진 정렬이다(point_entries 와 같다) - collate 는 jOOQ DDLDatabase 가 못 읽어 마커로 가린다.
create table if not exists point_spend_allocations (
    spend_entry_id varchar(40) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    bundle_id      varchar(40) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    amount         bigint      not null,
    restored       bigint      not null default 0,
    primary key (spend_entry_id, bundle_id),
    constraint ck_point_spend_alloc_amount check (amount > 0),
    constraint ck_point_spend_alloc_restored check (restored >= 0 and restored <= amount)
);

create table if not exists point_restore_links (
    restore_entry_id varchar(40) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ primary key,
    spend_entry_id   varchar(40) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null
);
