-- modules:account-jdbc — 연락받을 이메일 (skeleton.account.contact-email.enabled). 로그인 수단 · 복구 수단 · 중복 판정이 아니다: 유니크 제약도 인덱스도 없다.
-- 확인된 주소만 저장한다 (6자리 코드를 입력한 뒤). 탈퇴 익명화(erase)가 둘 다 NULL 로 비운다.
alter table accounts add column contact_email varchar(254);
alter table accounts add column contact_email_verified_at timestamptz;
