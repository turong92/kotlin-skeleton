-- modules:notification-jdbc — 알림 클릭 이동(link) · 같은 열쇠 안 읽은 알림 묶기(collapse) · 보관 정리 · 주제 지우기 색인. 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- 옛 줄은 collapsed_count = 1, 나머지 null 이다 - 데이터를 옮기지 않는다.
-- unread_collapse_key: 안 읽은 묶음 줄만 값이 있고(읽으면 null) (recipient_id, unread_collapse_key) 유니크라 같은 열쇠 안 읽은 줄은 사람마다 하나뿐이다.
-- null 은 유니크에서 겹쳐도 되므로 부분 색인 · 생성 칸 없이 PostgreSQL 과 MySQL 이 같은 모양이다. collapse_key 는 마지막 이벤트의 열쇠(참고용).
alter table notification_inbox add column link varchar(1000);
alter table notification_inbox add column collapse_key varchar(255);
alter table notification_inbox add column unread_collapse_key varchar(255);
-- last_event_id: 마지막으로 접힌 이벤트 id - 그 id 의 재발행(재시도)이 수를 올리지도 새 줄을 만들지도 않게 한다
alter table notification_inbox add column last_event_id varchar(128);
alter table notification_inbox add column collapsed_count integer not null default 1;
alter table notification_inbox add constraint uk_notification_inbox_unread_collapse unique (recipient_id, unread_collapse_key);
-- 재발행 확인(recipient_id, last_event_id)
create index if not exists idx_notification_inbox_last_event on notification_inbox (recipient_id, last_event_id);
-- 보관 정리(deleteOlderThan) · 주제 지우기(deleteByTopic)
create index if not exists idx_notification_inbox_created on notification_inbox (event_created_at);
create index if not exists idx_notification_inbox_topic on notification_inbox (topic);
-- 줄이 아주 많은 표에서는 유니크 · 색인 만들기가 표 크기에 비례해 오래 걸리고 그동안 쓰기가 느려지거나 막힐 수 있다 - 한가한 시간에 배포한다
