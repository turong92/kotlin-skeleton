-- modules:account-jdbc — 로그인 아이디 (skeleton.account.login-id.enabled). 기능을 꺼 둔 앱에서도 열과 표는 생기고 값은 비어 있다. 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- 소문자로 정규화한 값(정규화는 서비스 몫). 없을 수 있다(아직 안 정한 계정 · 기능을 끈 앱) — 유니크는 NULL 을 여럿 허용한다. 탈퇴 익명화(erase)가 NULL 로 비운다
-- 이진 정렬: MySQL 기본 정렬은 대소문자 · 악센트를 같게 봐서 다른 문자열이 같은 아이디로 겹친다 (이메일 열과 같은 이유 · 같은 마커 — jOOQ DDLDatabase 가 못 읽는 collate 절을 가린다)
alter table accounts add column login_id varchar(30) /* [jooq ignore start] */ character set utf8mb4 collate utf8mb4_bin /* [jooq ignore stop] */;
alter table accounts add constraint uq_accounts_login_id unique (login_id);

-- 한 번이라도 쓰인 아이디의 해시. 원문도 계정 id 도 없다(지워진 계정과 이어지지 않는다) — 아이디를 정하는 순간 같은 트랜잭션에서 한 줄을 넣고 줄은 지우지 않는다.
-- 해시는 서버 비밀(skeleton.account.blocks.secret)의 HMAC-SHA256 hex. 개인정보 처리방침에 적는다 (docs/accounts.md)
create table if not exists account_used_login_ids (
    hash    varchar(64) primary key,
    used_at datetime(6) not null
);
