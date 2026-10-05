-- skeleton_jobs_log_context (mysql). 넣은 쪽의 로그 문맥(traceId 등)을 작업 줄에 붙여 워커 로그와 이어 준다. 페이로드는 건드리지 않는다.
-- MySQL 8.4 에는 `add column if not exists` 가 없다 — Flyway 가 한 번만 적용한다 (schema.sql 모드는 docs/job-queue-jdbc.md 참고)
alter table skeleton_jobs add column log_context varchar(512) null;
