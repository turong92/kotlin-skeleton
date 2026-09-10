-- Flyway 없이 spring.sql.init 로 스키마를 만드는 예시 (v0 용). 실제 앱은 src/main/resources/schema.sql
create table if not exists schema_sql_probe (
    id   bigint auto_increment primary key,
    name varchar(50) not null
);
