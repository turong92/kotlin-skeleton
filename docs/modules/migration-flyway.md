# migration-flyway

`migration` 의 Flyway 구현이다: 검증 오류 시 로컬에서만 clean 하는 옵트인 전략과 마이그레이션 파일 이름 규칙(`V<UTC 14자리>__<snake_case>.sql`) 검사.
Flyway 의 `out-of-order` 같은 사용법 선택은 앱 yml 이 정한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:migration-flyway"))` |
| 함께 오는 모듈 | `migration`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | 클래스패스에 Flyway (`spring-boot-starter-flyway`). |
| 교체 지점 | `FlywayMigrationStrategy` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/migration-flyway/src/test` |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
