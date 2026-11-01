# inquiry-jdbc

`inquiry` 의 저장 포트 `InquiryRepository` 를 JDBC(PostgreSQL · MySQL)로 구현한다. 표 하나 — `inquiries`(종류 · 본문 · 답장 이메일 · 계정 id · 앱이 붙인 `context` · 언어 · 상태 NEW/DONE · 접수/처리 시각, `(status, created_at)` · `account_id` 색인). 계정 id 에는 외래 키가 없다(계정 모듈에 기대지 않는다 — 탈퇴하면 `inquiry` 의 고리가 연결만 끊는다).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:inquiry-jdbc"))` |
| 함께 오는 모듈 | `inquiry`, `persistence-jdbc`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. `DataSource` 가 있으면 인메모리 저장소 대신 JDBC 저장소가 선다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | `JdbcInquiryRepository` |
| 마이그레이션 | `modules/inquiry-jdbc/src/main/resources/db/migration/postgresql`, `modules/inquiry-jdbc/src/main/resources/db/migration/mysql` |
| 프론트 짝 | `@skeleton/inquiry` |
| 테스트 | `modules/inquiry-jdbc/src/dbTest` (PostgreSQL · MySQL 컨테이너로 두 번 돈다 (`postgresTest`, `mysqlTest`)) |

자세히: [inquiry](inquiry.md) · [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
