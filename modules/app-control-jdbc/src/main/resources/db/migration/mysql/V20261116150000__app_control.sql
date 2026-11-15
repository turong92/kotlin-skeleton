-- modules:app-control-jdbc — 기능 스위치 · 점검 · 플랫폼 버전 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists app_control_entries (
    name        varchar(100) primary key,
    payload     text         not null,
    updated_by  varchar(64)  not null,
    updated_at  datetime(6)  not null
);
