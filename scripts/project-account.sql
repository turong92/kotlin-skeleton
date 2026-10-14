-- 프로젝트 하나의 DB 계정 + 스키마 (PostgreSQL, 멱등) — 프로젝트 모드(skeleton.projects)는 프로젝트마다 DB 계정이 필수다 (docs/multi-project.md).
-- 관리자 계정으로 그 데이터베이스에 연결해 돌린다. 아래 중괄호 두 겹 자리(계정 · 비밀번호 · 스키마)를 바꿔 쓴다 (scripts/dev-multi.sh · 시험이 같은 틀을 쓴다).
-- PgBouncer 트랜잭션 모드(포트 6432)는 트랜잭션마다 실제 연결을 바꿔 끼워 연결 단위 SET search_path 가 따라가지 않는다 — 그래서 기본 스키마는 **계정에 박는다**
-- (ALTER ROLE … SET search_path). 계정이 다르면 PgBouncer 가 풀도 나누고, 붙을 때마다 그 기본 스키마가 걸린다.
do $$
begin
  if not exists (select from pg_roles where rolname = '{{user}}') then
    create role "{{user}}" login password '{{password}}';
  end if;
end
$$;
alter role "{{user}}" with login password '{{password}}';
create schema if not exists "{{schema}}" authorization "{{user}}";
alter schema "{{schema}}" owner to "{{user}}";
alter role "{{user}}" set search_path = "{{schema}}";
-- 다른 프로젝트의 스키마에는 권한이 없어야 한다 (새 계정은 처음부터 없다 — 앱이 기동 때 has_schema_privilege 로 확인한다)
