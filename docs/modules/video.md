# video

영상 올리기 · 재생 · 삭제 — 브라우저가 파일을 객체 저장소(R2 등 S3 호환)로 **직접** 멀티파트로 올리고, 서버는 시작 · 조각 주소 · 조각 목록 · 마침(크기 확인)만 한다. 새로 고친 뒤에도 같은 파일을 다시 고르면 빠진 조각만 이어 올린다.
올리기 뒤의 변환(원본 단 → 호환 단 → 사다리)은 **서버가 아니라 일꾼**(`apps/video-worker`)이 한다 — 서버는 일꾼을 부르지 않고, 어디서든 같은 이미지가 일꾼 토큰으로 서버 API 를 불러 일을 집어 간다. 이 모듈은 그 일꾼 API · 임대 · 결과 검증 · 후속(`VideoEvents`)을 맡는다. 재생 허가 · 삭제 · 운영자 삭제는 아래에 있다. 저장은 포트(`VideoStore`)이고 구현은 `video-jdbc` 가 낸다.

**켜기**: 모듈 기본은 **꺼짐** — 앱 yml 이 `skeleton.video.enabled=true`. 켰는데 `VideoStore` · `VideoWorkerStore` · `VideoFollowUps` · `VideoTransactions`(`video-jdbc` + `job-queue-jdbc`) · `PresignedStorage`(`storage-s3`)가 없거나 영상 키 구역이 업로드 · 공개 구역과 겹치거나 재생 비밀(`skeleton.video.playback.secret`, 환경변수 `<P>_VIDEO_PLAYBACK_SECRET` — 선언의 `secrets:`)이 비었거나 32자보다 짧으면 **기동 실패**다.

**경로**(서블릿 웹 앱 + Spring Security 가 있을 때, 로그인한 사람만 — 익명 401. 응답은 `{value: …}` 봉투):

| 메서드 · 경로 | 하는 일 |
|---|---|
| `POST /api/v1/videos {bytes, contentType(video/mp4·video/quicktime), purpose, visibility(PUBLIC·PRIVATE)}` | 정책 → 파일 상한(4GB) → 동시 수 → 용량 판정 뒤 멀티파트를 열고 줄을 `UPLOADING` 으로 → 201 `{id, uploadId, partSize, partCount}`. 413 `VIDEO.TOO_LARGE`(`data.maxBytes`) · 413 `VIDEO.QUOTA` · 415 `VIDEO.UNSUPPORTED` · 403 `VIDEO.FORBIDDEN` · 429 `VIDEO.TOO_MANY_UPLOADS` |
| `POST /api/v1/videos/{id}/parts {numbers}` | 번호마다 서명된 `PUT` 주소(한 번에 100개까지, 범위 밖 · 중복은 400 `VIDEO.PART_RANGE`). 브라우저가 저장소로 직접 올리고 응답의 `ETag` 를 모은다 |
| `GET /api/v1/videos/{id}/parts` | 저장소가 이미 가진 조각 `{parts: [{n, etag, bytes}]}` — 이어 올리기 |
| `POST /api/v1/videos/{id}/complete {parts: [{n, etag}]}` | 조각 목록이 1..n 을 정확히 덮는지 보고 멀티파트를 마친 뒤 **HEAD 로 크기 확인** → `QUEUED`. **멱등**(이미 넘어갔으면 저장소를 다시 부르지 않고 지금 모양을 준다). 크기가 처음 말한 값과 다르면 올린 파일을 지우고 `DELETED` + 400 `VIDEO.SIZE_MISMATCH` |
| `GET /api/v1/videos/{id}` · `GET /api/v1/videos?purpose=` | 내 영상 하나 · 목록(지운 것 제외, 새것부터 200개) |
| `POST /api/v1/videos/{id}/playback` | 재생 허가(`VideoAccessPolicy.canPlay`, 아직 `PLAYABLE` 이 아니면 409 `VIDEO.NOT_READY`) → `{value: {src, compat?, hls?, poster, codec, w, h, durationMs, big, expiresAt?}}` — 아래 「재생」 |
| `GET /api/v1/videos/play/{출입증}/{폴더}/{목록}` | 비공개 영상의 재생 목록 — **로그인 없이** 열린다(출입증이 경로에 있다). 위조 · 만료 · 남의 것은 모두 404 |
| `PUT /api/v1/videos/{id}/poster {key}` | 올린 사람이 **파일 묶음으로 올려 완료한 내 이미지**(`key`)를 포스터로 지정 → 204. 이미지 모듈(`storage-image`)이 있는 조립에서만 — 아래 「포스터」. 404 `VIDEO.NOT_FOUND`(남의 영상 · 남의 이미지) · 409 `VIDEO.POSTER_PENDING`(이미지 검사가 아직 — 잠시 뒤 다시) · 409 `VIDEO.NOT_READY` · 422 `VIDEO.POSTER_REJECTED`(검사가 거절한 이미지, 또는 이미지 모듈 없음) |
| `DELETE /api/v1/videos/{id}` | 올린 사람의 삭제 → 204. 되돌리기 없음 — 아래 「삭제」 |
| `DELETE /api/v1/videos/admin/{id}?reason=` | 운영자 삭제(`VideoOperatorPolicy`, 기본 모두 거절 → 403). `reason` 은 로그에 남는 짧은 식별자(기본 `OPERATOR`). 없는 영상 404 |

