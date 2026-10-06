-- skeleton_account_challenges (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:account-jdbc — 6자리 코드 챌린지: 가입 시도(로그인 전, 가입 id 로 찾는다) · 이메일 변경 · 다시 인증 · 삭제 확인(로그인한 세션 안에서 입력).
-- 코드 원문은 저장하지 않는다 (HMAC-SHA256 hex 만). 가입 시도는 계정이 아니다 — 확인되기 전에는 계정의 어떤 자격도 쓰이지 않는다.
create table if not exists skeleton_account_challenges (
    id           varchar(64)  primary key,                    -- 가입: 가입 id(핸들)의 SHA-256 / 그 밖: 무작위
    purpose      varchar(32)  not null,                       -- sign_up | email_change | reauth | delete_confirm
    -- 가름: 가입은 정규화된 이메일 / 그 밖은 계정 id — 이진 정렬 (악센트 · 대소문자만 다른 주소가 남의 시도를 지우지 않게)
    subject      varchar(254) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */ not null,
    account_id   varchar(40),
    session_id   varchar(64),
    payload      varchar(2000),
    secret       varchar(255),
    code_hash    varchar(64)  not null,
    attempts_left integer     not null,
    resends      integer      not null default 0,
    created_at   datetime(6)  not null,
    expires_at   datetime(6)  not null,
    last_sent_at datetime(6)  not null,
    ip           varchar(64)
    /* [jooq ignore start] */,
    index idx_skeleton_account_challenges_owner (purpose, subject),
    index idx_skeleton_account_challenges_account (account_id),
    index idx_skeleton_account_challenges_expires (expires_at)
    /* [jooq ignore stop] */
);
