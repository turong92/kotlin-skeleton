-- author_profile_avatar_key (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:author-profile-jdbc — 프로필마다 따로인 사진(아바타)의 업로드 키 (storage 가 있고 앱이 켠 경우에만 값이 생긴다, 없으면 NULL). 계정의 사진과 다른 키여야 한다(키로 두 프로필이 이어지면 안 된다).
-- 지운 프로필(markDeleted)은 NULL 로 비운다. varchar(512) 는 utf8mb4 로 2048바이트라 색인 한도(3072) 안이다.
alter table author_profiles add column avatar_key varchar(512);
create index idx_author_profiles_avatar_key on author_profiles (avatar_key);
