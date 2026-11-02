# storage-image-jdbc

`storage-image` 의 이미지 상태 저장소를 JDBC(PostgreSQL · MySQL)로 구현한다 — 이미지 원본 하나당 한 줄(`storage_images`).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-image-jdbc"))` |
| 함께 오는 모듈 | `storage-image`, `persistence-jdbc`, `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 꺼짐. `storage-image` 가 켜져 있을 때 저장소를 등록한다. |
| 부팅에 필요한 것 | `DataSource` 와 `db-*` 모듈 하나, 그리고 이 모듈의 마이그레이션이 적용된 스키마. |
| 교체 지점 | 없음 |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/storage` |
| 테스트 | `modules/storage-image-jdbc/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
