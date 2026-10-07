-- modules:author-profile-jdbc — 작성자 프로필 (한 계정의 여러 닉네임). 설계: docs/modules/author-profile.md · docs/superpowers/specs/2026-10-07-author-profiles-design.md §6
-- 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
create table if not exists author_profiles (
    id         varchar(40)  primary key,        -- prf_ + 32자 16진수 (SecureRandom 16바이트) — 공개 작성자 id
    account_id varchar(40)  not null,           -- 주인. 비공개. FK 없음 (다른 모듈의 표 — 탈퇴 고리가 행을 지운다)
    primary_of varchar(40)  unique,             -- 계정의 첫 프로필이면 계정 id, 아니면 NULL — 계정당 하나 (부분 인덱스 없이 두 방언 공통. NULL 은 서로 겹치지 않는다)
    name       varchar(60),                     -- NULL: 지워진 프로필 · 이름 없는 계정의 옛 글을 넘겨받은 첫 프로필
    name_key   varchar(255),                    -- 이름의 비교용 키 (NFKC · 소문자). 지우면 NULL
    tag        char(4),                         -- accounts.display_tag 와 같은 규칙 (NONE: NULL · UNIQUE: '0000' · TAGGED: '0001'..'9999')
    status     varchar(16)  not null,           -- ACTIVE | DELETED
    adopted_at timestamptz,                     -- 첫 프로필만: 옛 글을 넘겨받은 시각
    created_at timestamptz  not null,
    updated_at timestamptz  not null,
    deleted_at timestamptz,
    constraint uq_author_profiles_name unique (name_key, tag)   -- 둘 중 하나라도 NULL 이면 겹치지 않는다
);

-- findActive · countActive (계정의 살아 있는 프로필) 와 insertWithinCap 의 상한 세기. countCreatedSince(account_id, created_at >= …, primary_of is null) 는 이 인덱스로 계정의 줄만 훑는다 —
-- 계정당 줄 수는 한 자리(상한 · 만들기 한도가 막는다)라서 (account_id, created_at) 인덱스를 따로 두지 않는다.
-- (name_key, tag) 유니크 인덱스의 앞 열이 tagsOf · primariesByKey 의 `where name_key = ?` 를 받는다. primary_of 유니크 인덱스가 findPrimary 와 상한 잠금(for update)을 받는다.
create index if not exists idx_author_profiles_account on author_profiles (account_id, status);
