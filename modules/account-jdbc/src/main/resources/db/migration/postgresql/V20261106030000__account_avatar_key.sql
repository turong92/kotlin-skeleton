-- account_avatar_key (postgresql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- modules:account-jdbc — 프로필 사진(아바타)의 업로드 키 (storage 가 있고 앱이 켠 경우에만 값이 생긴다, 없으면 NULL). 탈퇴 익명화(erase)가 NULL 로 비운다.
-- 키 색인: 「이 키가 어딘가에 붙어 있나」(UploadedKeyUsage 의 버려진 업로드 정리)가 계정 표 전체를 읽지 않게.
alter table accounts add column avatar_key varchar(512);
create index idx_accounts_avatar_key on accounts (avatar_key);
