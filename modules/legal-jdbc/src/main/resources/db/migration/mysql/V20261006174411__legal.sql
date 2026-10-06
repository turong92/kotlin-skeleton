-- modules:legal-jdbc — 약관 · 동의 (MySQL). PostgreSQL 판과 같은 뜻 — 더하기만 하는 기록 + 트리거 (지우기 · 고치기를 막되 익명화 · IP/UA 비우기는 허용).
create table if not exists legal_consents (
    id             bigint        auto_increment primary key,
    subject_type   varchar(32)   not null,
    subject_id     varchar(128)  not null,
    document_type  varchar(32)   not null,
    version        varchar(32)   not null,
    content_sha256 varchar(64)   not null,
    locale         varchar(35)   not null,
    action         varchar(16)   not null,
    source         varchar(32)   not null,
    reference_id   varchar(128)  not null default '',
    seq            int           not null,
    ip             varchar(64),
    user_agent     varchar(255),
    created_at     datetime(6)   not null,
    constraint uq_legal_consents_event unique (subject_type, subject_id, document_type, reference_id, seq)
    /* [jooq ignore start] */,
    index idx_legal_consents_subject (subject_type, subject_id, id),
    index idx_legal_consents_created (created_at)
    /* [jooq ignore stop] */
);

drop trigger if exists legal_consents_no_delete;
create trigger legal_consents_no_delete before delete on legal_consents for each row
begin
    if old.subject_id not like 'deleted:%' then
        signal sqlstate '45000' set message_text = 'legal_consents is append-only: a row is deleted only after it was anonymized';
    end if;
end;

drop trigger if exists legal_consents_no_update;
create trigger legal_consents_no_update before update on legal_consents for each row
begin
    if not (new.id <=> old.id and new.subject_type <=> old.subject_type and new.document_type <=> old.document_type and new.version <=> old.version
            and new.content_sha256 <=> old.content_sha256 and new.locale <=> old.locale and new.action <=> old.action and new.source <=> old.source
            and new.reference_id <=> old.reference_id and new.seq <=> old.seq and new.created_at <=> old.created_at) then
        signal sqlstate '45000' set message_text = 'legal_consents is append-only: only the personal columns may change';
    end if;
    if not (new.subject_id <=> old.subject_id) and new.subject_id not like 'deleted:%' then
        signal sqlstate '45000' set message_text = 'legal_consents: a subject id may only be replaced by a tombstone';
    end if;
    if (new.ip is not null and not (new.ip <=> old.ip)) or (new.user_agent is not null and not (new.user_agent <=> old.user_agent)) then
        signal sqlstate '45000' set message_text = 'legal_consents: ip and user agent may only be cleared';
    end if;
end;

create table if not exists legal_document_versions (
    id             bigint       auto_increment primary key,
    document_type  varchar(32)  not null,
    version        varchar(32)  not null,
    locale         varchar(35)  not null,
    status         varchar(16)  not null,
    content_sha256 varchar(64)  not null,
    effective_from datetime(6),
    recorded_at    datetime(6)  not null,
    constraint uq_legal_document_versions unique (document_type, version, locale)
);

drop trigger if exists legal_document_versions_no_delete;
create trigger legal_document_versions_no_delete before delete on legal_document_versions for each row
begin
    signal sqlstate '45000' set message_text = 'legal_document_versions is append-only';
end;

drop trigger if exists legal_document_versions_no_update;
create trigger legal_document_versions_no_update before update on legal_document_versions for each row
begin
    signal sqlstate '45000' set message_text = 'legal_document_versions is append-only';
end;