응답 모양(화면 패키지 `@skeleton/video` 가 읽는다): `POST …/parts` → `{values: [{number, method, url, headers}]}` · `GET …/parts` → `{values: [{n, etag, bytes}]}`(이미 마쳤거나 닫힌 올리기는 404 — 화면이 처음부터 다시 올린다) · 목록 한 줄 `{id, purpose, visibility, status, bytes, compat, ladder, progress(0~100|null), failCode, width, height, durationMs, createdAt, updatedAt}` — `complete` 의 응답이 곧 이 한 줄이다. `failCode` 는 `UNSUPPORTED_FORMAT` · `CORRUPT` · `LIMITS_EXCEEDED` · `TOO_BIG_FOR_WORKER` · `PROCESSING_FAILED` · `LEASE_EXPIRED` · `UPLOAD_ABANDONED`.

**일꾼 API**(`/api/internal/video/...`, `Authorization: Bearer <일꾼 토큰>` — 사용자 로그인으로는 열리지 않고, 일꾼 토큰으로는 사용자 API 가 열리지 않는다. 오류 코드 접두사 `VIDEO_WORKER.`):

| 경로 | 하는 일 |
|---|---|
| `POST /claim {workerId, lanes?}` | 토큰의 차선 ∩ 요청 차선 안에서 다음 일 하나를 집는다(`FOR UPDATE SKIP LOCKED` 임대 10분). 없으면 204. `{value: {jobId, lane, attempt, leaseUntil, inputUrl, inputBytes, width, height, durationMs, codec, expectedOutputBytes, build, origFolder, verifyDecode}}` |
| `POST /jobs/{jobId}/heartbeat {workerId, progress?}` | 임대를 늘리고 진행률(0~100)을 적는다. 임대를 잃었으면 409 `LEASE_LOST` |
| `POST /jobs/{jobId}/upload-urls {workerId, names}` | 차선별 허용 이름만 서명한 `PUT` 주소 `{values: [{name, method, url, headers, expiresAt}]}`. 하나라도 허용 밖이면 400 `PATH_NOT_ALLOWED` 이고 **하나도 서명하지 않는다** |
| `POST /jobs/{jobId}/complete {workerId, orig? \| compat? \| ladderBuild? \| display?, …}` | 결과 폴더를 나열해 합계 크기 · 필수 파일 · 폴더 이름의 해시를 대조한 뒤 반영한다. 204. 같은 호출을 또 해도 결과가 같고, 임대를 잃은 일꾼의 늦은 결과는 409 로 무시된다 |
| `POST /jobs/{jobId}/fail {workerId, code, detail?, retryable?}` | 일꾼이 낸 실패. 204 |

