# coupon-jdbc

`coupon` 의 저장 포트 `CouponStore` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 넷 — `coupon_definitions`(정의, 단계 `stages_json` · 범위 `scope_json` 은 `*_json text`) ·
`coupons`(쿠폰함의 한 장, `unique(source_type, source_ref)` 가 지급 건 재시도에도 한 장으로 접는다) · `coupon_codes` · `coupon_code_redemptions`(코드 하나를 계정당 한 번).

규칙: 상태 전이는 모두 `where status = …` / `where holder_ref = … and status in (…)` 조건부 갱신이라 동시에 와도 한쪽만 이긴다(예약 · 확정 · 풀기 · 되돌리기 · 회수) ·
발급 한도 · 계정당 한도는 정의 줄을 `select … for update` 로 잠그고 센다(계정당 수도 잠금 읽기로 센다 — 집계는 PostgreSQL 이 `for update` 를 거절하고 MySQL 의 스냅샷 읽기는 낡을 수 있다) ·
넣기는 `SqlDialect.insertIgnore` 이고 넣었는지는 내가 만든 id 가 있는지로 본다(MySQL 은 이미 있는 행에도 1 을 돌려주고 PostgreSQL 은 유니크 위반 예외가 트랜잭션을 abort 시킨다) ·
쿠폰은 풀려도 `holder_ref` 를 지우지 않는다(늦은 결제 확정이 마지막 주인을 찾는다) · 인덱스는 `coupons(account_id, status)` · `coupons(holder_ref)` · `coupons(definition_id, account_id)`.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:coupon-jdbc"))` |
| 함께 오는 모듈 | `coupon`, `platform`, `persistence-jdbc`, `json` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `coupon` 과 `DataSource` · `db-*` 모듈이 있으면 저장소가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcCouponStore` |
| 마이그레이션 | `modules/coupon-jdbc/src/main/resources/db/migration/postgresql`, `modules/coupon-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 |
| 테스트 | `modules/coupon-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [coupon](coupon.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
