-- board_public_attachment_sync (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md). 기존 표는 건드리지 않는다.

-- 공개 영역을 맞추다 실패한 항목 - 이벤트가 곧바로 맞추지 못하면 여기 적어 두고 주기 작업이 이것만 다시 맞춘다(매분 전체를 훑지 않는다).
-- claimed_until: 한 인스턴스가 잡고 있는 동안 다른 인스턴스가 같은 줄을 잡지 않는다 (select … for update skip locked 로 잡는다).
create table if not exists board_public_attachment_queue (
    target_type   varchar(16) not null,              -- POST | COMMENT
    target_id     bigint      not null,
    queued_at     datetime(6) not null,
    claimed_until datetime(6),
    primary key (target_type, target_id)
);

-- 드문 전체 대조의 단일 실행 장치 - 인스턴스가 `update … where last_run_at < :한계` 로 한 줄을 차지해야 돈다.
create table if not exists board_public_attachment_runs (
    name        varchar(32) not null primary key,
    last_run_at datetime(6) not null
);
