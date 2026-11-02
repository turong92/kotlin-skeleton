# 파일 — 업로드 검사 · 이미지 변환본(썸네일 · 표시본) · 미리보기 — 설계

> 2026-11-02. 요청(형): 「공지 푸시랑 파일 관련해서 먼저 해보자」 — 파일 쪽: 이미지 리사이즈 · 썸네일 · 미리보기.
> 형 원칙: 공개 스켈레톤 · 캡슐화된 조립식 모듈 · 모듈 기본값 중립(꺼짐, 앱 yml 이 켬) · 업계 관행 · 회귀 시험은 돈 · 보안 · 동시성 · 데이터 손실만 · 홈서버 자원 아껴 씀(무거운 변환은 작업 큐로) · 개인 도메인 · 이메일 흔적 없음(`example.com`).
> 대상: kotlin-skeleton(기존 `storage` · `storage-s3` · `platform` · `board` · `review` 에 작은 자리, 새 `storage-image` · `storage-image-jdbc` · `storage-image-imageio` · `storage-image-imgproxy`(선택 엔진) · `storage-image-pdfbox`(PDF 1쪽 썸네일), `review-jdbc`(`UploadedKeyUsage`), `apps/sample` · `apps/workbench`) + react-skeleton(`@skeleton/storage` 1.2.0, `apps/sample`). 기준: kotlin main `a9342ec0`, react main `c552ab3`.
> 읽은 코드: `storage/{ObjectStorageContracts,PresignedStorageContracts,StorageService,StorageFileValidation,StorageKeyOwnership,StoragePublicUploads,StorageGuestCleanup,StorageGuestUploads}.kt` · `storage/web/StorageController.kt` · `docs/modules/storage.md` · `docs/storage-s3.md` · `docs/config/modules/storage.yml` · `platform/common/upload/{PublicUploads,GuestUploads}.kt` · `board/{BoardUploads,BoardPublicAttachments}.kt` · `board/web/BoardDtos.kt` · `review/{ReviewRules,ReviewReader}.kt` · `review/web/ReviewController.kt`(사진 302) · `docs/job-queue-jdbc.md` · `Dockerfile`(temurin 21 alpine) · `build.gradle.kts`(툴체인 21) · `docs/native-image.md` · `apps/sample/.../application.yml`(storage · review) · react `packages/storage`(README · `uploader` · `useUpload`) · `packages/ui/FilePicker` · `apps/sample/src/board/Pictures.tsx` · `apps/sample/src/reviews/ReviewPhoto.tsx`.

## 지금 있는 것 · 빈 것

| 있는 것 | 빈 것 |
|---|---|
| 브라우저가 저장소로 직접 올림(presign PUT · 멀티파트 · 진행률 · 취소), 선언한 Content-Type · 크기를 서명에 넣음 | **서버가 올라온 바이트를 한 번도 보지 않는다** — 매직 바이트 · 이미지 크기(픽셀 폭탄) · 깨진 파일을 모른다(모듈 문서도 「한계선」이라 적어 둠). `ObjectStorage` 에 읽기(GET) 계약이 없다 |
| 검증 = 선언된 크기 · 확장자 · Content-Type(`StorageFileValidator`), 비회원은 이미지 넷 · SVG 금지 | 업로드가 끝났다는 신호가 없다(presign 다음에 서버가 부를 자리 없음) |
| 게시판 공개 영역: 공개된 글 · 댓글 첨부를 **원본 그대로** CDN 영역에 복사 | 원본의 **EXIF(GPS · 기기) 가 공개 영역 · 리뷰 사진 302 로 남에게 그대로 간다**. 썸네일이 없어 목록이 원본(수 MB)을 받는다. 폭 · 높이를 몰라 화면이 밀린다(CLS) |
| 비회원 고아 회수(`g/` 키) | 회원 업로드 고아 회수 없음 · 리뷰는 `UploadedKeyUsage` 를 알리지 않음 |
| react: `FilePicker`(끌어다 놓기) · `useUpload`(진행률) · 견본 `PicturePicker` | 올리기 전 로컬 미리보기 · `srcset` 반응형 이미지 부품 · 「처리 중 / 쓸 수 없는 사진」 표시 |

## 0. 관행 조사 → 권장안

