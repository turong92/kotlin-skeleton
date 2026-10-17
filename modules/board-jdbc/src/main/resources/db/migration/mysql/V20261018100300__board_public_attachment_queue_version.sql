-- board_public_attachment_queue_version (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md). 잠긴 V…100200 은 고치지 않고 칸만 더한다.
-- MySQL 8.4 에는 `add column if not exists` 가 없다 — Flyway 가 한 번만 적용한다.

-- 줄마다 올라가는 번호 - 쓰기가 줄을 다시 적을 때마다 +1. 맞춘 쪽은 「읽은 번호와 같을 때만」 줄을 지운다 (앱 시각이 아니라 번호로 가르므로 시계가 어긋나도 새 쓰기를 잃지 않는다).
alter table board_public_attachment_queue add column version bigint not null default 0;
