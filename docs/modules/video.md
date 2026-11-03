# video

영상 올리기 — 브라우저가 파일을 객체 저장소(R2 등 S3 호환)로 **직접** 멀티파트로 올리고, 서버는 시작 · 조각 주소 · 조각 목록 · 마침(크기 확인) · 지우기만 한다. 새로 고친 뒤에도 같은 파일을 다시 고르면 빠진 조각만 이어 올린다.
올리기 뒤의 변환(원본 단 → 호환 단 → 사다리)은 **서버가 아니라 일꾼**(`apps/video-worker`)이 한다 — 서버는 일꾼을 부르지 않고, 어디서든 같은 이미지가 일꾼 토큰으로 서버 API 를 불러 일을 집어 간다. 이 모듈은 그 일꾼 API · 임대 · 결과 검증 · 후속(`VideoEvents`)을 맡는다. 재생 허가 · 삭제 · 운영자 삭제는 같은 묶음의 다음 조각이다. 저장은 포트(`VideoStore`)이고 구현은 `video-jdbc` 가 낸다.

**켜기**: 모듈 기본은 **꺼짐** — 앱 yml 이 `skeleton.video.enabled=true`. 켰는데 `VideoStore` · `VideoWorkerStore` · `VideoFollowUps` · `VideoTransactions`(`video-jdbc` + `job-queue-jdbc`) · `PresignedStorage`(`storage-s3`)가 없거나 영상 키 구역이 업로드 · 공개 구역과 겹치면 **기동 실패**다.

**경로**(서블릿 웹 앱 + Spring Security 가 있을 때, 로그인한 사람만 — 익명 401. 응답은 `{value: …}` 봉투):

| 메서드 · 경로 | 하는 일 |
|---|---|
| `POST /api/v1/videos {bytes, contentType(video/mp4·video/quicktime), purpose, visibility(PUBLIC·PRIVATE)}` | 정책 → 파일 상한(4GB) → 동시 수 → 용량 판정 뒤 멀티파트를 열고 줄을 `UPLOADING` 으로 → 201 `{id, uploadId, partSize, partCount}`. 413 `VIDEO.TOO_LARGE`(`data.maxBytes`) · 413 `VIDEO.QUOTA` · 415 `VIDEO.UNSUPPORTED` · 403 `VIDEO.FORBIDDEN` · 429 `VIDEO.TOO_MANY_UPLOADS` |
| `POST /api/v1/videos/{id}/parts {numbers}` | 번호마다 서명된 `PUT` 주소(한 번에 100개까지, 범위 밖 · 중복은 400 `VIDEO.PART_RANGE`). 브라우저가 저장소로 직접 올리고 응답의 `ETag` 를 모은다 |
| `GET /api/v1/videos/{id}/parts` | 저장소가 이미 가진 조각 `{parts: [{n, etag, bytes}]}` — 이어 올리기 |
| `POST /api/v1/videos/{id}/complete {parts: [{n, etag}]}` | 조각 목록이 1..n 을 정확히 덮는지 보고 멀티파트를 마친 뒤 **HEAD 로 크기 확인** → `QUEUED`. **멱등**(이미 넘어갔으면 저장소를 다시 부르지 않고 지금 모양을 준다). 크기가 처음 말한 값과 다르면 올린 파일을 지우고 `DELETED` + 400 `VIDEO.SIZE_MISMATCH` |
| `GET /api/v1/videos/{id}` · `GET /api/v1/videos?purpose=` | 내 영상 하나 · 목록(지운 것 제외, 새것부터 200개) |
| `DELETE /api/v1/videos/{id}` | 줄을 `DELETED` 로 먼저, 그다음 올리는 중이면 멀티파트 중단 + 올린 파일 삭제 → 204 |

응답 모양(화면 패키지 `@skeleton/video` 가 읽는다): `POST …/parts` → `{values: [{number, method, url, headers}]}` · `GET …/parts` → `{values: [{n, etag, bytes}]}`(이미 마쳤거나 닫힌 올리기는 404 — 화면이 처음부터 다시 올린다) · 목록 한 줄 `{id, purpose, visibility, status, bytes, compat, ladder, progress(0~100|null), failCode, width, height, durationMs, createdAt, updatedAt}` — `complete` 의 응답이 곧 이 한 줄이다. `failCode` 는 `UNSUPPORTED_FORMAT` · `CORRUPT` · `LIMITS_EXCEEDED` · `TOO_BIG_FOR_WORKER` · `PROCESSING_FAILED` · `LEASE_EXPIRED` · `UPLOAD_ABANDONED`.