| 물음 | 업계 | 권장 |
|---|---|---|
| 직접 올림 뒤 검사 | Rails ActiveStorage(직접 올림 → 붙일 때 `AnalyzeJob` 이 내용 · 크기 분석), Shrine(캐시에 올림 → 내용으로 MIME 판정 → 승격), Uppy/Transloadit(올림 완료 콜백). 저장소 이벤트(S3 → SQS)는 이식성이 없다(SeaweedFS · R2 다름) | **완료 호출** `POST /storage/complete` 에서 동기로 머리만 검사(HEAD + 앞 256KB 범위 GET → 매직 바이트), 무거운 일은 작업 큐. 완료 호출을 빼먹은 키는 **처음 쓰일 때 지연 등록**(ActiveStorage 의 「처음 요청에 변환」) — 클라이언트를 믿지 않는다 |
| MIME 판정 | 선언 Content-Type · 확장자는 신뢰하지 않는다(OWASP File Upload). 내용의 서명(매직 바이트)과 선언이 같은 계열이어야 받는다. `nosniff` 로 내려준다 | 알려진 형식 표(jpeg · png · gif · webp · avif/heic(`ftyp` 브랜드) · pdf · zip 계열(docx · xlsx · pptx · hwpx) · mp4/mov · webm · mp3 · wav · 텍스트(UTF-8 · NUL 없음))로 판정, 선언과 다르면 거절 + 객체 삭제. SVG · HTML 은 서명이 보이면 이미지로 선언됐어도 거절 |
| 이미지 폭탄 | Pillow: 약 8,900만 픽셀 경고 · 1억 7,900만 오류. sharp: `limitInputPixels` 기본 2억 6,800만. 공통: **머리에서 폭 · 높이를 먼저 읽고 디코드 전에 거절** | 머리에서 크기를 읽어 `max-pixels`(모듈 기본 1억) 넘으면 거절. 디코드는 목표 크기에 맞춘 **부분 표본 추출**(`ImageReadParam.setSourceSubsampling`)로 메모리를 묶는다. 원본 바이트 상한 `max-source-bytes`(기본 50MB) |
| 변환 시점 | 올릴 때 미리(Mastodon · Discourse · WordPress — 정해진 크기 몇 개), 처음 요청 때 만들어 저장(ActiveStorage variant), 요청마다 즉석 + CDN 캐시(imgproxy · thumbor · Cloudflare Images · imgix · Next Image) | **정해진 이름의 변환본 둘(`thumb` · `display`)을 작업 큐로 미리** 만들어 저장소에 둔다. 즉석 변환은 크기 매개변수가 열린 공개 끝점이라 CPU 를 남이 정한다 — 홈서버에 맞지 않는다. 지연 등록이 「처음 쓰일 때」를 맡는다 |
| 변환 위치(엔진) | 앱 안 라이브러리(JDK ImageIO + TwelveMonkeys — 순수 자바, WebP 는 읽기만 · HEIC/AVIF 못 읽음) · libvips(sharp · imgproxy 의 엔진, 메모리 적고 WebP/AVIF/HEIC — 자바 바인딩 `vips-ffm` 은 JDK 23+ FFM 이라 툴체인 21 과 안 맞음) · 외부 서비스(imgproxy 컨테이너, Cloudflare 이미지 변환 — 월 고유 변환 5,000 무료 · 넘으면 1,000 당 $0.50) | **확장 지점 `ImageRenderer`** 하나, 1차 구현은 앱 안 순수 자바(`storage-image-imageio`): 설치 0 · 시험이 어디서나 돈다 · 출력 JPEG(투명이면 PNG). imgproxy · Cloudflare 는 같은 자리의 다음 어댑터 — 「정해야 할 것 1」 |
| EXIF · 회전 | 공개 서비스(인스타그램 · X · Mastodon)는 내보내는 사진에서 메타데이터(GPS 포함)를 지우고, 회전 태그를 픽셀에 반영한다. 원본은 비공개로 보관 | 변환본은 `Orientation` 을 픽셀에 반영한 뒤 **메타데이터 없이** 다시 인코딩(ICC 가 있으면 sRGB 로 바꾼 뒤 버림). **이미지 모듈을 켜면 남에게는 원본을 주지 않는다** — 공개 영역에는 `display` 변환본이, 리뷰 사진 302 는 변환본이 간다. 원본은 올린 사람 · 관리자만 |
| 형식 | WebP 가 JPEG 보다 25~35% 작다(구글 자료), AVIF 는 더 작지만 인코딩이 느리다. 썸네일 320~400px, 표시본 1600~2048px 가 흔하다 | 1차 엔진은 JPEG(품질 82, progressive) / PNG. 크기 모듈 기본 `thumb` 400×400 안 · `display` 1600×1600 안, 키우지 않는다. WebP 는 엔진을 바꾸면 따라온다 |
| 움직이는 GIF · WebP | 썸네일은 첫 장, 표시는 원본(또는 동영상 변환) | 썸네일만 첫 장, `display` 는 원본 그대로(GIF 에는 EXIF 가 없다). 움직임 → 동영상 변환은 범위 밖 |
| 반응형 표시 | `<img srcset sizes width height loading="lazy" decoding="async">` — 폭 · 높이로 자리를 미리 잡아 CLS 를 막는다 | react `StoredImage` 가 서버가 준 변환본 둘 + 폭 · 높이로 그린다 |
| 업로드 미리보기 | 고른 즉시 `URL.createObjectURL` 로 로컬 미리보기, 올리는 동안 진행률, 끝나면 서버 결과(거절 · 처리 중) | `useFilePreview` + `ImageUploadField`(기존 `FilePicker` · `useUpload` 위) |
| 악성 파일 검사 | ClamAV(`clamd` INSTREAM) · 클라우드 스캐너(GuardDuty Malware Protection for S3 등)를 완료 단계에 끼운다 | 1차는 **확장 지점만**: 완료 단계의 `UploadInspector` 목록(내장 = 매직 바이트 · 이미지 크기). 스캐너 어댑터는 다음 |
| 고아 파일 | 올렸지만 어디에도 안 붙은 파일을 일정 시간 뒤 지운다(ActiveStorage `purge_unattached`, S3 수명 규칙) — 「쓰는 곳 전부가 사용 중을 알린다」가 전제 | 1차: 변환본은 원본을 따라 지워진다(비회원 회수가 폴더째). 회원 업로드 회수는 「정해야 할 것 3」 |

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| 검사 | 완료 호출(회원 · 비회원) · 매직 바이트 판정 · 이미지 크기 상한 · 거절이면 객체 삭제 · `UploadInspector` 자리 | ClamAV 어댑터 · 비동기 검사 판정(큰 파일) |
| 변환 | 이미지(jpeg · png · gif · webp 읽기) → `thumb` · `display` (회전 반영 · 메타데이터 제거 · sRGB) · 작업 큐 · 지연 등록 · 상태 표 | WebP/AVIF 출력 · HEIC/AVIF 입력(선택 엔진 `storage-image-imgproxy` 는 1차에 함께, 「결정됨」 1) · 동영상 포스터 · blurhash · 자르기 서버 쪽 |
| 쓰는 곳 | 게시판 공개 영역이 원본 대신 변환본을 복사 · 응답에 변환본 주소와 폭 · 높이 · 리뷰 사진 `?variant=` · 내 업로드 조회 | 작성자 프로필 아바타(지금 아바타 기능 없음) · 다른 도메인 모듈 |
| 수명 | 비회원 회수가 변환본 · 상태 줄까지 지움 · 버려진 회원 업로드 정리(기본 꺼짐, K6) + `review-jdbc` 의 `UploadedKeyUsage` · PDF 1쪽 썸네일(K5) — 「결정됨」 2 · 3 | — |
| 화면 | 완료 호출 · 로컬 미리보기 · `ImageUploadField`(여러 장 · 진행률 · 거절 문구 · 지우기) · `StoredImage`(`srcset`) | 자르기(아바타가 생길 때) · 올리기 전 브라우저에서 줄이기 · 붙여넣기 업로드 |

