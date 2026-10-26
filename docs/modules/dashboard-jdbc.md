# dashboard-jdbc

`dashboard` 의 저장 포트 둘을 JDBC(PostgreSQL · MySQL)로 구현한다. 테이블 2 개: `dashboard_daily`(외부 출처의 하루치 집계 — `(day, source, kind, dimension)` 이 기본키, 값은 `bigint`, 마지막 적재 시각 `updated_at`) · `dashboard_layouts`(계정별 화면 설정 JSON 한 줄).
**집계 숫자만 담는다** — IP · 이메일 · 계정 id 목록은 일별 표에 없고, 경로는 쿼리 문자열을 뗀 것만 저장한다(적재기의 몫). 계정 id 는 화면 설정 표에만 있고, 계정이 지워질 때 `AccountErasureListener`(`dashboard-jdbc`) 가 그 줄을 지운다.
쓰기는 방언 문법(`on conflict`/`on duplicate key`)에 기대지 않는 두 문장이다: 충돌하면 아무것도 하지 않는 insert(`SqlDialect.insertIgnore`) 다음 값을 덮어쓰는 update. 같은 날을 두 번 적재해도 같은 행이고 값이 바뀌었으면 마지막 값이 남는다. 쓰기는 (날짜 · 출처 · 종류)마다 한 트랜잭션에서 **지우고 넣기**라, 다시 적재했을 때 이번 결과에서 빠진 차원(상위 50 밖으로 밀린 유입처 …)의 옛 줄이 남지 않는다(두 DB 에서 시험). MySQL 의 `dimension` · `account_id` 는 이진 정렬이라 `Google.com` 과 `google.com` 이 한 줄로 합쳐지지 않는다.

이 모듈을 붙이면 `dashboard` 의 적재 작업(`DashboardIngestRunner`)이 켜진다 — 적재기(`DashboardIngestor`) 빈이 있을 때만 일을 한다(출처 모듈이 낸다): 기동 직후 한 번, 그다음 매일 `ingest.at`(대시보드 시간대, `dashboard` 의 설정)에 어제부터 `backfill-days` 일을 다시 적재한다. 없으면 외부 출처의 지난 기간은 `NO_HISTORY` 이고 화면 설정은 브라우저 저장으로 물러난다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:dashboard-jdbc"))` |
| 함께 오는 모듈 | `dashboard`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (적재 설정은 `dashboard` 의 `ingest` 항목) |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 일별 저장소 · 화면 설정 저장소 · 계정 삭제 고리를 등록한다 (앱이 같은 포트의 빈을 두면 그쪽이 쓰인다) |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마 (`spring.flyway.locations: classpath:db/migration/{vendor}`) |
| 교체 지점 | `DashboardDailyStore`, `DashboardLayoutStore` |
| 마이그레이션 | `modules/dashboard-jdbc/src/main/resources/db/migration/postgresql`, `modules/dashboard-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/dashboard` (HTTP 계약: [dashboard-http-contract.md](../dashboard-http-contract.md)) |
| 테스트 | `modules/dashboard-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`) — 같은 행 두 번 쓰기 · 덮어쓰기 · 차원 대소문자 · 화면 설정 저장과 계정 삭제) |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
