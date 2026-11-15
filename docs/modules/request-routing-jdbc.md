# request-routing-jdbc

`request-routing` 의 창 저장 포트 `LocalRouteWindowStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 하나 — `routing_local_windows`(`opened_by` · `reason` · `opened_at` · `open_until` · `closed_by` · `closed_at`, `open_until` 색인). 로컬 대상(`local: true`)으로 보내는 창을 열려면 이 표가 필요하다 — 저장소 빈이 없으면 창은 늘 닫혀 있고 로컬 대상은 쓸 수 없다(후보 대상만 쓰는 앱은 필요 없다).

- **열린 줄**: `closed_at` 이 비고 `open_until` 이 지금보다 뒤인 줄 가운데 가장 늦게 연 것. 열면(`open`) 열려 있던 줄을 먼저 `closed_by` · `closed_at` 으로 닫고 새 줄을 넣는다 — 호출하는 `LocalWindowService` 의 트랜잭션 안에서(감사 한 줄과 한 묶음). 줄은 지우지 않는다(이력이 곧 기록). 사유(`reason`)는 이 표에만 남는다.
- **켜는 법**: `skeleton.routing.enabled=true` 일 때만 저장소 빈이 선다(모듈을 붙여만 둔 앱은 아무것도 안 선다). 앱이 `LocalRouteWindowStore` 빈을 두면 이쪽이 물러난다.
- **한계 — 창은 「그 서버가 쓰는 DB 의 표」일 뿐이다**: 서버들(서비스 · 인스턴스)이 DB 하나를 같이 쓰면 창을 한 번 열면 전부에 적용되고(다른 인스턴스는 `local.cache-ttl` 안에 따른다), DB 를 나눴으면 서버마다 따로 연다. 서비스별 · 대상별 창은 아직 없다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:request-routing-jdbc"))` |
| 함께 오는 모듈 | `request-routing`, `persistence-jdbc`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (설정은 `request-routing` 이 가진다) |
| 기본 동작 | 꺼짐. `skeleton.routing.enabled=true` 이고 `DataSource` 가 있을 때 JDBC 저장소가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마 (`spring.flyway.locations: classpath:db/migration/{vendor}`). |
| 교체 지점 | `JdbcLocalRouteWindowStore` |
| 마이그레이션 | `modules/request-routing-jdbc/src/main/resources/db/migration/postgresql`, `modules/request-routing-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/request-routing-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [request-routing](request-routing.md) · [쓰는 법](../request-routing.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
