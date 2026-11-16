-- video_audio_track (mysql). 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md). 잠긴 V20261104100000__videos.sql 은 고치지 않고 칸만 더한다.
-- modules:video-jdbc — 소리 전용 판(AUDIO 차선) 트랙. 호환 단(compat_*)과 같은 모양이다:
-- audio_status NONE | SKIPPED | QUEUED | RUNNING | READY | FAILED (독립 트랙 — 실패해도 PLAYABLE 은 유지된다), audio_key = a-<16>/index.m3u8 의 저장소 키.
-- 기존 영상은 NONE 으로 시작한다 — 설정(skeleton.video.audio.enabled)을 켜면 청소가 소리가 있는 재생 가능 영상을 QUEUED 로 올리고, 소리가 없는 영상은 SKIPPED 로 닫는다.
-- MySQL 8.4 에는 `add column if not exists` 가 없다 — Flyway 가 한 번만 적용한다.
alter table videos add column audio_status varchar(16) not null default 'NONE';
alter table videos add column audio_key varchar(255);
alter table videos add column audio_bytes bigint;
create index idx_videos_audio on videos (audio_status);
