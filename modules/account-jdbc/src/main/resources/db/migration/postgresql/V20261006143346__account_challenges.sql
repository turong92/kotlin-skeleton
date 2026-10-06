-- account_challenges (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:account-jdbc — 6자리 코드 챌린지: 가입 시도(로그인 전, 가입 id 로 찾는다) · 이메일 변경 · 다시 인증 · 삭제 확인(로그인한 세션 안에서 입력).
-- 코드 원문은 저장하지 않는다 (HMAC-SHA256 hex 만). 가입 시도는 계정이 아니다 — 확인되기 전에는 계정의 어떤 자격도 쓰이지 않는다.
create table if not exists account_challenges (
    id           varchar(64)  primary key,                    -- 가입: 가입 id(핸들)의 SHA-256 / 그 밖: 무작위
    purpose      varchar(32)  not null,                       -- sign_up | email_change | reauth | delete_confirm
    subject      varchar(254) not null,                       -- 가입: 정규화된 이메일 / 그 밖: 계정 id
    account_id   varchar(40),
    session_id   varchar(64),                                 -- 세션 코드가 묶인 세션 (없으면 null)
    payload      varchar(2000),                               -- 가입: 프로필 JSON / 이메일 변경: 새 주소
    secret       varchar(255),                                -- 가입: 이 시도에서 입력된 비밀번호의 해시
    code_hash    varchar(64)  not null,
    attempts_left integer     not null,
    resends      integer      not null default 0,
    created_at   timestamptz  not null,
    expires_at   timestamptz  not null,
    last_sent_at timestamptz  not null,
    ip           varchar(64)
);
create index if not exists idx_account_challenges_owner on account_challenges (purpose, subject);
create index if not exists idx_account_challenges_account on account_challenges (account_id);
create index if not exists idx_account_challenges_expires on account_challenges (expires_at);
