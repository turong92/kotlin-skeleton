-- modules:search-jdbc — 검색 색인 문서 · 재색인 진행 (MySQL). PostgreSQL 판과 같은 뜻 - 한국어는 FULLTEXT ... WITH PARSER ngram (기본 2글자).
-- 시각은 datetime(6) (UTC).

-- 아래 SET 은 이 마이그레이션 연결의 세션 값이다. InnoDB 는 색인을 만들 때의 값으로 불용어를 정하고, ngram 은 불용어를 **포함한** 조각을 통째로 버려
-- `in` `is` `to` `on` `or` `be` 같은 영어 조각이 빠진다 (`end` 질의가 `and` 글에 맞기도 한다 - 실측 K4). 끄지 않으면 영어 검색이 틀린다.
-- 뒷날 다른 세션에서 색인을 다시 만들면(ALTER TABLE ... FORCE 등) 이 값이 다시 켜져 있으니 같은 SET 을 먼저 한다 (docs/modules/search-jdbc.md).
SET SESSION innodb_ft_enable_stopword = OFF;

-- 색인 문서 한 줄 = 문서 하나 (doc_type, doc_id). title · body 는 NFKC 정규화한 원문(표시 · 발췌용)
create table if not exists search_documents (
    id                bigint       not null auto_increment primary key,
    doc_type          varchar(32)  not null,
    doc_id            varchar(64)  not null,
    owner_id          varchar(64),                          -- null = 주인 없음(보여도 되는 모두에게), 있으면 그 계정에게만
    scope             varchar(64),                          -- 결과를 좁히는 열린 값 (모듈은 뜻을 모른다)
    title             varchar(300) not null,
    body              text         not null,
    source_updated_at datetime(6)  not null,                -- 원본이 바뀐 시각 - 더 옛것은 덮어쓰지 않는다
    indexed_at        datetime(6)  not null,                -- 이 줄을 쓴 시각 - 재색인 끝의 사라진 원본 정리가 쓴다
    constraint uq_search_documents_doc unique (doc_type, doc_id),
    index idx_search_documents_owner (owner_id),
    index idx_search_documents_type_indexed (doc_type, indexed_at),
    fulltext index ft_search_documents_title (title) with parser ngram,
    fulltext index ft_search_documents_text (title, body) with parser ngram
);

-- 종류별 재색인 진행. running 이고 updated_at 이 오래되면 멈춘 것으로 본다 (새 재색인을 받는다). finished_at 은 마지막으로 끝난 시각
create table if not exists search_rebuilds (
    doc_type    varchar(32) primary key,
    run_id      varchar(40) not null,
    running     boolean     not null,
    started_at  datetime(6) not null,
    updated_at  datetime(6) not null,
    finished_at datetime(6)
);