허용 이름: REMUX = `o-<해시16>/{index.m3u8, init.mp4, s#####.m4s}` · `p-<해시16>.jpg` / COMPAT = `c-<해시16>/…` / LADDER = 서버가 정한 `h-<빌드32>/<이름>.{m3u8,m4s,mp4}` 한 겹. 한 덩어리 `*.mp4` 는 `init.mp4` 말고는 없다. 폴더는 영상마다(`<공개 영역>/v/<id>/` 또는 `<video.prefix>/p/<id>/`). ANIMATED = `display.mp4` · `display.webp` 둘뿐이고 폴더는 이미지 모듈의 변환본 폴더다(아래).

**움직이는 이미지 표시본(ANIMATED 차선)**: `storage-image` 도 켜져 있으면(`VideoDisplayAutoConfiguration` 이 서고, 한쪽만 켜면 서로를 모른 채 지금처럼 동작한다) 움직이는 GIF · WebP 의 표시본 변환이 일꾼에게 간다. `storage-image` 는 `video` 를 모른다 — 그쪽이 낸 확장 지점 `AnimatedDisplayConverter`(기본 없음)를 이 모듈이 채워, 이미지가 사본 `display` 로 `READY` 가 된 **뒤에** 변환 일(`video_display_jobs`)을 한 줄 넣는다. 일꾼은 같은 API 로 이 일을 집는다: job id 는 영상 id 가 아니라 이미지 상태 줄의 열쇠(sha256 hex 64자), `inputUrl` 은 올린 원본 이미지의 서명 읽기 주소, 결과는 `display.mp4`(불투명 - 소리 없는 H.264 반복 영상, 크기 · 프레임 시간 그대로) 또는 `display.webp`(투명한 배경이 있을 때) 하나를 올리고 `complete {display: {name, bytes}}` — 서버는 올린 파일의 크기를 대조한 뒤에야 표시본을 바꾼다. 투명 판정은 파일의 투명 표시가 아니라 ffmpeg 가 합성한 실제 화면의 알파다(GIF 의 「앞 장면과 같은 부분」 최적화 투명에 속지 않는다) - 변환과 같은 디코드에서 본다. **변환이 늦거나 실패해도 사본 display 가 그대로 쓰인다**: 변환본이 원본보다 크거나 일꾼의 ffmpeg 가 못 읽는 형식(움직이는 WebP 는 ffmpeg 6.1 에 디코더가 없다)이면 일꾼은 올리지 않고 실패로 알리고, 일은 `FAILED` 로 닫히며 이미지는 그대로다. 변환본은 사본의 파일 이름(`display.<원본 형식>`)을 덮어쓰지 않는다. 응답에서는 `POST /api/v1/storage/images` 의 `variants.display.contentType` 이 `video/mp4` 이면 영상으로 틀어야 한다(`<video autoplay loop muted playsinline>`). Cloud Run 깨우기는 이 일을 깨우지 않는다 - 상주 일꾼이 집는다.
**운영자**(`/api/v1/videos/admin/workers`): `POST {name, lanes}` 로 토큰 발급(평문은 응답에 한 번) · `GET` 목록 · `DELETE /{id}` 폐기. `VideoOperatorPolicy` 빈이 허락해야 열린다(기본은 모두 거절 → 403).
**로컬**: `skeleton.video.worker-token-sha256`(평문 토큰의 SHA-256)을 두면 기동 때 그 일꾼을 모든 차선(ANIMATED 포함)으로 한 번 등록한다 — `skeleton.env=stage|prod` 에서는 기동 실패다.

