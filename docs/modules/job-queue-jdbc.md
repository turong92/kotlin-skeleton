# job-queue-jdbc

`jobs` 테이블 기반 재시도 큐다: `JobQueue` · `JobHandler`, `FOR UPDATE SKIP LOCKED`, 백오프, DEAD 처리. 여러 인스턴스에서 안전하다.
핸들러는 멱등이어야 한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:job-queue-jdbc"))` |
| 함께 오는 모듈 | `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.job-queue` — [docs/config/modules/job-queue-jdbc.yml](../config/modules/job-queue-jdbc.yml) |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JobQueue` |
| 마이그레이션 | `modules/job-queue-jdbc/src/main/resources/db/migration/postgresql`, `modules/job-queue-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/job-queue-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [재시도 큐 상세](../job-queue-jdbc.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
