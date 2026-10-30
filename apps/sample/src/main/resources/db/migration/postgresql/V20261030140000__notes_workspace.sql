-- 노트가 워크스페이스(팀)에 속할 수 있다. null 이면 예전처럼 내 노트(owner_id 가 주인), 있으면 그 워크스페이스의 노트(owner_id 는 쓴 사람).
-- 워크스페이스 표는 modules:workspace-jdbc 의 것이라 외래 키를 걸지 않는다(모듈은 앱의 표를 모르고 앱도 모듈의 표에 묶이지 않는다) - 접근은 요청마다 멤버십으로 판정한다.
alter table notes add column workspace_id varchar(40);
create index idx_notes_workspace on notes (workspace_id, updated_at desc) where workspace_id is not null;
