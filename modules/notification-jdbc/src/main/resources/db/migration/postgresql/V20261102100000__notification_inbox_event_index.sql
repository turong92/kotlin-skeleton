-- modules:notification-jdbc — 이벤트 하나의 저장 · 읽음 줄 수(NotificationInboxRepository.eventStats, 대량 공지의 열람 통계)를 위한 색인. 데이터는 옮기지 않는다.
-- 줄이 아주 많은 표에서는 색인 만들기가 표 크기에 비례해 오래 걸리고 그동안 쓰기가 느려지거나 막힐 수 있다 - 한가한 시간에 배포한다
create index if not exists idx_notification_inbox_event on notification_inbox (event_id);