**비목표**: 즉석 변환 공개 끝점, 원본 덮어쓰기(원본은 그대로 비공개 보관), 동영상 · 문서 변환, 이미지 편집(필터 · 워터마크), 네이티브 이미지에서의 변환(AWT — `storage-image-imageio` 는 JVM 전용, 모듈 문서에 적는다), 기존 presign · 멀티파트 계약 바꾸기.
**반목표**(깨지면 사고): 이미지 모듈을 켠 앱에서 남(비회원 · 다른 계정 · 공개 영역 · CDN)에게 **원본(EXIF · GPS)이 간다** / 선언과 내용이 다른 파일(HTML · SVG 를 `image/png` 로)이 받아진다 / 픽셀 폭탄이 디코드돼 서버 메모리가 넘친다 / 남의 키에 완료를 불러 검사 · 삭제가 일어난다 / 변환 실패 · 거절이 **원본을 지운다**(완료 호출의 동기 거절만 지운다) / 회수가 쓰이는 파일을 지운다 / 이 모듈을 안 붙인 앱의 동작이 바뀐다.

## 2. 모듈 경계 · 확장 지점

```
platform (common.upload: PublicUploads · PrivateUploadAccess · + UploadImages 포트)
   ▲
storage ── 완료 호출 · 매직 바이트 · UploadInspector/UploadCompletionListener · ObjectStorage.read · 변환본 접두사 · 공개 영역이 UploadImages 를 선택적으로 본다
   ▲  (storage-s3: read = 범위 GET, 장식 저장소 둘이 넘겨준다)
storage-image ◀─api── storage-image-jdbc ──impl──▶ job-queue-jdbc, persistence-jdbc
   ▲   (UploadImages 구현 · 작업 처리기 · 내 업로드 조회 HTTP · 설정)
   ├─api── storage-image-imageio (기본 ImageRenderer 구현 — TwelveMonkeys imageio-jpeg · imageio-webp, metadata-extractor)
   ├─api── storage-image-imgproxy (선택 ImageRenderer 구현 — imgproxy 컨테이너, HEIC 입력 · WebP 출력, 서버 하나 더)
   └─api── storage-image-pdfbox (선택 ImageRenderer 구현 — PDF 1쪽 썸네일, 기본 꺼짐, 페이지 수 · 크기 · 렌더 시간 상한)
storage ◀── 버려진 업로드 정리(K6, 기본 꺼짐) ◀── UploadedKeyUsage 구현: review-jdbc 등 사용처
board · review ──▶ platform 의 UploadImages 만 안다(없으면 지금과 같다)
```

| 확장 지점 | 자리 | 고른 이유 |
|---|---|---|
| `ObjectStorage.read(key, range: LongRange? = null): InputStream` | `storage`, 구현 `storage-s3`(`GetObject` + `Range`) · `ProjectRoutedStorage` · `KeyPrefixedPresignedStorage` 가 넘긴다 | 서버가 바이트를 보는 유일한 길. 기본 구현은 `UnsupportedOperationException`(옛 구현이 컴파일되게) |
| `UploadInspector`(`inspect(upload: InspectedUpload): InspectionVerdict` — `Accept` · `Reject(code, message)`) 목록 | `storage`. 내장 = `MagicBytesInspector`(늘), `storage-image` 가 `ImageHeaderInspector`(크기 상한) 를 더한다 | 악성 검사 · 앱 규칙이 같은 자리에 끼운다(형 요청 「악성 파일 검사 확장 지점」). `InspectedUpload` = 키 · 선언 형식 · 판정 형식 · 크기 · 앞 256KB · `open()`(전체 읽기) |
| `UploadCompletionListener`(`onAccepted(upload)`) | `storage` | 검사를 다 통과한 뒤에만 부수 효과(상태 줄 · 작업). 검사기 안에서 부수 효과를 내지 않게 둘을 나눈다 |
| `UploadImages`(`status(key)` · `views(keys)` · `signedVariantUrl(key, variant, ttl)` · `publicVariants(key)` · `ensure(key)`) | `platform` `common.upload`, 구현 `storage-image` | board · review 는 storage 를 모른다(지금 `PublicUploads` 와 같은 선). 빈이 없으면 지금 동작 그대로 |
| `ImageRenderer`(`probe(head): ImageInfo?` · `render(source: () -> InputStream, info, spec): Rendered`) | `storage-image`, 구현 `storage-image-imageio`(기본) · `storage-image-imgproxy`(선택) · `storage-image-pdfbox`(PDF) | 엔진 교체가 이 자리 하나. imgproxy 는 1차에 함께, Cloudflare 이미지는 이번에 안 한다(「결정됨」 1) |
| `ImageStatusStore` | `storage-image`, 구현 `-jdbc` | 레포 관례(포트/어댑터 짝). 인메모리 없음 — `storage.image.enabled=true` 인데 빈이 없으면 기동 실패(「`storage-image-jdbc` 를 얹어라」) |
| `skeleton.storage.image.variants.{thumb,display}` (`max-width` · `max-height` · `quality`) | 설정 | **이름 둘은 계약**(board · review · react 가 이 이름을 안다), 크기 · 품질은 앱이 정한다 |

