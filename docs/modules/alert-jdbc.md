# alert-jdbc

`alert` 의 기록을 DB 에 둔다 — `alerts` 에 (종류 · 키)마다 한 행. 같은 경보가 간격 안에 다시 오면 접고(접은 수를 센다),
여러 인스턴스 · 재시작을 가로질러도 보내기는 하나다. 메모리 저장소(기본)는 이 인스턴스 안에서만 접는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:alert-jdbc"))` |
| 함께 오는 모듈 | `alert`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.alert-jdbc` — [docs/config/modules/alert-jdbc.yml](../config/modules/alert-jdbc.yml) |
| 기본 동작 | 켜짐. 오래된 줄 정리(`retention`)는 꺼져 있다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcAlertStore` |
| 마이그레이션 | `modules/alert-jdbc/src/main/resources/db/migration/postgresql`, `modules/alert-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/alert-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [주인 경보 상세](../alert.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
