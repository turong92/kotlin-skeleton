-- modules:authz-jdbc — 역할 → 권한 표 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists authz_roles (
    name        varchar(40)  primary key,
    description varchar(200),
    protected   boolean      not null default false,
    created_at  datetime(6)  not null,
    updated_at  datetime(6)  not null
);

create table if not exists authz_role_permissions (
    role       varchar(40) not null,
    permission varchar(64) not null,
    primary key (role, permission),
    constraint fk_authz_role_permissions_role foreign key (role) references authz_roles (name) on delete cascade
);
