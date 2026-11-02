# storage-image

업로드한 이미지의 변환본(썸네일 `thumb` · 표시본 `display`)을 만드는 핵심: 설정, `UploadImages` 구현, 변환 작업 처리기, 이미지 크기 검사기, 내 이미지 조회 `POST /api/v1/storage/images`. 엔진(`ImageRenderer`)과 상태 저장소(`ImageStatusStore`)는 따로 고른다 — 기본 엔진은 `storage-image-imageio`, 저장은 `storage-image-jdbc`.
이 모듈을 켠 앱은 남에게(공개 영역 · 다른 계정) 원본이 아니라 EXIF 없는 변환본만 준다. 설계: [files-design](../superpowers/specs/2026-11-02-files-design.md).

**흐름** 화면이 올림을 마치고 `POST /api/v1/storage/complete` 를 부르면 `ImageHeaderInspector` 가 **머리만 보고** 크기를 확인하고(`max-pixels` · `max-source-bytes` 를 넘으면 디코드 없이 `IMAGE_TOO_LARGE` 로 거절 · 객체 삭제), 통과하면 `ImageCompletionListener` 가 상태 줄(`PENDING`) + 작업 `storage.image.variants` 를 **한 트랜잭션에** 넣는다. 완료 호출을 빼먹은 키는 처음 쓰일 때 `UploadImages.ensure` 가 같은 일을 한다(지연 등록). 같은 키로 동시에 와도 줄 하나 · 작업 하나다.
작업(`ImageVariantProcessor`)은 다시 검사한 뒤(매직 바이트 → 머리의 크기 → 상한) 엔진으로 변환본을 만들어 `<prefix>/<원본 키 sha256 앞 32자>/<이름>.<확장자>` 에 `Cache-Control: private, max-age=31536000, immutable` 로 올리고 줄을 `READY` 로 바꾼다. 같은 작업이 두 번 돌아도 결과가 같다. 내용 때문에 못 만들면(`REJECTED` 깨짐 · 상한, `UNSUPPORTED` 엔진이 못 읽는 형식) 줄에 적고 끝낸다 — **비동기 단계는 원본을 지우지 않는다**(동기 완료 거절만 그 키를 지운다). 저장소 · 일시 오류는 던져서 큐가 다시 하고, 다 쓰면 `FAILED`.

**`ImageRenderer` 확장 지점** `kinds`(이 엔진이 읽는 내용 종류 `DetectedKind`) · `probe(head, kind): ImageInfo`(앞 256KB 로 크기 · 방향 · 움직임, 디코드 없이) · `render(source, info, specs): List<Rendered>`(변환본마다 하나, 메타데이터 없이). 엔진은 여럿이어도 된다 — `ImageRenderers` 가 `@Order` 순으로 **그 종류를 가진 첫 엔진**에 맡긴다(예: `imageio` 는 JPEG · PNG · GIF · WebP, `pdfbox` 는 PDF, `imgproxy` 는 HEIC · AVIF). 이미지인데 아무 엔진도 못 읽으면 `UNSUPPORTED`(남에게 보이지 않는다). 읽을 수 없는 내용은 `ImageRejectedException(code)` 로 알리고, 저장소 · 일시 오류는 그대로 던진다.
**움직이는 이미지**는 썸네일만 첫 장으로 만들고 `display` 는 원본을 가리킨다.

**남에게 보이기** 이 모듈이 켜지면 `UploadImages` 빈이 선다 — 공개 영역은 `display`(+ `.thumb`) 변환본을 복사하고(변환 전이면 늦춘다), 변환할 수 없는 이미지는 공개하지 않는다. 비회원 회수는 `forget` 으로 줄과 변환본 폴더를 함께 지운다.

**한계** `ImageIoRenderer` 는 JVM 의 headless AWT 를 쓴다(네이티브 이미지에서는 뺀다). 상태 줄이 있는 키는 완료 호출이 저장소를 다시 읽지 않는다. 프로젝트 여럿에서도 설정은 공통이고 줄 · 저장소는 프로젝트마다다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-image"))` |
| 함께 오는 모듈 | `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.storage.image` ([설정 블록](../config/modules/storage-image.yml)) |
| 기본 동작 | 꺼짐. `skeleton.storage.image.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | `skeleton.storage.image.enabled=true` 로 켜면 `ImageRenderer` 와 `ImageStatusStore` 빈이 있어야 한다(`storage-image-imageio` · `storage-image-jdbc`) — 없으면 기동 실패, 메시지가 얹을 모듈을 말한다. 저장소(`PresignedStorage`, `storage-s3`)가 없으면 변환본도 없다. |
| 교체 지점 | `UploadImages`, `ImageRenderer`, `ImageHeaderInspector` |
| 마이그레이션 | 없음 (`storage-image-jdbc` 가 가진다) |
| 프론트 짝 | `@skeleton/storage` |
| 테스트 | `modules/storage-image/src/test` (엔진을 쓰는 시험은 `modules/storage-image-imageio/src/test`) |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
