-- jobs_lane (postgresql). 요청 라우팅: 후보 일꾼이 자기 표시 붙은 잡만 집게 하는 차선. null = 기본 일꾼.
alter table jobs add column if not exists lane varchar(40) null;
