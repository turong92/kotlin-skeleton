# video

영상 올리기 — 브라우저가 파일을 객체 저장소(R2 등 S3 호환)로 **직접** 멀티파트로 올리고, 서버는 시작 · 조각 주소 · 조각 목록 · 마침(크기 확인) · 지우기만 한다. 새로 고친 뒤에도 같은 파일을 다시 고르면 빠진 조각만 이어 올린다.
이 조각은 올리기까지다 — 변환(원본 단 · 호환 단 · 사다리) · 일꾼 API · 재생은 같은 묶음의 다음 조각이 이 위에 올라온다(상태 기계 · 표 · 확장 지점 자리는 이미 있다). 저장은 포트(`VideoStore`)이고 구현은 `video-jdbc` 가 낸다.

**켜기**: 모듈 기본은 **꺼짐** — 앱 yml 이 `skeleton.video.enabled=true`. 켰는데 `VideoStore` · `VideoWorkerStore`(`video-jdbc`) · `PresignedStorage`(`storage-s3`)가 없거나 영상 키 구역이 업로드 · 공개 구역과 겹치면 **기동 실패**다.

**경로**(서블릿 웹 앱 + Spring Security 가 있을 때, 로그인한 사람만 — 익명 401. 응답은 `{value: …}` 봉투):

| 메서드 · 경로 | 하는 일 |
|---|---|
| `POST /api/v1/videos {bytes, contentType(video/mp4·video/quicktime), purpose, visibility(PUBLIC·PRIVATE)}` | 정책 → 파일 상한(4GB) → 동시 수 → 용량 판정 뒤 멀티파트를 열고 줄을 `UPLOADING` 으로 → 201 `{id, uploadId, partSize, partCount}`. 413 `VIDEO.TOO_LARGE`(`data.maxBytes`) · 413 `VIDEO.QUOTA` · 415 `VIDEO.UNSUPPORTED` · 403 `VIDEO.FORBIDDEN` · 429 `VIDEO.TOO_MANY_UPLOADS` |
| `POST /api/v1/videos/{id}/parts {numbers}` | 번호마다 서명된 `PUT` 주소(한 번에 100개까지, 범위 밖 · 중복은 400 `VIDEO.PART_RANGE`). 브라우저가 저장소로 직접 올리고 응답의 `ETag` 를 모은다 |
| `GET /api/v1/videos/{id}/parts` | 저장소가 이미 가진 조각 `{parts: [{n, etag, bytes}]}` — 이어 올리기 |
| `POST /api/v1/videos/{id}/complete {parts: [{n, etag}]}` | 조각 목록이 1..n 을 정확히 덮는지 보고 멀티파트를 마친 뒤 **HEAD 로 크기 확인** → `QUEUED`. **멱등**(이미 넘어갔으면 저장소를 다시 부르지 않고 지금 모양을 준다). 크기가 처음 말한 값과 다르면 올린 파일을 지우고 `DELETED` + 400 `VIDEO.SIZE_MISMATCH` |
| `GET /api/v1/videos/{id}` · `GET /api/v1/videos?purpose=` | 내 영상 하나 · 목록(지운 것 제외, 새것부터 200개) |
| `DELETE /api/v1/videos/{id}` | 줄을 `DELETED` 로 먼저, 그다음 올리는 중이면 멀티파트 중단 + 올린 파일 삭제 → 204 |

남의 · 지운 · 없는 영상은 조각 목록 · 마침 · 상태 · 지우기 모두 같은 404 `VIDEO.NOT_FOUND` 다(`VideoAccessPolicy.canManage` 가 정한다).

**상태 기계**: `UPLOADING → UPLOADED → QUEUED → REMUXING → PLAYABLE` (실패는 `FAILED`, 지우면 `DELETED`). 전이는 순수 함수 `VideoStatus.next(event)` 의 표가 정하고, 저장소의 조건부 갱신(`where status = ?`)이 DB 에서 같은 표를 지킨다 — 동시에 온 두 호출 중 하나만 이긴다. 호환 · 사다리는 영상의 독립 트랙(`TrackStatus`)이라 실패해도 재생은 계속된다.
**키**: 올린 파일은 `<prefix>/u/<id>`(비공개, 서버가 정한다). `id` 는 무작위 base64url 22자라 대소문자를 구분한다. `prefix`(기본 `videos`)는 업로드(`skeleton.storage.web.key-prefix`) · 공개(`…public.prefix`) 구간과 겹치면 기동 실패다.
**청소**(5분마다, `scheduler` 가 있으면 `@SkeletonScheduled`, 없으면 요청 구동 모드의 `OnRequestTask`): 마지막 움직임이 `stale-upload-age`(1시간) 넘은 `UPLOADING` · `UPLOADED` 를 멀티파트 중단 + 올린 파일 삭제 + `FAILED(UPLOAD_ABANDONED)` 로 — 지우지 않고 실패로 두어 화면이 「다시 올려 주세요」를 보인다.
**아직 없는 것**: 일꾼 API · 변환 · 재생 허가 · 공개 영역 폴더 삭제 · 운영자 경로 · 이벤트(`VideoEvents`) — 다음 조각. 지금의 `DELETE` 는 올린 파일까지만 치운다.

**확장 지점**: `VideoAccessPolicy`(`canUpload` · `canPlay` · `canManage` — 기본은 올린 계정만, 공개 영상 재생은 누구나) · `VideoQuota.allow(caller, bytes, purpose)`(요금제 · 남은 용량 같은 앱 규칙, 기본은 늘 허용). 앱이 빈을 두면 기본이 물러난다.
받아들이는 원칙: 올라온 영상은 그대로 받는다 — 거절은 실제로 디코드가 안 되는 파일(손상 · 조작)과 크기 · 길이 상한 초과뿐이다(디코드 검사는 일꾼 조각에서). 올리는 쪽 검사는 형식(mp4 · mov) · 4GB · 조각 수뿐이다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:video"))` |
| 함께 오는 모듈 | `platform`, `storage` |
| 컴파일 전용 | `scheduler` |
| 설정 접두사 | `skeleton.video` — [docs/config/modules/video.yml](../config/modules/video.yml) |
| 기본 동작 | 꺼짐. 앱 yml 이 `skeleton.video.enabled=true` 로 켜면 올리기 서비스 · 기본 정책 · 청소가 서고, 서블릿 앱이면 `/api/v1/videos` 도 켜진다. |
| 부팅에 필요한 것 | 켰을 때 `TimeProvider`(platform), `PresignedStorage`(storage-s3), `VideoStore` · `VideoWorkerStore`(video-jdbc) 빈. 없으면 기동 실패. |
| 교체 지점 | `VideoAccessPolicy`, `VideoQuota`, `VideoStore`, `VideoWorkerStore`, `VideoController`, `videoCleanupTask` |
| 마이그레이션 | 없음 (`video-jdbc` 가 가진다) |
| 프론트 짝 | 없음 (영상 화면 패키지는 따로 온다) |
| 테스트 | `modules/video/src/test`, `modules/video/src/noOptionalTest` |

[모듈 색인](README.md) · [video-jdbc](video-jdbc.md) · [storage](storage.md)
