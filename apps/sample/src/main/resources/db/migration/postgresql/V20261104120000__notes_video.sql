-- 노트에 영상 한 편을 붙일 수 있다(modules:video). 영상 표는 모듈(video-jdbc)의 것이라 외래 키를 걸지 않는다 - 붙일 때 내 영상인지 서비스가 확인하고, 노트를 지우거나 영상을 떼면 영상도 지운다.
alter table notes add column video_id varchar(32);
