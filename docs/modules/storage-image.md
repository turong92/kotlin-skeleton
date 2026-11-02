# storage-image

업로드한 이미지의 변환본(썸네일 `thumb` · 표시본 `display`)을 만드는 핵심: 설정, `UploadImages` 구현, 변환 작업 처리기, 이미지 크기 검사기. 엔진(`ImageRenderer`)과 상태 저장소(`ImageStatusStore`)는 따로 고른다 — 기본 엔진은 `storage-image-imageio`, 저장은 `storage-image-jdbc`.
이 모듈을 켠 앱은 남에게(공개 영역 · 다른 계정) 원본이 아니라 EXIF 없는 변환본만 준다. 설계: [files-design](../superpowers/specs/2026-11-02-files-design.md).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-image"))` |
| 함께 오는 모듈 | `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 꺼짐. `skeleton.storage.image.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | `skeleton.storage.image.enabled=true` 로 켜면 `ImageRenderer` 와 `ImageStatusStore` 빈이 있어야 한다(`storage-image-imageio` · `storage-image-jdbc`) — 없으면 기동 실패. |
| 교체 지점 | 없음 |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/storage` |
| 테스트 | `modules/storage-image/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