**일꾼 API**(`/api/internal/video/...`, `Authorization: Bearer <일꾼 토큰>` — 사용자 로그인으로는 열리지 않고, 일꾼 토큰으로는 사용자 API 가 열리지 않는다. 오류 코드 접두사 `VIDEO_WORKER.`):

| 경로 | 하는 일 |
|---|---|
| `POST /claim {workerId, lanes?}` | 토큰의 차선 ∩ 요청 차선 안에서 다음 일 하나를 집는다(`FOR UPDATE SKIP LOCKED` 임대 10분). 없으면 204. `{value: {jobId, lane, attempt, leaseUntil, inputUrl, inputBytes, width, height, durationMs, codec, expectedOutputBytes, build, origFolder, verifyDecode}}` |
| `POST /jobs/{jobId}/heartbeat {workerId, progress?}` | 임대를 늘리고 진행률(0~100)을 적는다. 임대를 잃었으면 409 `LEASE_LOST` |
| `POST /jobs/{jobId}/upload-urls {workerId, names}` | 차선별 허용 이름만 서명한 `PUT` 주소 `{values: [{name, method, url, headers, expiresAt}]}`. 하나라도 허용 밖이면 400 `PATH_NOT_ALLOWED` 이고 **하나도 서명하지 않는다** |
| `POST /jobs/{jobId}/complete {workerId, orig? \| compat? \| ladderBuild?, …}` | 결과 폴더를 나열해 합계 크기 · 필수 파일 · 폴더 이름의 해시를 대조한 뒤 반영한다. 204. 같은 호출을 또 해도 결과가 같고, 임대를 잃은 일꾼의 늦은 결과는 409 로 무시된다 |
| `POST /jobs/{jobId}/fail {workerId, code, detail?, retryable?}` | 일꾼이 낸 실패. 204 |

허용 이름: REMUX = `o-<해시16>/{index.m3u8, init.mp4, s#####.m4s}` · `p-<해시16>.jpg` / COMPAT = `c-<해시16>/…` / LADDER = 서버가 정한 `h-<빌드32>/<이름>.{m3u8,m4s,mp4}` 한 겹. 한 덩어리 `*.mp4` 는 `init.mp4` 말고는 없다. 폴더는 영상마다(`<공개 영역>/v/<id>/` 또는 `<video.prefix>/p/<id>/`).
**운영자**(`/api/v1/videos/admin/workers`): `POST {name, lanes}` 로 토큰 발급(평문은 응답에 한 번) · `GET` 목록 · `DELETE /{id}` 폐기. `VideoOperatorPolicy` 빈이 허락해야 열린다(기본은 모두 거절 → 403).
**로컬**: `skeleton.video.worker-token-sha256`(평문 토큰의 SHA-256)을 두면 기동 때 그 일꾼을 세 차선으로 한 번 등록한다 — `skeleton.env=stage|prod` 에서는 기동 실패다.

**원 파일**: 올린 파일은 **원본 단을 검증(`complete`)한 뒤 한 시간 뒤에** `video.finalize` 가 지운다(그 전에는 지우지 않는다). 임대 만료는 시도 +1 로 다시 줄을 세우고 3회째에 `FAILED(LEASE_EXPIRED)`; 호환 · 사다리의 실패는 그 트랙만 `FAILED` 이고 영상은 `PLAYABLE`, 원본 단은 그대로다. 늦게 끝난 중복 결과 폴더는 트랙이 끝난 한 시간 뒤 쓸기가 지운다(이긴 쪽만 남긴다).
**후속**: 상태가 바뀌는 같은 트랜잭션에서 `video.finalize` 작업(`PLAYABLE` · `COMPAT_READY` · `LADDER_READY` · `FAILED` · `TRACK_FAILED` · `UPLOAD_CLEANUP` · `SWEEP`)을 큐에 넣고, 처리기가 앱의 `VideoEvents`(`onPlayable` · `onTrackReady` · `onFailed` · `onDeleted`)를 부른다(적어도 한 번 — 멱등하게 쓴다). 깨울 일이 생기면 `video.wake` — `VideoWaker`(기본 `NoopWaker`, 상주 일꾼은 스스로 집는다).
**경보**(`alert` 가 있을 때): `VIDEO_QUEUE_STALE`(대기가 있는데 remux 일꾼이 안 보인다) · `VIDEO_FAILED_SURGE` · `VIDEO_COMPAT_STALE`; `VIDEO_WAKE_FAILED` 는 깨우기 구현이 연속 실패를 셀 때 낸다.

