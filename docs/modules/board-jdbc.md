# board-jdbc

`board` 의 저장소 포트 네 개(`BoardRepository` · `PostRepository` · `CommentRepository` · `ReactionRepository`)를 JDBC(PostgreSQL · MySQL)로 구현한다. 테이블 5 개: `skeleton_boards` · `skeleton_board_posts` · `skeleton_board_post_attachments` · `skeleton_board_comments` · `skeleton_board_reactions`.
시각은 `SqlDialect` 로 바인딩한다. 카운터는 같은 트랜잭션의 원자적 UPDATE, 반응 변경은 대상 행을 먼저 잠근다 — 규칙과 근거는 [board](board.md) 의 "Decisions and rejected alternatives".

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:board-jdbc"))` |
| 함께 오는 모듈 | `board`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 저장소 네 개를 등록한다 (앱이 같은 타입의 빈을 두면 그쪽이 쓰인다). |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마 (`spring.flyway.locations: classpath:db/migration/{vendor}`). |
| 교체 지점 | `BoardRepository`, `PostRepository`, `CommentRepository`, `ReactionRepository` |
| 마이그레이션 | `modules/board-jdbc/src/main/resources/db/migration/postgresql`, `modules/board-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/board` |
| 테스트 | `modules/board-jdbc/src/test`, `modules/board-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`) — 병렬 반응 · 병렬 댓글 카운터 · N+1 없는 목록 · 검색 이스케이프) |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
