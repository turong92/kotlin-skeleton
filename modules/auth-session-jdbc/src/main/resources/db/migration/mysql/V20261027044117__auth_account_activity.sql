-- modules:auth-session-jdbc — 계정별 일별 활동 기록 (mysql). PostgreSQL 판과 같은 뜻.
create table if not exists auth_account_activity (
    account_id varchar(64) not null,
    day        date        not null,
    primary key (account_id, day)
    /* [jooq ignore start] */,
    index idx_auth_account_activity_day (day)
    /* [jooq ignore stop] */
);

drop index idx_auth_sessions_last_used on auth_sessions;