## 3. 데이터 (`storage-image-jdbc`, 두 방언 짝 V 파일 · `migrations.lock`, 시각 PG `timestamptz` · MySQL `datetime(6)` UTC)

`V<UTC>__storage_images.sql` — 이미지 원본 하나당 한 줄(이미지 아닌 파일은 줄이 없다).

| 칸 | 모양 | 뜻 |
|---|---|---|
| `key_hash` | `char(64)` pk | sha256(원본 키) — 긴 키의 유니크(MySQL 색인 길이). 변환본 경로에도 쓴다 |
| `object_key` | `varchar(1024)` not null | 원본 키 |
| `status` | `varchar(16)` | `PENDING` \| `READY` \| `REJECTED`(내용이 이미지가 아니거나 상한 초과 · 디코드 실패) \| `UNSUPPORTED`(엔진이 못 읽는 형식 — HEIC 등) \| `FAILED`(재시도 소진) |
| `format` | `varchar(16)` null | 판정 형식(`jpeg` · `png` · `gif` · `webp` …) |
| `width` · `height` | `int` null | **회전 반영 뒤** 원본 크기 |
| `animated` | `boolean` | 움직이는 GIF/WebP |
| `variants` | `varchar(400)` null | 만든 변환본 JSON `{"thumb":{"w":400,"h":300,"ext":"jpg"},"display":{…}}` |
| `reason` | `varchar(64)` null | 거절 · 실패 코드(사용자에게 보일 수 있는 코드만, 예외 문구는 로그로) |
| `created_at` · `updated_at` | 시각 | `status, updated_at` 색인(PENDING 이 오래 남은 것 확인 · 정리) |

변환본 키: `<storage.image.prefix>/<key_hash 앞 32자>/<변환본 이름>.<ext>`(모듈 기본 접두사 `variants`, 업로드 `key-prefix` · 공개 `prefix` 와 겹치면 기동 실패). 비밀이 필요 없다 — 원본 키 없이는 계산할 수 없고, 변환본도 비공개(서명 주소로만 읽힌다). 계정 id 는 줄에도 키에도 없다. 프로젝트 모드면 프로젝트 스키마 · 프로젝트 저장소마다.

## 4. 흐름

**완료(동기, 가벼움)** 화면이 PUT 을 마친 뒤 `POST /api/v1/storage/complete {key}` (비회원: `/guest/complete`):
1. 소유 확인 — 회원 `StorageKeyOwnership`, 비회원 `StorageKeyScheme.isGuestKey`(MAC). 아니면 404 `STORAGE.OBJECT_NOT_FOUND`(아무것도 읽지 않는다). 비회원은 IP 한도(`guest.rate-limit`, 발급과 별도 열쇠).
2. 이미지 상태 줄이 이미 있으면 그대로 돌려준다(멱등 · 저장소를 다시 안 읽음).
3. `metadata`(HEAD) → 없으면 404 · 크기가 검증 상한을 넘으면 거절. `read(key, 0..262143)` → 검사기 목록. 하나라도 `Reject` 면 **이 객체를 지우고** 400 `STORAGE.FILE_REJECTED`(`data.errors` 코드 `CONTENT_MISMATCH` · `UNRECOGNIZED_CONTENT` · `IMAGE_TOO_LARGE` · 검사기 코드). 지우는 것은 방금 소유를 확인한 그 키 하나뿐이다.
4. 통과 → `UploadCompletionListener` — `storage-image` 가 이미지면 `PENDING` 줄 + 작업 `storage.image.variants {key}`(같은 트랜잭션, 줄이 이미 있으면 넣지 않음). 응답 `{ key, contentType(판정), sizeBytes, image: { status, width?, height? } | null }`.

**지연 등록** 완료를 부르지 않은 키가 쓰일 때(공개 영역 복사 · 리뷰 사진 · 내 업로드 조회) `UploadImages.ensure(key)` — 줄이 없고 선언 형식이 이미지면 `PENDING` 줄 + 작업(검사는 작업이 한다). 그 순간에는 「아직 아님」으로 답한다.

