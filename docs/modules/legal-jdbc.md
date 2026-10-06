# legal-jdbc

`legal` 의 저장소 포트 둘(`ConsentStore` · `LegalLedger`)을 JDBC(PostgreSQL · MySQL)로 구현한다. 테이블 2 개: `legal_consents`(동의 사건) · `legal_document_versions`(판 장부).
시각은 `SqlDialect` 로 바인딩한다. 두 테이블 모두 **트리거로 더하기만 하도록** 강제한다 — `legal_consents` 는 지우기를 익명화된 줄(`deleted:…`)에만, 고치기를 익명화 · IP/UA 비우기에만 허락하고, 장부는 어떤 고치기 · 지우기도 거절한다.
순번 유니크 키가 동시 기록을 하나로 만든다. 규칙과 근거는 [legal](legal.md) 의 "Decisions and rejected alternatives".

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:legal-jdbc"))` |
| 함께 오는 모듈 | `legal`, `platform`, `persistence-jdbc` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 저장소 둘을 등록한다 (앱이 같은 타입의 빈을 두면 그쪽이 쓰인다). |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마 (`spring.flyway.locations: classpath:db/migration/{vendor}`). **MySQL**: 마이그레이션이 트리거를 만들므로 binlog 가 켜져 있으면 `log_bin_trust_function_creators=1`(또는 마이그레이션 계정에 `SUPER`)이 필요하다 — 없으면 Flyway 가 "You do not have the SUPER privilege and binary logging is enabled" 로 실패한다. |
| 교체 지점 | `ConsentStore`, `LegalLedger` |
| 마이그레이션 | `modules/legal-jdbc/src/main/resources/db/migration/postgresql`, `modules/legal-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | 없음 (HTTP 계약: [legal-http-contract.md](../legal-http-contract.md)) |
| 테스트 | `modules/legal-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`) — 트리거가 실제로 막는지 · 16 스레드 동시 기록이 한 줄 · 철회 경주에서 순번이 이어지는지 · 장부 못 박기 · 익명화 · 보관 기간 정리) |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
