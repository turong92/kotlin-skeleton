-- 이 모듈의 코드 생성·테스트용 예시 스키마. 앱은 자기 schema.sql 을 가리킨다.
-- DDLDatabase(jOOQ 파서) 가 읽으므로 MySQL 전용 storage 절(engine=, charset=)은 빼고, 표준 DDL 만 쓴다.
create table jooq_probe (
    id         bigint auto_increment primary key,
    name       varchar(100) not null,
    happened_at datetime(6) not null,
    local_wall datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null
);