**변환 작업** `storage.image.variants`(job-queue, 인스턴스당 동시 `max-concurrent` 기본 1 — 세마포어, 기다리는 동안 일꾼 스레드를 잡는다):
1. 줄을 읽는다 — 없거나 `PENDING` 이 아니면 끝(멱등). 원본 `metadata` 가 없으면 줄을 지우고 끝(원본이 지워졌다).
2. 다시 검사한다(클라이언트도 완료 단계도 믿지 않는다): 매직 바이트 → 머리에서 폭 · 높이 · 회전 → `max-pixels` · `max-source-bytes`. 넘거나 아니면 `REJECTED`(원본은 **남긴다** — 비동기 단계에서 사용자 파일을 지우지 않는다. 남에게는 안 보인다). 엔진이 못 읽는 형식은 `UNSUPPORTED`.
3. 변환본마다: 부분 표본 추출로 디코드 → 회전 반영 → sRGB → 긴 변 맞춤 축소(키우지 않음, 고품질 보간) → JPEG(투명이면 PNG) 메타데이터 없이 → `upload`(`Cache-Control: private, max-age=31536000, immutable`). 움직이는 이미지는 `thumb` 만(첫 장), `display` 는 원본을 가리킨다.
4. 줄을 `READY` + 크기 + 변환본으로. 디코드 예외는 `REJECTED`(영구), 저장소 · 일시 오류는 던져서 큐 재시도, 소진되면 `FAILED`(죽은 작업 → `alert` 가 있으면 경보).

**남에게 보이기**(이미지 모듈이 있을 때만 바뀐다):
- **게시판 공개 영역** `StoragePublicUploads.publish(key)`: 이미지 형식이면 `UploadImages.status` — `READY` → 공개 키(지금 이름 그대로)에 **`display` 변환본**을, `<공개 키 꼬리 앞>.thumb.<ext>` 에 `thumb` 을 복사(Content-Type 은 변환본). `PENDING`/줄 없음 → `ensure` 후 재시도 예외(board 의 기존 맞추기 큐가 늦춰 다시 한다). `REJECTED` · `UNSUPPORTED` · `FAILED` → `false`(공개하지 않음 — 지금의 「공개할 수 없는 형식」과 같다). `unpublish` · `publicKeysOf(key)`(새 기본 메서드 = `listOf(publicKeyOf)`) 가 썸네일까지 — board 의 전체 대조가 이것으로 「줄이 가리키는 공개 객체」를 센다(안 그러면 썸네일을 고아로 지운다).
- **리뷰 사진** `GET …/{id}/photos/{index}?variant=thumb|display`(없으면 `display`): 이미지 모듈이 있으면 `READY` 변환본 서명 주소로 302. 아직이면 올린 사람 · 관리자는 원본, **그 밖은 404**(`ensure` 를 건다).
- 비이미지(pdf · txt)는 지금처럼 원본.

**수명** 비회원 회수가 원본을 지울 때 같은 키의 줄과 변환본 폴더(`<prefix>/<hash32>/`)도 지운다. 공개 영역은 board 가 지금처럼 맞춘다. 버려진 회원 업로드 정리는 K6(기본 꺼짐 · 모든 사용처가 `UploadedKeyUsage` 로 답해야 켜진다).

## 5. API

`storage`(지금 경로 옆, 같은 조건 — 서블릿 + Security + `PresignedStorage`):

| 경로 | 응답 · 하는 일 |
|---|---|
| `POST /api/v1/storage/complete` | 본문 `{ key }` → `{ value: { key, contentType, sizeBytes, image: { status, width, height } \| null } }` · 거절 400 `STORAGE.FILE_REJECTED`(객체 삭제됨) · 남의 키 404. 멱등 |
| `POST /api/v1/storage/guest/complete` | 같은 모양, 로그인 없이(비회원 업로드를 켰을 때만, 끄면 401) |

`storage-image`(`storage.image.enabled` + 같은 조건, `skeleton.storage.image.http.enabled=false` 면 빠진다):

| 경로 | 응답 · 하는 일 |
|---|---|
| `POST /api/v1/storage/images` | 본문 `{ keys: string[≤20] }` → `{ value: { items: [{ key, status, width, height, variants: { thumb?: { url, width, height }, display?: {…} } }] } }` — **내 키만**(남의 키 · 모르는 키는 빠진다), 주소는 서명(`presign.download` 수명). 줄이 없는 내 이미지 키는 `ensure` 후 `PENDING`. 편집 화면의 썸네일 · 처리 대기 확인용 |

`board`(응답에 더함, 이미지 모듈이 있을 때만): 글 · 댓글의 `attachmentImages: { [key]: { width, height, thumb, display } }` — 공개된 항목의 `READY` 이미지만, 주소는 공개 주소. `attachmentUrls`(=`display`) 는 그대로.
`review`: `GET /{id}/photos/{index}?variant=` (위 §4).
`StorageFileValidationErrorCode` 에 `CONTENT_MISMATCH` · `UNRECOGNIZED_CONTENT` · `IMAGE_TOO_LARGE` · `INSPECTION_REJECTED` 를 더하고 `docs/errors.md` · react `validateFile` 코드 목록을 맞춘다.

## 6. 화면 (react `@skeleton/storage` 1.2.0)

