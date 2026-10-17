-- board_public_attachment_retry (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md). 잠긴 파일은 고치지 않고 칸 · 표만 더한다.
-- MySQL 8.4 에는 `add column if not exists` 가 없다 — Flyway 가 한 번만 적용한다.

-- 영구 실패에 상한을 둔다: 시도 횟수를 세고, 실패할수록 다음 시도를 늦춘다(지수, 최대 1시간). 새 쓰기가 줄을 다시 적으면 0 으로 돌아간다.
alter table board_public_attachment_queue add column attempts int not null default 0;
alter table board_public_attachment_queue add column next_attempt_at datetime(6);

-- CDN 캐시 비우기(purge)는 요청 스레드에서 하지 않는다 - 객체 삭제는 동기로 끝내고 비울 공개 키만 여기 적어 비동기로 돌린다 (실패하면 같은 식으로 늦춰 다시 한다).
create table if not exists board_public_purge_queue (
    key_hash        char(64)      not null primary key,   -- sha256(public_key)
    public_key      varchar(1024) not null,
    queued_at       datetime(6)   not null,
    claimed_until   datetime(6),
    attempts        int           not null default 0,
    next_attempt_at datetime(6)
);
