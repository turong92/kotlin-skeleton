# persistence-jooq

`DSLContext` 위에 `created_at` · `updated_at` 을 채우는 `JooqAuditRecordListener` 를 얹고, MySQL `datetime` 을 UTC `Instant` 로 바꾸는 `UtcInstantConverter` 를 준다.
`main` 에는 이 런타임 부품만 있다. 예시 스키마와 생성 코드는 test 소스 세트에만 있고, 코드 생성 레시피는 문서에 있다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:persistence-jooq"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | `DataSource` (코드 생성에는 DB 가 필요 없다). |
| 교체 지점 | `JooqAuditRecordListener` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/persistence-jooq/src/test` |

자세히: [jOOQ 코드 생성 레시피](../persistence-jooq.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
