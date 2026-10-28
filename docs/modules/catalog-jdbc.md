# catalog-jdbc

`catalog` 의 저장 포트 `CatalogStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 넷 — `catalog_products`(`unique(code)` · `unique(level)`, 등급이 없는 단건 상품은 `level` 이 NULL 이라 여럿) ·
`catalog_product_texts`(상품 × 언어) · `catalog_prices`(`unique(code)` - 구독 줄의 `plan_code` · 단건의 `itemRef` · 결제사 링크의 열쇠) · `catalog_price_provider_refs`(`unique(provider, external_id)` - 결제사 가격 id 는 한 가격에만).
`payment-jdbc` 와 같은 규칙으로 JSON 열 · 부분 인덱스가 없다(PostgreSQL · MySQL 에 같은 모양).

규칙: 같은 상품에 가격을 만드는 쓰기는 상품 줄을 `select … for update` 로 먼저 잠그고 겹침 검사 → 넣기를 한다(동시에 행사를 둘 만들어도 하나만, 시험이 두 DB 로 증명한다) ·
코드 · 등급 · 결제사 가격 id 의 「하나뿐」은 마지막으로 유니크 제약이 지키고 위반은 `CatalogException`(`CODE_TAKEN` · `LEVEL_TAKEN`)으로 옮긴다(PostgreSQL 은 유니크 위반이 호출자 트랜잭션을 abort 시키므로 바로 던진다) ·
판매 중지는 `where selling = true` 조건부 갱신이라 두 번 눌러도 처음 시각이 남는다 · 시각은 `SqlDialect.instantParam` / `readInstant` 로만 읽고 쓴다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:catalog-jdbc"))` |
| 함께 오는 모듈 | `catalog`, `payment`, `platform`, `crypto`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `catalog` 와 `DataSource` · `db-*` 모듈이 있으면 저장소가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcCatalogStore` |
| 마이그레이션 | `modules/catalog-jdbc/src/main/resources/db/migration/postgresql`, `modules/catalog-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/payment` |
| 테스트 | `modules/catalog-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [catalog](catalog.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
