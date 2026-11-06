-- author_profile_avatar_key (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:author-profile-jdbc — 프로필마다 따로인 사진(아바타)의 업로드 키 (storage 가 있고 앱이 켠 경우에만 값이 생긴다, 없으면 NULL). 계정의 사진과 다른 키여야 한다(키로 두 프로필이 이어지면 안 된다).
-- 지운 프로필(markDeleted)은 NULL 로 비운다. 키 색인: 「이 키가 어딘가에 붙어 있나」(UploadedKeyUsage 의 버려진 업로드 정리)가 표 전체를 읽지 않게.
alter table author_profiles add column avatar_key varchar(512);
create index idx_author_profiles_avatar_key on author_profiles (avatar_key);
