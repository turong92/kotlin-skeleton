# migration

도구에 중립인 마이그레이션 공통 규칙이다: DB 를 지우는 설정(`clean-on-validation-error`, `spring.flyway.clean-disabled=false`, `spring.liquibase.drop-first=true`)이 허용 프로필 밖에서 켜지면 시작을 막는 가드.
Flyway 쪽 구현은 `migration-flyway` 가 맡는다. Flyway 기본값은 바꾸지 않는다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:migration"))` |
| 함께 오는 모듈 | 없음 |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.migration` — [docs/config/modules/migration.yml](../config/modules/migration.yml) |
| 기본 동작 | 켜짐 (가드). |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | 없음 |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/migration/src/test` |

자세히: [스키마 관리](../schema-management.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