- `storageEndpoints()` 에 `complete` · `guestComplete` · `images` 를 더하고 `createStorageApi` 가 `complete?(key)` · `images?(keys)` 를 낸다.
- `createUploader({ …, complete: true })` — **기본 `false`**(옛 서버와 짝일 때 그대로). 켜면 PUT 뒤 `complete` 를 부르고 `UploadResult` 에 `inspection`(`contentType` · `image`)을 싣는다. 거절은 새 `UploadRejectedError`(코드 목록 — `StorageValidationError` 와 같은 모양) — 객체는 서버가 이미 지웠다.
- `useFilePreview(file | null)` → 로컬 미리보기 주소(`createObjectURL`, 바뀌거나 사라지면 `revoke`). 이미지가 아니면 `null`.
- `<ImageUploadField uploader value onChange max accept labels? />` — `FilePicker` 위: 여러 장, 장마다 로컬 미리보기 · 진행률(`Progress`) · 거절 문구(코드 → `labels`) · 「처리 중」 표시 · 지우기(`Button`). 이미 붙은 키는 `api.images` 의 `thumb` 으로 그린다. 순서 바꾸기 · 자르기는 다음.
- `<StoredImage image alt sizes? />` — 서버 모양 `{ width, height, thumb?, display? }` 를 받아 `<img src={display ?? thumb} srcSet="thumb 400w, display 1600w" sizes width height loading="lazy" decoding="async">`. 한쪽만 있으면 그 하나. 폭 · 높이가 없으면 빼고 그린다.
- 스토리(`play`): `ImageUploadField`(빈 칸 · 올리는 중 · 처리 중 · 거절 · 다 됨 · 상한) · `StoredImage`(둘 다 · 하나만). 문구 ko/en `labels`, 눌리는 것은 눌리게, 날 요소 없음.

## 7. 견본 앱 조립

**kotlin `apps/sample`**: 의존 셋(`storage-image` · `-jdbc` · `-imageio`). yml(이 앱의 선택): `storage.image.enabled: true` · 변환본 크기는 모듈 기본 그대로(주석으로 명시) · 리뷰 사진 · 게시판 공개 영역은 조립만으로 따라온다. `apps/workbench` 는 의존 한 줄씩(꺼짐 — 공존 증명). `docker-compose.yml` 의 SeaweedFS 신원 설정은 그대로(변환본은 비공개 접두사).
**react `apps/sample`**: 업로더 `complete: true`(회원 · 비회원) · `PicturePicker` 를 `ImageUploadField` 로 · 게시판 사진을 `StoredImage`(`attachmentImages`)로 · 리뷰 사진 목록은 `?variant=thumb`, 크게 볼 때 `display`.
**배포**: 새 비밀 없음. `docs/deploy.md` 에 한 줄 — 「`storage-image-imageio` 는 JVM 이미지(alpine JRE)의 headless AWT 를 쓴다, 네이티브 빌드에서는 빼라」. 메모리: 변환 동시 1 · 부분 표본 추출로 한 장당 수십 MB 안(모듈 문서에 계산식).

## 8. 검증 (깨지면 사고 나는 것만)

**kotlin**
- 보안: `image/png` 로 선언한 HTML · SVG · PDF 가 완료에서 거절되고 객체가 지워진다 · 남의 키 · MAC 이 틀린 비회원 키에 완료를 부르면 404 이고 **읽기 · 지우기가 일어나지 않는다** · 픽셀 폭탄(작은 파일, 머리에 3만×3만)이 디코드 없이 거절된다(완료와 작업 둘 다) · 이미지 모듈을 켜면 공개 영역에 복사된 바이트에 EXIF · GPS 가 없다(GPS 가 든 시험 사진) · 리뷰 사진 302 가 남에게 원본 키를 서명하지 않는다(아직이면 404) · 접두사 겹침 기동 실패.
- 데이터 손실: 작업의 거절 · 실패가 원본을 지우지 않는다 · 완료 거절은 그 키 하나만 지운다 · 비회원 회수가 변환본을 같이 지우고 다른 키의 변환본은 안 건드린다 · board 전체 대조가 썸네일 공개 객체를 고아로 지우지 않는다.
- 동시성: 같은 키 완료 동시 둘 · 완료 + 지연 등록 → 줄 하나 · 작업 하나(줄 넣기 `insertIgnore`) · 같은 작업 두 번 → 결과 같음.
- 조립: 이미지 모듈 없이 board 공개 영역 · 리뷰 사진이 지금과 같다(기존 시험 그대로 통과) · `-jdbc` 없이 켜면 기동 실패 메시지 · 두 방언 `dbTest` · `MigrationLockTest` · `CapabilitiesCatalogTest` · `ModuleDocumentationTest`.
- 회귀 아님(동작 확인만, 시험 하나씩): 회전 태그 6 · 8 인 사진의 변환본 폭 · 높이가 뒤바뀐다 · 투명 PNG 는 PNG 로 · 움직이는 GIF 는 썸네일만.

**react**: 완료 호출 순서(PUT 뒤) · `complete: false` 면 안 부름 · 거절 코드 → `UploadRejectedError` · `useFilePreview` 가 `revoke` 한다 · `StoredImage` 의 `srcset` · `width` · `height` · 스토리 `play` · `capabilities` 검사.
**e2e 한 여정**(react sample + kotlin sample, Chromium): 회전 태그 · GPS 가 든 JPEG 를 댓글에 끌어다 놓기 → 로컬 미리보기 → 진행률 → 완료 → 글 공개(승인) → 다른 브라우저 문맥(비로그인)에서 썸네일이 `srcset` 으로 보이고, 받은 공개 객체 바이트에 GPS 가 없다 → `.png` 이름의 HTML 파일은 「쓸 수 없는 파일」 문구. qa 수동 한 번(캡처 2장까지) — 배포 이미지(alpine JRE)에서 변환이 도는지 포함.

## 9. 내가 정한 것 (관행대로 — 바꾸려면 말해 줘)