**원 파일**: 올린 파일은 **원본 단을 검증(`complete`)한 뒤 한 시간 뒤에** `video.finalize` 가 지운다(그 전에는 지우지 않는다). 임대 만료는 시도 +1 로 다시 줄을 세우고 3회째에 `FAILED(LEASE_EXPIRED)`; 호환 · 사다리의 실패는 그 트랙만 `FAILED` 이고 영상은 `PLAYABLE`, 원본 단은 그대로다. 늦게 끝난 중복 결과 폴더는 트랙이 끝난 한 시간 뒤 쓸기가 지운다(이긴 쪽만 남긴다).
**후속**: 상태가 바뀌는 같은 트랜잭션에서 `video.finalize` 작업(`PLAYABLE` · `COMPAT_READY` · `LADDER_READY` · `FAILED` · `TRACK_FAILED` · `DELETED` · `UPLOAD_CLEANUP` · `SWEEP`)을 큐에 넣고, 처리기가 앱의 `VideoEvents`(`onPlayable` · `onTrackReady` · `onFailed` · `onDeleted`)를 부른다(적어도 한 번 — 멱등하게 쓴다). 깨울 일이 생기면 `video.wake` — `VideoWaker`(기본 `NoopWaker`, 상주 일꾼은 스스로 집는다).
**경보**(`alert` 가 있을 때): `VIDEO_QUEUE_STALE`(대기가 있는데 remux 일꾼이 안 보인다) · `VIDEO_FAILED_SURGE` · `VIDEO_COMPAT_STALE`; `VIDEO_WAKE_FAILED` 는 깨우기 구현이 연속 실패를 셀 때 낸다.

남의 · 지운 · 없는 영상은 조각 목록 · 마침 · 상태 · 재생 허가 · 지우기 모두 같은 404 `VIDEO.NOT_FOUND` 다(`VideoAccessPolicy.canManage` 가 정한다).

**상태 기계**: `UPLOADING → UPLOADED → QUEUED → REMUXING → PLAYABLE` (실패는 `FAILED`, 지우면 `DELETED`). 전이는 순수 함수 `VideoStatus.next(event)` 의 표가 정하고, 저장소의 조건부 갱신(`where status = ?`)이 DB 에서 같은 표를 지킨다 — 동시에 온 두 호출 중 하나만 이긴다. 호환 · 사다리는 영상의 독립 트랙(`TrackStatus`)이라 실패해도 재생은 계속된다.
**키**: 올린 파일은 `<prefix>/u/<id>`(비공개, 서버가 정한다). `id` 는 무작위 base64url 22자라 대소문자를 구분한다. `prefix`(기본 `videos`)는 업로드(`skeleton.storage.web.key-prefix`) · 공개(`…public.prefix`) 구간과 겹치면 기동 실패다.
**청소**(5분마다, `scheduler` 가 있으면 `@SkeletonScheduled`, 없으면 요청 구동 모드의 `OnRequestTask`): 마지막 움직임이 `stale-upload-age`(1시간) 넘은 `UPLOADING` · `UPLOADED` 를 멀티파트 중단 + 올린 파일 삭제 + `FAILED(UPLOAD_ABANDONED)` 로 — 지우지 않고 실패로 두어 화면이 「다시 올려 주세요」를 보인다.
**재생**: 영상마다 올릴 때 정한 `visibility` 가 길을 가른다(올린 뒤에는 바꾸지 않는다).
- `PUBLIC` — storage 공개 영역(`skeleton.storage.web.public.enabled=true`)의 주소 `<base-url>/<public.prefix>/v/<id>/…`. CDN 이 캐시하고(폴더 이름이 내용 해시라 `immutable`) 주소를 알면 누구나 볼 수 있다. 로그인 없이 보여 주는 앱은 `POST …/playback` 응답을 자기 공개 응답에 싣는다(그 호출 자체는 로그인이 필요하다).
- `PRIVATE` — 허가마다 출입증 `v1.<영상 id>.<만료>.<mac>`(HMAC-SHA256, 비밀 `playback.secret`, 수명 `playback.ttl` 기본 4시간)을 **경로**에 넣은 서버 주소 `/api/v1/videos/play/<출입증>/o-<해시>/index.m3u8`(사파리 기본 재생은 헤더를 못 붙인다). 이 GET 만 로그인 없이 열리고(`PublicEndpointContributor`), 저장된 목록의 `init` · 조각 줄을 **영상 폴더 안의 허용 이름일 때만**(`VideoResultVerifier`) 출입증 남은 수명의 서명 주소로 바꿔 쓴다. 마스터의 `v0.m3u8` · `../o-…/index.m3u8` 같은 목록 줄은 그대로라 같은 출입증 경로 아래로 풀리고, `..` · 절대 주소 · 다른 폴더 · 질의가 든 줄은 서명하지 않는다. 응답은 `private, no-store`, 조각은 CDN 을 거치지 않고 저장소에서 바로 받는다(R2 는 객체마다 서명이라 접두사 서명이 없다 — CDN 캐시가 필요하면 공개 영상으로 올리거나 `VideoPlayback` 을 바꿔 끼운다). 위조 · 만료 · 다른 영상 id · 그 영상에 없는 목록은 404 이고 그 전에는 서명도 저장소 읽기도 일어나지 않는다. 서버 주소는 루트 기준 경로이므로 화면과 API 가 같은 출처(리버스 프록시)여야 한다.
- 응답: `src` = 원본 단 목록, `compat` = 호환 단 목록(준비됐을 때만 — 원본이 이미 호환 조건이면 없다), `hls` = 사다리 마스터(준비됐을 때만), `poster`(비공개는 출입증 수명의 서명 주소, 포스터가 없으면 null), `codec` · `w` · `h`(회전 적용) · `durationMs`, `big` = 원본이 호환 조건 밖, `expiresAt`(비공개만 — 화면이 만료 전에 허가를 다시 받는다).
- 호환 · 사다리 일꾼이 읽는 입력 주소(`VideoWorkerInputs`, claim 응답의 `inputUrl`)도 같은 길이다: 공개는 공개 주소, 비공개는 `worker.url-ttl` 수명의 출입증 목록 주소를 **루트 기준 경로**로 준다 — 일꾼은 claim 을 부른 서버 주소를 기준으로 풀고, ffmpeg 가 그 목록 안의 서명 조각 주소를 따라간다.

