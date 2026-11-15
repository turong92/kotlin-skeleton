-- modules:app-control-jdbc — 기능 스위치 · 점검 · 플랫폼 버전 (PostgreSQL). 설계: docs/modules/app-control.md
-- 표 하나, 줄 하나가 값 하나: name 은 `flag:<키>` · `maintenance` · `platform:<이름>`, payload 는 그 종류의 JSON. 읽기는 전체 한 번(캐시가 통째로 바꿔 끼운다).
-- 시각은 timestamptz (UTC). 점검 문구 · 스위치 설명이 payload 에 있으므로 운영 기록(OperatorAuditLog)에는 payload 를 싣지 않는다.
create table if not exists app_control_entries (
    name        varchar(100) primary key,
    payload     text         not null,
    updated_by  varchar(64)  not null,
    updated_at  timestamptz  not null
);