1. 새 모듈 다섯: 핵심(`storage-image`) · 저장(`-jdbc`) · 기본 엔진(`-imageio`) · 선택 엔진(`-imgproxy`) · PDF(`-pdfbox`). 검사 · 완료 호출은 이미지와 무관해 `storage` 에 둔다.
2. 완료 호출 + 지연 등록 둘 다 — 클라이언트가 빼먹어도 「남에게 원본 금지」가 지켜진다.
3. 변환은 정해진 이름 둘(`thumb` · `display`)을 작업 큐로 미리. 즉석 변환 끝점은 열지 않는다(남이 CPU 를 정하게 되므로).
4. 이미지 모듈을 켜면 남에게는 변환본만 — 공개 영역 · 리뷰 사진 · 게시판 응답. 원본은 올린 사람 · 관리자만. 변환 전에는 공개를 늦춘다(몇 초 — board 큐가 다시 한다).
5. 동기 거절(완료)만 원본을 지우고, 비동기 거절 · 실패는 원본을 남긴다.
6. 상한 모듈 기본: `max-pixels` 1억 · `max-source-bytes` 50MB · 동시 1 · `thumb` 400 · `display` 1600 · JPEG 82 — 안전 장치라 모듈 기본으로 두고 설정으로 덮는다. `enabled` 자체는 기본 꺼짐.
7. 변환본 키는 비밀 없는 sha256 경로 — 비공개 영역이라 추측 방지 비밀이 필요 없고, 운영자 비밀을 늘리지 않는다.
8. 출력은 JPEG/PNG(기본 엔진의 한계), WebP 는 선택 엔진 `storage-image-imgproxy` 로.
9. HEIC/AVIF 원본은 기본 엔진에서 `UNSUPPORTED`(imgproxy 엔진이면 읽는다) — 남에게 안 보이고 올린 사람에게 「이 형식은 미리보기를 만들 수 없어요」. 견본은 허용 형식에 넣지 않는다(지금도 없다).
10. react 업로더의 완료 호출은 기본 꺼짐(옛 서버와 짝일 때 깨지지 않게), 견본이 켠다.
11. 악성 검사는 자리(`UploadInspector`)만 — 어댑터는 다음.
12. PDF 1쪽 썸네일(`storage-image-pdfbox`)과 버려진 회원 업로드 정리는 「결정됨」에 따라 1차에 넣는다(둘 다 기본 꺼짐).

## 10. 작업 계획 (계획 `docs/superpowers/plans/2026-11-02-files.md`)

| 키 | 레포 | 조각 | 선행 | 크기 | 예상 |
|---|---|---|---|---|---|
| K1 | kotlin | `storage` · `storage-s3` · `platform`: `ObjectStorage.read`(+ S3 범위 GET · 장식 둘) · 매직 바이트 판정 표 · `UploadInspector` · `UploadCompletionListener` · 완료 경로 둘(비회원 한도) · 거절 코드 · `UploadImages` 포트 · `PublicUploads.publicKeysOf` · 공개 영역의 변환본 분기 · 비회원 회수가 변환본 폴더까지 · 모듈 문서 · 설정 문서 · `docs/errors.md` · 카탈로그(새 다섯 모듈 자리를 한 번에: `storage-image` · `-jdbc` · `-imageio` · `-imgproxy` · `-pdfbox`) | — | 대 | 5h |
| K2 | kotlin | `storage-image` · `-jdbc` · `-imageio`: 설정 · 상태 표(V 파일 두 방언) · 완료 리스너 · 지연 등록 · 작업 처리기(세마포어 · 재검사 · 판정) · `ImageRenderer` 자바 구현(부분 표본 · 회전 · sRGB · 메타데이터 제거 · 움직이는 이미지) · `UploadImages` 구현 · `/storage/images` · 시험(GPS 사진 · 폭탄 · 회전) · 모듈 문서 3 | K1 계약(포트 · 리스너 먼저 합의) | 대 | 6h |
| K4 | kotlin | `storage-image-imgproxy`: 선택 엔진(`ImageRenderer` 구현, 서명 · 원본 주소 SSRF 주의, HEIC 입력 · WebP 출력), Testcontainers 시험(끝나면 컨테이너를 내린다), `docs/deploy.md` 컨테이너 한 줄 · 메모리 안내 | K2 | 중 | 3h |
| K5 | kotlin | `storage-image-pdfbox`: PDF 1쪽 썸네일(기본 꺼짐), 페이지 수 · 크기 · 렌더 시간 상한, 작업 큐에서 렌더 | K2 | 중 | 2h |
| K6 | kotlin | 버려진 회원 업로드 정리(기본 꺼짐) + `review-jdbc` 의 `UploadedKeyUsage` 구현. 대답하지 않는 사용처가 하나라도 조립돼 있으면 정리를 켤 때 기동 거부(데이터 손실 방지 회귀 시험) | K1 · K2 | 중 | 2.5h |
| K3 | kotlin | 쓰는 곳 · 조립: board 전체 대조의 `publicKeysOf` · `attachmentImages` · review `?variant=` · 견본 · workbench · yml · `deploy.md` 한 줄 · CHANGELOG · 견본 통합 시험(완료 → 작업 → 공개 영역에 변환본). 먼저 board 맞추기 큐가 `publish` 예외로 다시 도는지 확인 | K1 · K2 · K4 · K5 · K6 | 중 | 3.5h |
| R1 | react | `@skeleton/storage` 1.2.0: 경로 · `complete` 옵션 · `UploadRejectedError` · `useFilePreview` · `ImageUploadField` · `StoredImage` · 스토리 · README · 카탈로그 · CHANGELOG | §5 표(가짜 API) | 중 | 3.5h |
| R2 | react | 견본 조립(업로더 · 게시판 · 리뷰) · e2e 한 여정 | R1 · K3 합친 뒤 | 중 | 2.5h |

