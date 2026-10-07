-- modules:author-profile-jdbc — 작성자 프로필 (한 계정의 여러 닉네임) (mysql). 설계: docs/modules/author-profile.md · docs/superpowers/specs/2026-10-07-author-profiles-design.md §6
-- 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- 인라인 index 는 MySQL 문법이라 jOOQ DDLDatabase(H2 파서) 가 못 읽는다 — schema.sql 로 복사해 코드 생성에 쓸 때를 위해 [jooq ignore] 마커로 감싼다 (job-queue-jdbc · account-jdbc 와 같다).
-- 별도 `create index` 로 빼지 않는 이유: MySQL 8.4 엔 `create index if not exists` 가 없어 schema.sql 재실행(매 기동)에서 깨진다.
create table if not exists author_profiles (
    id         varchar(40)  primary key,        -- prf_ + 32자 16진수 (SecureRandom 16바이트) — 공개 작성자 id
    account_id varchar(40)  not null,           -- 주인. 비공개. FK 없음 (다른 모듈의 표 — 탈퇴 고리가 행을 지운다)
    primary_of varchar(40)  unique,             -- 계정의 첫 프로필이면 계정 id, 아니면 NULL — 계정당 하나 (NULL 은 서로 겹치지 않는다)
    name       varchar(60),                     -- NULL: 지워진 프로필 · 이름 없는 계정의 옛 글을 넘겨받은 첫 프로필
    -- 이름의 비교용 키 (NFKC · 소문자). 이진 정렬: MySQL 기본 정렬은 악센트 · 대소문자를 같게 봐서 키가 같다고 잘못 판정한다 (accounts.display_name_key 와 같은 규칙).
    -- collate 는 jOOQ DDLDatabase 가 못 읽는 절이라 마커로 가린다 (docs/persistence-jooq.md) — 코드 생성 타입은 그대로 varchar
    name_key   varchar(255) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */,
    tag        char(4),                         -- accounts.display_tag 와 같은 규칙 (NONE: NULL · UNIQUE: '0000' · TAGGED: '0001'..'9999')
    status     varchar(16)  not null,           -- ACTIVE | DELETED
    adopted_at datetime(6),                     -- 첫 프로필만: 옛 글을 넘겨받은 시각
    created_at datetime(6)  not null,
    updated_at datetime(6)  not null,
    deleted_at datetime(6),
    constraint uq_author_profiles_name unique (name_key, tag)   -- 둘 중 하나라도 NULL 이면 겹치지 않는다
    -- findActive · countActive 와 insertWithinCap 의 상한 세기. countCreatedSince 는 이 인덱스로 계정의 줄만 훑는다 —
    -- 계정당 줄 수는 한 자리(상한 · 만들기 한도가 막는다)라서 (account_id, created_at) 인덱스를 따로 두지 않는다.
    /* [jooq ignore start] */,
    index idx_author_profiles_account (account_id, status)
    /* [jooq ignore stop] */
);
