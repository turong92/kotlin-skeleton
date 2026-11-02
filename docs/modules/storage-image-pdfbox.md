# storage-image-pdfbox

PDF 첫 쪽 썸네일 - `storage-image` 의 변환 작업에서 PDFBox 로 1쪽을 그려 `thumb` 으로 둔다. 페이지 수 · 파일 크기 · 렌더 시간 상한이 있고, 기본 꺼짐이다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-image-pdfbox"))` |
| 함께 오는 모듈 | `storage-image`, `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 |
| 기본 동작 | 꺼짐. `skeleton.storage.image.pdf.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | 없음 |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/storage-image-pdfbox/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