**포스터**: 일꾼은 파일의 표지 그림(attached picture)이 있으면 그 한 장을, 없으면 영상 한 장면을 포스터(`p-<해시16>.jpg`)로 올린다(영상 조각에서는 표지 그림 트랙을 벗겨 낸다 — 재생에 쓰이지 않는다). `UploadImages`(`storage-image`)가 있으면 이 그림이 **파일 묶음의 이미지 길**(업로드 검사 · 메타데이터 제거 · 표시본)을 거친다: 영상이 재생 가능해지면 후속 `video.finalize POSTER` 가 그림을 검사에 부치고, 끝나면 표시본 사본(`poster-<해시16>.<jpg|png|webp>`)을 **영상 폴더로 복사**해 포스터로 바꾼다 — 그동안 재생 허가의 `poster` 는 null 이고, 검사가 거절한 그림은 포스터가 되지 않는다(포스터 없음). 바꿔 끼우기(`PUT …/poster`)도 같은 길이라 검사를 통과한(`READY`) 이미지의 표시본만 포스터가 된다(움직이는 이미지는 첫 장 썸네일). 옛 포스터는 쓸기(`SWEEP`)가 지우고, 공개 영상이면 지우기 전에 CDN 캐시를 비운다(`PublicUploadPurger`). 이미지 모듈이 없으면 아무것도 바뀌지 않는다 - 일꾼 포스터를 그대로 쓰고 바꿔 끼우기는 422.

