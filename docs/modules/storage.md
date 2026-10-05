# storage

벤더 중립 저장소 계약(`ObjectStorage`, `PresignedStorage`, `StoragePublicUrlResolver`)과 파일 크기 · 확장자 · content-type 검증이다.
실제 저장소는 `storage-s3` 같은 어댑터가 구현한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage"))` |
| 함께 오는 모듈 | 없음 |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.storage` — [docs/config/modules/storage.yml](../config/modules/storage.yml) |
| 기본 동작 | 켜짐. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `StorageFileValidator` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/storage/src/test` |

자세히: [S3 저장소 상세](../storage-s3.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