- 순서: K1 → K2 → K4 → K5 → K6 → K3, R1 은 K1 과 나란히(§5 가 계약), R2 마지막. 묶음: ① K1 ∥ R1 → ② K2 → ③ K4 → K5 → K6 → K3 → R2 → 끝 검사(지휘: kotlin 바뀐 모듈만 한 번 · react 전체 + `e2e:sample` 한 번, 리뷰 kotlin 1 · react 1, qa 1 — 화면당 캡처 2장까지). 동시 최대 2.
- developer 8회 + 리뷰 2 + qa 1 = 에이전트 11. 벽시계 대략 25h(K1 5h → K2 6h → K4 3h → K5 2h → K6 2.5h → K3 3.5h → R2 2.5h 가 긴 줄 — R1 은 K1 에 겹친다).
- 같이 만지는 파일: `settings.gradle.kts` · `capabilities.json` · `docs/modules/README.md` 는 K1 이 새 다섯 모듈의 자리를 한 번에(K2 · K4 · K5 는 내용만), 견본 yml · `deploy.md` · CHANGELOG 는 K3 만. `StoragePublicUploads` 는 K1 만(K3 는 board 쪽만).
- 계약이 바뀌면 kotlin 조각이 보고에 적고 지휘가 react 조각에 넘긴다. 스펙과 계획이 어긋나면 스펙이 이긴다.

## 정해야 할 것 (세 건 모두 아래 「결정됨」으로 닫혔다 — 선택지 기록으로만 남긴다)

1. **변환 엔진(1차 구현 하나 — 자리는 어느 쪽이든 `ImageRenderer`)**
   - A. 앱 안 순수 자바(ImageIO + TwelveMonkeys) **(추천)** — 설치 0, 시험이 CI 어디서나 돌고 메모리 상한을 앱이 쥔다. 대신 출력은 JPEG/PNG, HEIC/AVIF 원본은 못 읽는다.
   - B. imgproxy 컨테이너(libvips) — WebP/AVIF 출력 · HEIC 입력 · 메모리 적음. 홈서버 compose 에 서비스 하나 더, 시험에 컨테이너가 필요하다.
   - C. Cloudflare 이미지 변환 — 즉석 변환 + CDN 캐시, 월 5,000 고유 변환 무료. 비공개 원본(서명 주소)과 맞추기 어렵고 벤더에 묶인다.
2. **PDF 1쪽 썸네일**
   - A. 다음으로 **(추천)** — 요청이 이미지 중심이고, PDF 파서(PDFBox)는 무겁고 공격면이 넓어 따로 다루는 게 낫다. 같은 `ImageRenderer` 자리에 나중에 끼운다.
   - B. 1차에 넣는다 — `storage-image-pdfbox` 하나 더(약 +2h, 에이전트 +1).
3. **회원 업로드 고아 정리**
   - A. 다음으로 **(추천)** — 켜려면 쓰는 곳 전부가 「사용 중」을 알려야 하는데 지금 `review-jdbc` 가 알리지 않아, 서두르면 리뷰 사진이 지워진다(데이터 손실). `review-jdbc` 의 `UploadedKeyUsage` + 일반 회수를 한 조각으로 다음에.
   - B. 1차에 넣는다(기본 꺼짐, `review-jdbc` 구현 포함 — 약 +2.5h, 에이전트 +1).

참고(관행 조사 출처): [Cloudflare Images 요금](https://developers.cloudflare.com/images/pricing) · [vips-ffm](https://github.com/lopcode/vips-ffm)(JDK 23+) · [TwelveMonkeys WebP 쓰기 없음 #659](https://github.com/haraldk/TwelveMonkeys/issues/659).

## 결정됨 (2026-11-02)

형과 정했다. 이 절이 본문보다 우선한다.

1. 변환 엔진 — 형: "자바 안 쓰는 케이스도 있지 않을까?", "이미지프록시 그거 하면 서버 하나 더 뜨는거 아냐? 클라우드 어케 됨?" → **기본은 A(앱 안 ImageIO + TwelveMonkeys, 추가 서버 0)**, **imgproxy 는 같은 `ImageRenderer` 자리에 끼우는 선택 모듈(`storage-image-imgproxy`)로 1차에 함께 만든다** — HEIC 입력 · WebP 출력이 필요한 앱만 켜고 컨테이너를 더한다(docs/deploy.md 에 컨테이너 한 줄 · 메모리 안내). Cloudflare 이미지는 이번에 하지 않는다.
2. PDF 1쪽 썸네일 — 형: "이번에 넣음" → **B**: `storage-image-pdfbox` 모듈을 1차에 넣는다(기본 꺼짐, 페이지 수 · 크기 · 렌더 시간 상한, 작업 큐에서).
3. 버려진 업로드 정리 — 지휘 세션 설명 뒤 **B**: 1차에 넣는다(기본 꺼짐). 정리 전에 모든 사용처에 「이 키를 쓰는가」를 묻는 계약이므로, **review-jdbc 의 `UploadedKeyUsage` 구현을 같은 조각에 포함**한다 — 대답하지 않는 사용처가 하나라도 조립돼 있으면 정리는 기동 시 거부(데이터 손실 방지 회귀 시험).
