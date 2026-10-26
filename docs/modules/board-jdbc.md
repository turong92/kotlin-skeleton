# board-jdbc

`board` 의 저장소 포트 네 개(`BoardRepository` · `PostRepository` · `CommentRepository` · `ReactionRepository`)를 JDBC(PostgreSQL · MySQL)로 구현한다. 테이블: `boards` · `board_posts` · `board_post_attachments` · `board_comments` · `board_reactions`, 그리고 새 기능의 `board_revisions` · `board_revision_attachments`(고친 판) · `board_comment_attachments`(댓글 첨부). 포트 다섯 개(`BoardRevisionRepository` 포함)를 구현한다.
`board_posts` · `board_comments` 의 `author_ref`(공개 작성자 id, NULL = 이 기능 이전의 행 — 공개 id 는 `author_id`)는 마이그레이션 `V20261007150825__board_author_ref` 가 더한다. 그 열을 채우는 `AuthorAdoptionListener`(이름 `board`)도 이 모듈이 구현한다: `pendingAccounts` 는 `author_ref is null` 이고 톰스톤(`deleted:`)이 아닌 행의 계정 id, `adopt` 는 그 계정의 NULL 행만 채운다(멱등). 탈퇴 고리는 `author_ref` 를 건드리지 않는다.
시각은 `SqlDialect` 로 바인딩한다. 카운터는 같은 트랜잭션의 원자적 UPDATE, 반응 변경은 대상 행을 먼저 잠근다 — 규칙과 근거는 [board](board.md) 의 "Decisions and rejected alternatives".

새 기능의 마이그레이션(모두 새 V 파일, 두 방언 — 잠긴 파일은 고치지 않는다): `V20261017100000__board_guest_authors`(글 · 댓글 `guest_name` · `guest_secret_hash`, `boards.guest_write`), `V20261017100100__board_moderation`(`boards` 승인 셋 · `owner_moderates_comments`, 글 `comment_approval`, 글 · 댓글 `moderated_by`, `idx_board_comments_status`), `V20261017100200__board_revisions`, `V20261017100300__board_comment_attachments`(`board_comments.attachment_count`), `V20261017100400__board_comment_published_at`(`board_comments.published_at` — 한 번도 공개된 적 없는 댓글을 가린다, 옛 행은 공개된 적 있음으로 백필). 새 열은 모두 NULL 허용이거나 `not null default` 라 옛 행은 「모두 꺼짐」으로 읽힌다(업그레이드 시험 `MigrationUpgradeIntegrationTest` 가 확인). 비회원 행(`author_id` 가 `guest:`)은 넘겨받기(`pendingAccounts`)가 고르지 않는다. 판을 덮어쓰는 것과 승인이 동시에 와도 대상 행 잠금(글 → 댓글 순서)이 줄을 세우고, 승인은 잠근 뒤에 판을 읽는다(MySQL REPEATABLE READ 의 스냅샷이 잠금 전에 잡히지 않게).

**스키마 관리**: Flyway 가 모듈 마이그레이션을 적용한다. `schema.sql` 모드(MySQL)에서는 `V20261007150825__board_author_ref.sql` 과 위 새 기능 파일들의 `alter table … add column` 을 복사하지 말고 `create table` 사본에 새 열(`author_ref` · `guest_*` · 승인 셋 · `moderated_by` · `attachment_count`)과 인덱스를 접어 넣는다 — MySQL 8.4 에는 `add column if not exists` 가 없어 두 번째 기동에서 실패한다 ([job-queue-jdbc](job-queue-jdbc.md) 의 같은 주의).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:board-jdbc"))` |
| 함께 오는 모듈 | `board`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 저장소 네 개를 등록한다 (앱이 같은 타입의 빈을 두면 그쪽이 쓰인다). |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마 (`spring.flyway.locations: classpath:db/migration/{vendor}`). |
| 교체 지점 | `BoardRepository`, `PostRepository`, `CommentRepository`, `ReactionRepository`, `BoardRevisionRepository`, `BoardErasureRepository`, 넘겨받기 고리는 빈 이름 `boardAuthorAdoptionListener` (고리는 모듈마다 하나씩이라 타입이 아니라 이름으로 비킨다) |
| 마이그레이션 | `modules/board-jdbc/src/main/resources/db/migration/postgresql`, `modules/board-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/board` |
| 테스트 | `modules/board-jdbc/src/test`, `modules/board-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`) — 병렬 반응 · 병렬 댓글 카운터 · 검색 이스케이프; N+1 없는 목록은 `src/postgresTest` 에서 PostgreSQL 한 벌만 — MySQL 로 찍은 프로젝트에는 없다) |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
