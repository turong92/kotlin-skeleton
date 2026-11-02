# storage-image-pdfbox

PDF 첫 쪽 썸네일 - `storage-image` 의 변환 작업에서 PDFBox 로 1쪽을 그려 변환본(`thumb` · `display`, 메타데이터 없는 JPEG)으로 둔다. 같은 `ImageRenderer` 자리의 엔진이고 PDF 만 맡는다(`kinds = {PDF}`) - `imageio` · `imgproxy` 와 함께 있어도 된다. 기본 꺼짐.

- **작업 큐에서만 돈다** 완료 호출(`/complete`)은 PDF 를 열지 않는다. 변환 작업이 원본을 임시 파일로 받아(바이트 상한을 넘으면 읽다가 거절) 연다. 선언 Content-Type `application/pdf` 로 올린 키도 `ensure`(지연 등록)가 같은 길로 등록한다.
- **상한**(`skeleton.storage.image.pdf.*`, 모두 안전 장치라 모듈 기본): 원본 바이트 · 쪽 수 · 1쪽 크기(포인트, `max-page-pixels`) · 그리는 시간 · PDFBox 메모리(`max-main-memory-bytes` 를 넘는 만큼은 임시 파일로, 그 임시 파일도 `max-temp-bytes` 까지). 쪽 크기와 쪽 수는 문서를 연 뒤 **그리기 전에** 확인한다. 시간은 별도 스레드에서 기다리고, 그리는 쪽도 연산마다 마감을 확인해 스스로 멈춘다.
- **거절** 암호가 있어야 열리는 PDF(`PDF_ENCRYPTED`) · 깨진 PDF(`NOT_AN_IMAGE`) · 쪽 수(`PDF_TOO_MANY_PAGES`) · 크기(`IMAGE_TOO_LARGE`) · 시간(`PDF_RENDER_TIMEOUT`) 는 상태 줄 `REJECTED` 로 끝나고 **원본은 지우지 않는다**. 저장소 읽기 오류는 던져서 큐가 다시 한다. 암호 없이 열리는 제한 PDF(편집 금지 등)는 그대로 그린다.
- **출력** 흰 바탕의 1쪽 JPEG(72dpi 의 두 배까지 키우지 않고 변환본 크기에 맞춘다), 메타데이터 없음. 페이지의 링크 · 스크립트 · 첨부는 실행하지도 담지도 않는다. 상태 줄의 크기는 머리에서 1쪽 `MediaBox`(포인트)를 찾으면 그 값, 못 찾으면 0 x 0 이다.
- **공개 영역 · 리뷰** 에서 PDF 변환본을 어떻게 보이는지는 이 모듈이 정하지 않는다(상태 줄과 변환본까지만) - 쓰는 모듈(board · review)이 정한다.
- **JVM 전용** AWT 로 그리므로 headless 로 돈다(`java.awt.headless` 를 정하지 않았으면 켠다). 글꼴이 박히지 않은 PDF 는 시스템 폰트를 쓰므로 배포 이미지에 기본 폰트가 있어야 글자 모양이 맞는다. 네이티브 이미지에서는 뺀다. 첫 렌더에서 PDFBox 가 시스템 폰트를 훑어 시간이 걸릴 수 있다(`render-timeout` 안에서).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-image-pdfbox"))` |
| 함께 오는 모듈 | `storage-image`, `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.storage.image.pdf` ([설정 블록](../config/modules/storage-image-pdfbox.yml)) |
| 기본 동작 | 꺼짐. `skeleton.storage.image.pdf.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | 없음 (`storage-image` 가 켜져 있어야 변환 작업이 돈다). |
| 교체 지점 | `PdfRenderer` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/storage-image-pdfbox/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
