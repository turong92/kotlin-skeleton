-- account_avatar_key (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:account-jdbc — 프로필 사진(아바타)의 업로드 키 (storage 가 있고 앱이 켠 경우에만 값이 생긴다, 없으면 NULL). 탈퇴 익명화(erase)가 NULL 로 비운다.
-- 키 색인: varchar(512) 는 utf8mb4 로 2048바이트라 색인 한도(3072) 안이다 - 접두사 색인이 필요 없다.
alter table accounts add column avatar_key varchar(512);
create index idx_accounts_avatar_key on accounts (avatar_key);
