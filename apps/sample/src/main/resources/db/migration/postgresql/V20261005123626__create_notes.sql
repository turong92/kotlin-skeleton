-- create_notes (postgresql). 샘플 앱의 유일한 도메인 테이블. 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
create table notes (
    id              uuid         primary key default gen_random_uuid(),
    owner_id        varchar(64)  not null,                 -- 계정 id (Authentication.name)
    title           varchar(80)  not null,
    body            text         not null default '',
    status          varchar(16)  not null default 'DRAFT', -- DRAFT | ACTIVE | ARCHIVED
    pinned          boolean      not null default false,
    attachment_key  varchar(512),                          -- storage 모듈 키: uploads/<계정 id>/<uuid>/<파일 이름>
    attachment_name varchar(255),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    deleted_at      timestamptz                            -- audit 계약(AuditTimestamps)의 열. 이 샘플은 하드 삭제라 쓰지 않는다
);

-- 목록: 내 노트를 고정 → 최근 수정 순으로
create index notes_owner_listing on notes (owner_id, pinned desc, updated_at desc);
