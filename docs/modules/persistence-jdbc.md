# persistence-jdbc

Spring Data JDBC 엔티티의 audit 타임스탬프 콜백과, DB 방언 전략 `SqlDialect` · 연결한 DB 와 방언이 맞는지 보는 `SqlDialectVerifier` 를 준다.
방언 구현은 `db-postgresql` / `db-mysql` 이 하나만 제공한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:persistence-jdbc"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나. |
| 교체 지점 | `JdbcAuditBeforeConvertCallback` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/persistence-jdbc/src/test` |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