남의 · 지운 · 없는 영상은 조각 목록 · 마침 · 상태 · 지우기 모두 같은 404 `VIDEO.NOT_FOUND` 다(`VideoAccessPolicy.canManage` 가 정한다).

**상태 기계**: `UPLOADING → UPLOADED → QUEUED → REMUXING → PLAYABLE` (실패는 `FAILED`, 지우면 `DELETED`). 전이는 순수 함수 `VideoStatus.next(event)` 의 표가 정하고, 저장소의 조건부 갱신(`where status = ?`)이 DB 에서 같은 표를 지킨다 — 동시에 온 두 호출 중 하나만 이긴다. 호환 · 사다리는 영상의 독립 트랙(`TrackStatus`)이라 실패해도 재생은 계속된다.
**키**: 올린 파일은 `<prefix>/u/<id>`(비공개, 서버가 정한다). `id` 는 무작위 base64url 22자라 대소문자를 구분한다. `prefix`(기본 `videos`)는 업로드(`skeleton.storage.web.key-prefix`) · 공개(`…public.prefix`) 구간과 겹치면 기동 실패다.
**청소**(5분마다, `scheduler` 가 있으면 `@SkeletonScheduled`, 없으면 요청 구동 모드의 `OnRequestTask`): 마지막 움직임이 `stale-upload-age`(1시간) 넘은 `UPLOADING` · `UPLOADED` 를 멀티파트 중단 + 올린 파일 삭제 + `FAILED(UPLOAD_ABANDONED)` 로 — 지우지 않고 실패로 두어 화면이 「다시 올려 주세요」를 보인다.
**아직 없는 것**: 재생 허가(공개 주소 · 비공개 서명 재생 목록) · 공개 영역 폴더 삭제 · 운영자 삭제 경로 — 다음 조각. 지금의 `DELETE` 는 올린 파일까지만 치운다. 비공개 영상의 호환 · 사다리 입력 주소(`VideoWorkerInputs`)도 재생 조각이 채울 때까지 없다.

**확장 지점**: `VideoAccessPolicy`(`canUpload` · `canPlay` · `canManage` — 기본은 올린 계정만, 공개 영상 재생은 누구나) · `VideoQuota.allow(caller, bytes, purpose)`(요금제 · 남은 용량 같은 앱 규칙, 기본은 늘 허용) · `VideoEvents`(머리 포인터 · 캐시 · 알림, 기본 아무것도 안 함) · `VideoWaker`(기본 `NoopWaker`) · `VideoWorkerInputs`(호환 · 사다리 입력 주소) · `VideoOperatorPolicy`(일꾼 토큰 발급, 기본 모두 거절). 앱이 빈을 두면 기본이 물러난다.
받아들이는 원칙: 올라온 영상은 그대로 받는다 — 거절은 실제로 디코드가 안 되는 파일(손상 · 조작)과 크기 · 길이 상한 초과뿐이다(디코드 검사는 일꾼 조각에서). 올리는 쪽 검사는 형식(mp4 · mov) · 4GB · 조각 수뿐이다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:video"))` |
| 함께 오는 모듈 | `platform`, `storage` |
| 컴파일 전용 | `scheduler`, `alert` |
| 설정 접두사 | `skeleton.video` — [docs/config/modules/video.yml](../config/modules/video.yml) |
| 기본 동작 | 꺼짐. 앱 yml 이 `skeleton.video.enabled=true` 로 켜면 올리기 서비스 · 일꾼 일 서비스 · 기본 정책 · 청소가 서고, 서블릿 앱(+ Spring Security)이면 `/api/v1/videos` · 일꾼 API · 운영자 일꾼 발급도 켜진다. |
| 부팅에 필요한 것 | 켰을 때 `TimeProvider`(platform), `PresignedStorage`(storage-s3), `VideoStore` · `VideoWorkerStore` · `VideoFollowUps` · `VideoTransactions`(video-jdbc, 작업 큐 `job-queue-jdbc` 필요) 빈. 없으면 기동 실패. |
| 교체 지점 | `VideoAccessPolicy`, `VideoQuota`, `VideoEvents`, `VideoWaker`, `VideoWorkerInputs`, `VideoOperatorPolicy`, `VideoStore`, `VideoWorkerStore`, `VideoController`, `VideoWorkerController`, `videoCleanupTask` |
| 마이그레이션 | 없음 (`video-jdbc` 가 가진다) |
| 프론트 짝 | 없음 (영상 화면 패키지는 따로 온다) |
| 테스트 | `modules/video/src/test`, `modules/video/src/noOptionalTest` |

[모듈 색인](README.md) · [video-jdbc](video-jdbc.md) · [storage](storage.md)
