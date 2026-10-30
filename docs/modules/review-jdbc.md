# review-jdbc

`review` 의 저장 포트 `ReviewStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 일곱 — `reviews`(리뷰 한 줄, `unique(target_type, target_id, account_id)`, 지운 뒤 다시 쓰면 같은 줄이 `PUBLISHED` 로) ·
`review_target_stats`(대상별 집계, `PUBLISHED` 만) · `review_helpful`(도움돼요 표) · `review_purchases`(구매 신호, `unique(ref)`) · `review_reports` · `review_replies` · `review_photos`(신고 · 운영자 답글 · 사진).

규칙: **잠금 순서는 리뷰 줄 → 집계 줄 하나**다 · 새 리뷰 줄은 `SqlDialect.insertIgnore` 로 **넣기를 먼저 하고** 못 넣었을 때만 `for update` 로 잠근다(없는 키를 잠그면 MySQL 의 틈 잠금 때문에 동시에 온 두 쓰기가 교착한다) ·
넣었는지는 내가 만든 id 가 있는지로 본다(MySQL 은 이미 있는 행에도 1 을 돌려주고 PostgreSQL 은 유니크 위반 예외가 트랜잭션을 abort 시킨다) ·
집계는 `update … set review_count = review_count + :d, rating_sum = rating_sum + :s, r<n> = r<n> + :d` 원자적 갱신이라 동시 쓰기에도 어긋나지 않는다(집계 줄은 `insertIgnore` 로 0 에서 만든다) ·
구매 취소는 구매 줄 → 리뷰 줄 순으로 잠그고 취소 뒤 살아 있는 구매를 다시 센다 ·
**남에게 보이는 목록 질의는 `status = 'PUBLISHED'`(보는 사람 자신의 `HIDDEN` 만 더)인 줄만 읽는다** — 걸러 내는 것이 아니라 읽지 않는다 ·
운영 큐(`reported`)는 `status = 'PUBLISHED' and report_count > 0 and (reports_reviewed_at is null or reported_at > reports_reviewed_at)` 로 읽는다 · 다시 쓰기로 되살린 줄은 도움돼요 · 신고 · 답글 · 사진을 비운다 ·
탈퇴 때 리뷰 · 도움돼요 · 신고의 계정을 톰스톤으로 바꾸고 구매 줄을 지운다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:review-jdbc"))` |
| 함께 오는 모듈 | `review`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `review` 와 `DataSource` · `db-*` 모듈이 있으면 저장소가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcReviewStore` |
| 마이그레이션 | `modules/review-jdbc/src/main/resources/db/migration/postgresql`, `modules/review-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/reviews` |
| 테스트 | `modules/review-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [review](review.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
