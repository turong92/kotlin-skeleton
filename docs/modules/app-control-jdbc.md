# app-control-jdbc

`app-control` 의 저장소 포트 `AppControlStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. **`skeleton.app-control.enabled=true` 인 앱에서만 선다** — 꺼진 앱은 이 모듈이 없는 앱과 같다.
표 하나: `app_control_entries`(`name` 기본 키 — `flag:<키>` · `maintenance` · `platform:<이름>`, `payload` 그 종류의 JSON 글, 마지막으로 바꾼 `updated_by` · `updated_at`). 읽기는 `select … order by name` 한 번(캐시가 통째로 바꿔 끼운다), 쓰기는 「없으면 넣고(두 방언 공통 insert-ignore) 이어서 덮어쓴다」라 처음 만드는 두 요청이 겹쳐도 오류 없이 마지막이 이긴다. 운영 기록과 값은 한 트랜잭션(`TransactionTemplate`)이라 기록이 실패하면 값도 남지 않는다. 시각은 PostgreSQL `timestamptz` · MySQL `datetime(6)`(UTC). 받는 사람 · 사용자 데이터는 없다 — 계정 지우기 대상이 아니다. 프로젝트 모드면 프로젝트 스키마마다 표가 생긴다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:app-control-jdbc"))` |
| 함께 오는 모듈 | `app-control`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (설정은 `app-control` 에서 정한다) |
| 기본 동작 | 조건부. `skeleton.app-control.enabled=true` 이고 `DataSource` · `db-*` 모듈이 있으면 저장소가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcAppControlStore` |
| 마이그레이션 | `modules/app-control-jdbc/src/main/resources/db/migration/postgresql`, `modules/app-control-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/app-control` |
| 테스트 | `modules/app-control-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [app-control](app-control.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
