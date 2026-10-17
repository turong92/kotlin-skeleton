-- sample_board_general_settings (postgresql). 견본 앱의 기존 게시판 `general` 에 새 기능(비회원 쓰기 · 승인 · 글 주인의 댓글 관리)을 켠다.
-- `seed-boards` 는 이미 있는 게시판을 건드리지 않아 설정이 꺼진 채 남는다 - 이 한 장이 기존 DB 에서 켠다. 새 DB 에서는 0 행이고, 그 뒤 시드가 같은 설정으로 만든다.
update boards
set guest_write = 'ALL', post_approval = 'GUESTS', comment_approval = 'GUESTS', owner_moderates_comments = true
where code = 'general';