**삭제**: `VideoLibrary.delete(id, reason)` — 줄을 먼저 `DELETED` 로 닫고(그 영상을 쥔 일꾼의 임대도 이때 끊겨 heartbeat · 늦은 결과가 거절된다) **같은 트랜잭션에서** 후속 `video.finalize DELETED` 를 큐에 넣는다. 후속이 올린 원 파일 · 열린 멀티파트 · **그 영상의 폴더만**(`…/<id>/` 접두사 — id 가 닮은 다른 영상은 건드리지 않는다)을 지우고 `VideoEvents.onDeleted` 를 부른다. 공개 영상은 폴더를 지우기 **전에** CDN 캐시를 비운다(주소가 immutable 이라 지운 뒤에는 목록이 비어 비울 주소를 잃는다) — 비우기가 실패하면 아무것도 지우지 않고 큐가 다시 돌린다(Cloudflare 설정은 `docs/modules/storage.md` 의 `public.purge.cloudflare`). 되돌리기는 없다. 소유 확인은 `VideoAccessPolicy.canManage`, 운영자 경로는 `VideoOperatorPolicy`. 변환 중에 지우면 일꾼이 이미 보낸 PUT 몇 개가 지운 뒤 폴더에 남을 수 있다 — 임대가 끊겨 새 `upload-urls` 는 받지 못한다.

**확장 지점**: `VideoAccessPolicy`(`canUpload` · `canPlay` · `canManage` — 기본은 올린 계정만, 공개 영상 재생은 누구나) · `VideoQuota.allow(caller, bytes, purpose)`(요금제 · 남은 용량 같은 앱 규칙, 기본은 늘 허용) · `VideoEvents`(머리 포인터 · 캐시 · 알림, 기본 아무것도 안 함) · `VideoWaker`(기본 `NoopWaker`) · `VideoWorkerInputs`(호환 · 사다리 입력 주소) · `VideoPlayback`(재생 허가를 만드는 자리 — 벤더 스트리밍은 이 빈과 `VideoEvents` 로 끼운다) · `VideoOperatorPolicy`(일꾼 토큰 발급 · 운영자 삭제, 기본 모두 거절). 앱이 빈을 두면 기본이 물러난다.
받아들이는 원칙: 올라온 영상은 그대로 받는다 — 거절은 실제로 디코드가 안 되는 파일(손상 · 조작)과 크기 · 길이 상한 초과뿐이다(디코드 검사는 일꾼 조각에서). 올리는 쪽 검사는 형식(mp4 · mov) · 4GB · 조각 수뿐이다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:video"))` |
| 함께 오는 모듈 | `platform`, `storage` |
| 컴파일 전용 | `scheduler`, `alert`, `storage-image` |
| 설정 접두사 | `skeleton.video` — [docs/config/modules/video.yml](../config/modules/video.yml) |
| 기본 동작 | 꺼짐. 앱 yml 이 `skeleton.video.enabled=true` 로 켜면 올리기 서비스 · 일꾼 일 서비스 · 기본 정책 · 청소가 서고, 서블릿 앱(+ Spring Security)이면 `/api/v1/videos` · 일꾼 API · 운영자 일꾼 발급도 켜진다. |
| 부팅에 필요한 것 | 켰을 때 `TimeProvider`(platform), `PresignedStorage`(storage-s3), 재생 비밀 `skeleton.video.playback.secret`(32자 이상), `VideoStore` · `VideoWorkerStore` · `VideoFollowUps` · `VideoTransactions`(video-jdbc, 작업 큐 `job-queue-jdbc` 필요) 빈. 없으면 기동 실패. |
| 교체 지점 | `VideoAccessPolicy`, `VideoQuota`, `VideoEvents`, `VideoWaker`, `VideoWorkerInputs`, `VideoPlayback`, `VideoOperatorPolicy`, `VideoStore`, `VideoWorkerStore`, `VideoController`, `VideoPlaylistController`, `VideoWorkerController`, `videoCleanupTask` |
| 마이그레이션 | 없음 (`video-jdbc` 가 가진다) |
| 프론트 짝 | `@skeleton/video` |
| 테스트 | `modules/video/src/test`, `modules/video/src/noOptionalTest` |

[모듈 색인](README.md) · [video-jdbc](video-jdbc.md) · [storage](storage.md)
