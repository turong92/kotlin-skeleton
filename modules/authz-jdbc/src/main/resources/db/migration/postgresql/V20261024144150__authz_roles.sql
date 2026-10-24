-- modules:authz-jdbc — 역할 → 권한 표 (PostgreSQL). MySQL 판과 같은 뜻.
-- authz_roles: 역할. protected 는 전권 역할(skeleton.authz.super-role) — 저장소 문장이 이 줄을 고치지도 지우지도 않는다.
-- authz_role_permissions: 역할이 가진 권한 키. 역할을 지우면 같이 지워진다.
create table if not exists authz_roles (
    name        varchar(40)  primary key,
    description varchar(200),
    protected   boolean      not null default false,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null
);

create table if not exists authz_role_permissions (
    role       varchar(40) not null references authz_roles (name) on delete cascade,
    permission varchar(64) not null,
    primary key (role, permission)
);
