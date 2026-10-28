-- modules:authz-jdbc — 역할 위계 (PostgreSQL). MySQL 판과 같은 뜻.
-- authz_role_includes: role 이 included 의 권한을 물려받는다(위가 아래를 모두 얻는다). role 을 지우면 그 줄도 같이 지워지고,
-- 다른 역할이 물려받는 included 는 지워지지 않는다(제약이 막는다 - 서비스가 먼저 AUTHZ.ROLE_INCLUDED 로 막는다).
create table if not exists authz_role_includes (
    role     varchar(40) not null references authz_roles (name) on delete cascade,
    included varchar(40) not null references authz_roles (name),
    primary key (role, included)
);

create index if not exists ix_authz_role_includes_included on authz_role_includes (included);
