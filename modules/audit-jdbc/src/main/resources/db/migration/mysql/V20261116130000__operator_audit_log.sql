-- operator_audit_log (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:audit-jdbc — 운영자 행동 기록: 누가(actor_id) · 언제(occurred_at) · 무엇을(action) · 대상(target_type, target_id) · 결과(result) · 어디서(ip, user_agent).
-- **더하기만 한다** — 트리거가 막는 것: 고치기(항상), 지우기(기록한 지 30일이 안 된 줄 — 보관 기한 설정 skeleton.audit.retention 의 최솟값과 같다).
-- MySQL 은 TRUNCATE 에 트리거를 걸 수 없다 — 표 비우기는 데이터베이스 계정의 권한(앱 계정에 DROP · TRUNCATE 를 주지 않는다)으로 막는다.
-- 값은 id 만 — 이메일 · 이름 · 본문 · 토큰을 싣지 않는다(OperatorAction 의 규칙). ip · user_agent 는 개인정보라 보관 기한이 지나면 줄째 지워진다.
-- 새 표라서 옛 버전은 이 표를 모르고 그대로 돈다(expand).
create table if not exists operator_audit_log (
    id          bigint       not null auto_increment primary key,
    occurred_at datetime(6)  not null,
    actor_id    varchar(64)  not null,
    action      varchar(100) not null,                    -- <모듈>.<대상>.<과거형>
    target_type varchar(64)  not null,
    target_id   varchar(128) not null,
    result      varchar(16)  not null,                    -- SUCCESS | DENIED | FAILED
    ip          varchar(64),
    user_agent  varchar(255),
    detail      text                                      -- OperatorAction.detail 의 JSON (문자열 맵)
    /* [jooq ignore start] */,
    index idx_operator_audit_log_time (occurred_at),
    index idx_operator_audit_log_actor (actor_id, id),
    index idx_operator_audit_log_target (target_type, target_id, id)
    /* [jooq ignore stop] */
);

drop trigger if exists operator_audit_log_no_update;
create trigger operator_audit_log_no_update before update on operator_audit_log for each row
begin
    signal sqlstate '45000' set message_text = 'operator_audit_log is append-only';
end;

drop trigger if exists operator_audit_log_no_delete;
create trigger operator_audit_log_no_delete before delete on operator_audit_log for each row
begin
    if old.occurred_at > utc_timestamp(6) - interval 30 day then
        signal sqlstate '45000' set message_text = 'operator_audit_log is append-only: a line is deleted only after 30 days';
    end if;
end;
