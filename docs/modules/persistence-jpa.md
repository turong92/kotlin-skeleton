# persistence-jpa

JPA 엔티티의 `created_at` · `updated_at` 자동 채움(생명주기 콜백)과 부분 수정 · fetch graph 도우미를 준다.
JDBC · jOOQ 와는 병행하지 않고 앱이 하나를 고른다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:persistence-jpa"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | `DataSource` 와 JPA 스타터. |
| 교체 지점 | `JpaPartialUpdateExecutor`, `JpaFetchGraphHints` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/persistence-jpa/src/test` |

자세히: [JPA 상세](../persistence-jpa.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
