# persistence-jdbc

Spring Data JDBC 엔티티의 audit 타임스탬프 콜백과, DB 방언 전략 `SqlDialect` · 연결한 DB 와 방언이 맞는지 보는 `SqlDialectVerifier` 를 준다.
방언 구현은 `db-postgresql` / `db-mysql` 이 하나만 제공한다.
시작할 때 DB 에 못 닿으면(컨테이너가 아직 초기화 중 · `SPRING_DATASOURCE_URL` 틀림 · 망) `DatabaseUnavailableFailureAnalyzer` 가 스택 트레이스 대신 **host:port 와 가능한 원인**(비밀번호 · 사용자는 말하지 않는다)을 보여 준다.

**첫 연결 기다리기(선택, 기본 꺼짐)**: `skeleton.persistence-jdbc.startup-wait.enabled=true` 면 컨텍스트가 시작하기 전에 `spring.datasource.url` 로 첫 연결이 될 때까지 `interval`(1s) 간격으로 `timeout`(60s) 동안 다시 시도한다 — 앱이 막 만든 PostgreSQL 보다 먼저 떠도 재시작 없이 붙는다. 시간이 다 되면 위 메시지에 "기다렸다"가 붙어 기동이 실패한다. 잘못된 비밀번호처럼 기다려도 소용없는 실패는 바로 실패한다. 모듈은 끈 채가 기본이고 켜는 것은 앱의 선택이다(`apps/api` · `apps/sample` 이 켠다, `docs/deploy.md`).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:persistence-jdbc"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.persistence-jdbc` — [docs/config/modules/persistence-jdbc.yml](../config/modules/persistence-jdbc.yml) |
| 기본 동작 | 켜짐. 첫 연결 기다리기는 꺼짐(`startup-wait.enabled`). |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나. |
| 교체 지점 | `JdbcAuditBeforeConvertCallback` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/persistence-jdbc/src/test` |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
