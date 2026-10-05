# json

`JsonCodec`, 임의 JSON 을 담는 `JsonDocument` · `VersionedJsonDocument`, 버전 마이그레이션이 있는 `JsonPayloadRegistry`, JPA · JDBC 변환기를 준다.
외부 HTTP JSON 확장 함수와 OpenAPI 스키마도 있다. 새 웹 스택을 끌고 오지 않는다: webflux · validation · springdoc 은 `platform` 에서 온다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:json"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `JsonCodec`, `JsonPayloadRegistry` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/json/src/test` |

자세히: [JSON 상세](../json.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
