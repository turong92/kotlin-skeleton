-- jobs_log_context (postgresql). 넣은 쪽의 로그 문맥(traceId 등)을 작업 줄에 붙여 워커 로그와 이어 준다. 페이로드는 건드리지 않는다.
alter table jobs add column if not exists log_context varchar(512) null;
