-- modules:authz-jdbc — 역할 위계 (MySQL). PostgreSQL 판과 같은 뜻.
create table if not exists authz_role_includes (
    role     varchar(40) not null,
    included varchar(40) not null,
    primary key (role, included),
    index ix_authz_role_includes_included (included)
    /* [jooq ignore start] */,
    constraint fk_authz_role_includes_role foreign key (role) references authz_roles (name) on delete cascade,
    constraint fk_authz_role_includes_included foreign key (included) references authz_roles (name)
    /* [jooq ignore stop] */
);
