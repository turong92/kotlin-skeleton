-- jobs_lane (mysql). 요청 라우팅: 후보 일꾼이 자기 표시 붙은 잡만 집게 하는 차선. null = 기본 일꾼.
-- MySQL 8.4 에는 `add column if not exists` 가 없다 — Flyway 가 한 번만 적용한다 (schema.sql 모드는 docs/job-queue-jdbc.md 참고)
alter table jobs add column lane varchar(40) null;
