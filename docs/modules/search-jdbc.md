# search-jdbc

`search` 의 엔진 포트 `SearchEngine` 과 재색인 포트 `SearchRebuilds` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 둘 — `search_documents`(문서 한 줄, `unique(doc_type, doc_id)`) · `search_rebuilds`(종류별 재색인 진행).

- **PostgreSQL**: 확장 설치 없음. `terms tsvector` = 제목 조각(가중 A) ∥ 본문 조각(가중 B) + GIN. 조각은 `SearchText.grams` 가 만든다 — 덩어리 사이와 각 부분 끝에 표지 낱말 `brk` 를 끼운다(없으면 서로 다른 낱말의 조각이 구문으로 이어져 질의 `abc` 가 `ab bc` 에 맞는다 — 실측). 질의 덩어리 하나 = `phraseto_tsquery('simple', 조각들)`, 여럿은 `&&`, 순위 `ts_rank_cd`.
- **MySQL**: `FULLTEXT(title)` · `FULLTEXT(title, body)` `WITH PARSER ngram`, `MATCH … AGAINST('+"덩어리1" +"덩어리2"' IN BOOLEAN MODE)`, 순위 = 제목 점수 × 2 + 전체 점수. **마이그레이션이 색인을 만들기 전 `SET SESSION innodb_ft_enable_stopword = OFF` 한다** — ngram 은 불용어를 **포함한** 조각을 통째로 버려 `in` `is` `to` 같은 영어 조각이 빠지고 `end` 질의가 `and` 글에 맞는다(실측). 뒷날 DBA 가 다른 세션에서 색인을 다시 만들면(`ALTER TABLE … FORCE` 등) 값이 다시 켜져 있으니 같은 `SET` 을 먼저 한다. `ngram_token_size` 가 2 가 아니면 기동 때 경고한다.
- 쓰기는 **호출자 트랜잭션에 합류**한다(같은 연결) — 한 문장 upsert(`on conflict` / `on duplicate key update`)라 동시에 와도 한 줄이고, 더 옛 `updatedAt` 은 덮지 않는다(재색인의 `reindex` 만 무조건 덮는다). MySQL InnoDB FULLTEXT 는 커밋 뒤에야 보이므로 같은 트랜잭션 안의 질의에는 방금 쓴 문서가 안 잡힐 수 있다.
- 재색인은 `job-queue-jdbc` 에 얹힌 처리기 `search-rebuild` — 큐가 꺼져 있으면(`skeleton.job-queue.enabled=false`) 시작이 실패하고 `JobQueue` 빈이 없다고 말한다. 줄 하나(`search_rebuilds.doc_type`)를 잠가 같은 종류의 동시 시작을 한쪽만 통과시키고, 옛 작업 줄은 `run_id` 가 달라지면 아무것도 하지 않고 끝난다.
- 재색인과 지우기: `delete` 는 그 종류의 `search_rebuilds` 줄을 공유 잠금으로 잡은 뒤 지운다(줄이 없으면 자리 줄을 만든다) - 재색인 쪽은 그 줄을 잠근 채 원본을 읽고 쓰므로, 원본을 읽은 뒤 지워진 문서를 되살리지 못한다. `reindex` 도 `upsert` 와 같은 시각 가드를 쓰고(더 새 쓰기를 옛 쪽 스냅숏으로 덮지 않는다) `indexed_at` 만 갱신한다. `indexed_at` 과 재색인 `started_at` 은 **DB 서버 시계**라 여러 인스턴스의 시계 차이가 끝 정리를 흔들지 않는다.
- 제목 · 본문은 NFKC 정규화한 원문(NUL 등 제어 문자는 공백)으로 저장하고 열 길이에 맞춰 자른다(제목 300 · 본문 16,000).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:search-jdbc"))` |
| 함께 오는 모듈 | `search`, `platform`, `persistence-jdbc`, `job-queue-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `search` 와 `DataSource` · `db-*` 모듈 · `JobQueue` 가 있으면 엔진이 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 큐(`job-queue-jdbc`), 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcSearchEngine`, `JdbcSearchRebuilds`, `SearchRebuildJobHandler` |
| 마이그레이션 | `modules/search-jdbc/src/main/resources/db/migration/postgresql`, `modules/search-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/search-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [search](search.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
