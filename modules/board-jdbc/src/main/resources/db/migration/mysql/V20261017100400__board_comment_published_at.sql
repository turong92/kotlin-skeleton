-- board_comment_published_at (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- MySQL 8.4 에는 `add column if not exists` 가 없다 — Flyway 가 한 번만 적용한다 (schema.sql 모드는 docs/job-queue-jdbc.md 의 같은 주의 참고)

-- 한 번이라도 공개된 적이 있는 댓글인가. NULL = 한 번도 공개된 적 없음(승인 대기 → 거절 · 대기 중 삭제) — 이런 행은 관리하는 쪽과 쓴 회원 말고는 스레드에 자리조차 보이지 않는다.
alter table board_comments add column published_at datetime(6);
-- 옛 행은 모두 바로 공개되던 때의 것이라 공개된 적 있음으로 채운다 (옛 동작 유지)
update board_comments set published_at = created_at where published_at is null and status in ('PUBLISHED', 'HIDDEN', 'DELETED');
