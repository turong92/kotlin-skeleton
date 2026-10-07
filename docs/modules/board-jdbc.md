# board-jdbc

`board` 의 저장소 포트 네 개(`BoardRepository` · `PostRepository` · `CommentRepository` · `ReactionRepository`)를 JDBC(PostgreSQL · MySQL)로 구현한다. 테이블 5 개: `boards` · `board_posts` · `board_post_attachments` · `board_comments` · `board_reactions`.
`board_posts` · `board_comments` 의 `author_ref`(공개 작성자 id, NULL = 이 기능 이전의 행 — 공개 id 는 `author_id`)는 마이그레이션 `V20261007150825__board_author_ref` 가 더한다. 그 열을 채우는 `AuthorAdoptionListener`(이름 `board`)도 이 모듈이 구현한다: `pendingAccounts` 는 `author_ref is null` 이고 톰스톤(`deleted:`)이 아닌 행의 계정 id, `adopt` 는 그 계정의 NULL 행만 채운다(멱등). 탈퇴 고리는 `author_ref` 를 건드리지 않는다.
시각은 `SqlDialect` 로 바인딩한다. 카운터는 같은 트랜잭션의 원자적 UPDATE, 반응 변경은 대상 행을 먼저 잠근다 — 규칙과 근거는 [board](board.md) 의 "Decisions and rejected alternatives".

**스키마 관리**: Flyway 가 모듈 마이그레이션을 적용한다. `schema.sql` 모드(MySQL)에서는 `V20261007150825__board_author_ref.sql` 의 `alter table … add column` 을 복사하지 말고 `create table` 사본에 `author_ref` 열과 인덱스를 접어 넣는다 — MySQL 8.4 에는 `add column if not exists` 가 없어 두 번째 기동에서 실패한다 ([job-queue-jdbc](job-queue-jdbc.md) 의 같은 주의).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:board-jdbc"))` |
| 함께 오는 모듈 | `board`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 저장소 네 개를 등록한다 (앱이 같은 타입의 빈을 두면 그쪽이 쓰인다). |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마 (`spring.flyway.locations: classpath:db/migration/{vendor}`). |
| 교체 지점 | `BoardRepository`, `PostRepository`, `CommentRepository`, `ReactionRepository`, `BoardErasureRepository`, 넘겨받기 고리는 빈 이름 `boardAuthorAdoptionListener` (고리는 모듈마다 하나씩이라 타입이 아니라 이름으로 비킨다) |
| 마이그레이션 | `modules/board-jdbc/src/main/resources/db/migration/postgresql`, `modules/board-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/board` |
| 테스트 | `modules/board-jdbc/src/test`, `modules/board-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`) — 병렬 반응 · 병렬 댓글 카운터 · N+1 없는 목록 · 검색 이스케이프) |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
