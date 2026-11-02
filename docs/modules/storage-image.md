# storage-image

업로드한 이미지의 변환본(썸네일 `thumb` · 표시본 `display`)을 만드는 핵심: 설정, `UploadImages` 구현, 변환 작업 처리기, 이미지 크기 검사기, 내 이미지 조회 `POST /api/v1/storage/images`. 엔진(`ImageRenderer`)과 상태 저장소(`ImageStatusStore`)는 따로 고른다 — 기본 엔진은 `storage-image-imageio`, 저장은 `storage-image-jdbc`.
이 모듈을 켠 앱은 남에게(공개 영역 · 다른 계정) 원본이 아니라 EXIF 없는 변환본만 준다. 설계: [files-design](../superpowers/specs/2026-11-02-files-design.md).

**흐름** 화면이 올림을 마치고 `POST /api/v1/storage/complete` 를 부르면 `ImageHeaderInspector` 가 **머리만 보고** 크기를 확인하고(`max-pixels` · `max-source-bytes` 를 넘으면 디코드 없이 `IMAGE_TOO_LARGE` 로 거절 · 객체 삭제), 통과하면 `ImageCompletionListener` 가 상태 줄(`PENDING`) + 작업 `storage.image.variants` 를 **한 트랜잭션에** 넣는다. 완료 호출을 빼먹은 키는 처음 쓰일 때 `UploadImages.ensure` 가 같은 일을 한다(지연 등록). 같은 키로 동시에 와도 줄 하나 · 작업 하나다.
작업(`ImageVariantProcessor`)은 다시 검사한 뒤(매직 바이트 → 머리의 크기 → 상한) 엔진으로 변환본을 만들어 `<prefix>/<원본 키 sha256 앞 32자>/<이름>.<확장자>` 에 `Cache-Control: private, max-age=31536000, immutable` 로 올리고 줄을 `READY` 로 바꾼다. 같은 작업이 두 번 돌아도 결과가 같다. 내용 때문에 못 만들면(`REJECTED` 깨짐 · 상한, `UNSUPPORTED` 엔진이 못 읽는 형식) 줄에 적고 끝낸다 — **비동기 단계는 원본을 지우지 않는다**(동기 완료 거절만 그 키를 지운다). 저장소 · 일시 오류는 던져서 큐가 다시 하고, 다 쓰면 `FAILED`.

**`ImageRenderer` 확장 지점** `kinds`(이 엔진이 읽는 내용 종류 `DetectedKind`) · `probe(head, kind): ImageInfo`(앞 256KB 로 크기 · 방향 · 움직임, 디코드 없이) · `render(source, info, specs): List<Rendered>`(변환본마다 하나, 메타데이터 없이) - 처리기는 원본 키를 더한 `render(key, source, info, specs)` 를 부르고, 기본 구현은 `source` 스트림으로 위를 부른다(원본을 서명 주소로 넘기는 imgproxy 엔진이 재정의한다). 엔진은 여럿이어도 된다 — `ImageRenderers` 가 `@Order` 순으로 **그 종류를 가진 첫 엔진**에 맡긴다(예: `imageio` 는 JPEG · PNG · GIF · WebP, `pdfbox` 는 PDF, `imgproxy` 는 HEIC · AVIF). 이미지인데 아무 엔진도 못 읽으면 `UNSUPPORTED`(남에게 보이지 않는다). 읽을 수 없는 내용은 `ImageRejectedException(code)` 로 알리고, 저장소 · 일시 오류는 그대로 던진다.
**여러 형식**: 엔진은 변환본마다 같은 그림의 다른 형식을 `Rendered.alternates` 로 더 낼 수 있다(imgproxy 엔진의 WebP). 처리기가 함께 올리고 상태 줄(`StoredVariant.alt`)에 적으며, `UploadImageVariant.alternates` 로 보인다. 기본 형식(`contentType`)이 누구나 읽는 형식이고, `UploadImages.signedVariantUrl(key, variant, ttl, accept)` 가 `Accept` 로 고른다.
**움직이는 이미지**는 썸네일을 첫 장으로 만들고 `display` 는 움직임을 남기고 메타데이터를 걷어낸 사본(`AnimatedImageCleaner`)이다 - 원본이 남에게 가지 않는다. 상태 줄의 `ext` `-`(원본을 가리킴)는 이 변경 전에 만든 줄에만 남아 있다.

**남에게 보이기** 이 모듈이 켜지면 `UploadImages` 빈이 선다 — 공개 영역은 `display`(+ `.thumb`) 변환본을 복사하고(변환 전이면 늦춘다), 변환할 수 없는 이미지는 공개하지 않는다. 비회원 회수는 `forget` 으로 줄과 변환본 폴더를 함께 지운다.

**쓰는 곳** 게시판 응답 `attachmentImages: { <첨부 키>: { width, height, thumb, display } }` - 공개된 글 · 댓글의 **변환이 끝난 이미지**만(`PublicUploads.imagesOf`), 주소는 공개 주소, `width` · `height` 는 `display` 사본의 크기. `attachmentUrls`(= `display`)는 그대로다. 리뷰 사진 `GET /api/v1/reviews/{id}/photos/{index}?variant=thumb|display`(기본 `display`)는 변환본 서명 주소로 302 - 변환이 끝나기 전(또는 만들 수 없는 이미지)에는 **올린 사람 · 운영자만 원본, 그 밖은 404**(이때 줄이 없으면 변환을 시작시킨다), 이미지가 아닌 첨부(pdf · txt)는 늘 원본. 이 모듈이 없으면 둘 다 지금과 같다.
**형식 협상** 엔진이 WebP 도 만들면(imgproxy) `POST /api/v1/storage/images` 와 리뷰 사진 302 는 요청 `Accept` 에 `image/webp` 가 명시돼 있을 때 WebP 주소를 내고 `Vary: Accept` 를 단다. 게시판 공개 영역은 정적 CDN 이라 협상이 안 되어 WebP 사본을 **따로 복사**하고(`<공개 키>.alt.webp` · `.thumb.alt.webp`; `publicKeysOf` · `unpublish` · 전체 대조가 사본까지 센다) 응답 `attachmentImages[키].alternates["image/webp"] = { thumb, display }` 로 알린다. 변환 중인 이미지는 `attachmentUrls` 에서 빠지고 `attachmentsPending` 에 실린다.
**PDF** 공개 영역은 PDF 를 **원본 그대로** 복사한다(문서 자체가 공개 대상이고 EXIF 같은 이미지 메타데이터 문제가 아니다) - `storage-image-pdfbox` 를 켜도 공개 영역에 PDF 썸네일 사본을 더하지 않는다(`attachmentImages` 에도 PDF 는 빠진다). 변환본(`thumb` · `display`)은 `POST /api/v1/storage/images` 로 편집 화면이 미리보기에 쓴다.

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
