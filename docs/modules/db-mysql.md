# db-mysql

MySQL 방언이다. 드라이버 · Flyway MySQL 지원 · `SqlDialect` · Data JDBC 시간 변환을 주고, 세션을 UTC 로 고정한다.
앱은 `db-postgresql` 과 `db-mysql` 중 정확히 하나만 끼운다. 방언 계약 `SqlDialect` 가 `persistence-jdbc` 에 있어서 JPA · jOOQ 앱도 이 모듈을 쓰면 `persistence-jdbc` 가 함께 온다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:db-mysql"))` |
| 함께 오는 모듈 | `persistence-jdbc`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐 (정확히 하나). |
| 부팅에 필요한 것 | 연결한 DB 가 MySQL 이어야 한다 (`SqlDialectVerifier` 가 어긋남을 짚는다). |
| 교체 지점 | `JdbcCustomConversions` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/db-mysql/src/test` |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
