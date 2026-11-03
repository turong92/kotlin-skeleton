# 영상 — 올리기 → 원본 단 → 호환 단 → HLS 사다리 · 공개/비공개 재생 — 설계 (추출 · 일반화)

> 2026-11-02. 요청(형): 「ffmpeg 작업 + R2」 — 올린 영상을 ffmpeg 로 HLS(적응형) + 포스터로 바꿔 S3 호환 저장소(주 사용처 Cloudflare R2)에 두고 hls.js(사파리는 기본 재생)로 튼다. 변환 위치는 확장 지점(기본 홈서버, 클라우드 예시 Google Cloud Run Jobs). 벤더 스트리밍은 이번에 안 하되 같은 자리에 끼울 수 있게.
> **새로 설계하지 않는다.** 형의 비공개 앱에서 이미 만들어 실측 · 운영 문서까지 갖춘 영상 파이프라인(서버 영상 모듈 + Kotlin/ffmpeg 일꾼 앱)을 스켈레톤의 범용 모듈로 **추출 · 일반화**한다(모노레포 → 스켈레톤 반영 원칙). 실측값 · 결정은 그대로 가져오고, 그 앱 고유 개념(화면 단위 · 용도 슬롯 · 용량 요금 · 신고 · 운영 VM)은 일반 개념으로 바꾼다. 출처 경로는 지휘 세션이 안다 — 이 레포에는 그 앱의 이름 · 도메인 · 경로를 남기지 않는다.
> 형 원칙: 공개 스켈레톤 · 캡슐화된 조립식 모듈 · 모듈 기본값 중립(꺼짐) · 업계 관행 · 홈서버 자원 아낌 · 회귀 시험은 돈 · 보안 · 동시성 · 데이터 손실만 · 개인 도메인 · 이메일 흔적 없음(`example.com`).
> 대상: kotlin-skeleton(`storage` · `storage-s3` 에 `ListParts` 하나, 새 `video` · `video-jdbc` · `video-cloudrun`, 새 앱 `apps/video-worker`, `apps/sample` · `apps/workbench`) + react-skeleton(새 `@skeleton/video`, `apps/sample`). 기준: kotlin main `a9342ec0`, react main `c552ab3`.
> 표시: **[측정]** 원 구현이 직접 잰 값(합성 영상 · 1 CPU 컨테이너 · 개발 기기 arm64) · **[어림]** 계산 · 가정 · **[확인 필요]** 확인 못 함.

## 지금 있는 것 · 빈 것

| 있는 것 | 빈 것 |
|---|---|
| `storage`: 멀티파트 presign(시작 · 조각 · 완료 · 취소) · 공개 영역(`public.prefix` + `base-url` + Cloudflare purge) · 키 소유 | 영상 전부. 올린 조각 목록(`ListParts`) — 새로 고침 뒤 이어 올리기 |
| `job-queue-jdbc`(임대 10분 · 시작 전에만 갱신) · `alert` · `scheduler` | 한 시간짜리 변환을 큐 처리기로 돌리면 임대가 끊긴다 — **원 구현은 변환을 큐 밖으로 뺐다**(아래 §2: 영상 줄 자체의 임대 + 별도 일꾼 프로세스). 그래서 job-queue 는 고치지 않는다 |

## 0. 가져오는 결정 (원 구현 그대로)

1. **올리면 원본 단으로 바로 재생.** 원본 단 = 재인코딩 없는 **fMP4 HLS 조각**(`-c copy`, 메타데이터 · 위치 제거, `hev1`→`hvc1`, AAC 아닌 오디오만 AAC 192k). 최상 화질이자 보관본(4K 그대로). remux 는 4K 3분에 CPU 1초 · 프로세스 17MB **[측정]**.
2. **저장소에 영상 한 덩어리(`*.mp4`)가 없다** — 원본 · 호환 · 사다리 전부 조각 폴더(`init.mp4` 는 수 KB 조각 머리라 예외). 내려받기 단추 없음. 서버가 일꾼이 올릴 이름을 모양으로 막는다(`VideoResultVerifier`).
3. **호환 단은 반드시.** 원본이 호환 조건(H.264 · AAC · 짧은 변 ≤1080 · 8bit · ≤20Mbps)이면 생략(원본 단이 호환 단), 아니면 H.264 High 720p(짧은 변) 2.8Mbps · AAC 128k 한 판 HLS.
4. **사다리(아랫단)** = 원본 짧은 변보다 작은 판만: 1080(5,000k) · 720(2,800k) · 480(1,200k), 6초 fMP4 · `force_key_frames` 6초 · `sc_threshold 0` · HDR 은 `zscale`+`tonemap=hable` 로 SDR · 세로는 회전을 구워 짧은 변 기준. 짧은 변 ≤480 이면 사다리 없음.
5. **마스터 목록(자동)에는 짧은 변 1080 이하 원본 단만** 일꾼이 한 줄 덧붙인다(`BANDWIDTH` = 폴더 바이트÷길이×1.2 · `RESOLUTION` 회전 적용 · `CODECS` 직접 씀) — 4K 원본은 화질 칩 「원본」으로 직접 고를 때만(빠른 망 ABR 이 늘 4K 를 골라 전송 · 디코드가 폭주하지 않게).
6. **원본 단 조각 길이 = 원본 GOP**(상한 없음, 30초 넘는 조각은 로그만). 회전 표지는 원본 단에 그대로(재인코딩 안 함) — Chromium + hls.js 단독 · 단 전환 모두 세로 유지 **[측정]**, Safari · iOS · Firefox 실기기 **[확인 필요]**.
7. **포스터** 1초 지점(1.5초 이하면 0초) 한 장, 긴 변 ≤1280 JPEG, 회전 적용.
8. **차선 셋 `REMUX` · `COMPAT` · `LADDER`**, 호환 · 사다리는 영상의 **독립 트랙** — 주 상태 `PLAYABLE` 은 원본 단이 올라간 순간이고, 트랙이 실패해도 재생은 계속된다.
9. **일꾼은 별도 프로세스**(작은 Kotlin + ffmpeg 이미지, JVM `-Xmx96m`) · **저장소 키가 없다**(서버가 서명한 GET · PUT 만) · 서버 API 로 **집어 간다**(`claim`, 영상 줄의 `FOR UPDATE SKIP LOCKED` 임대 10분 + heartbeat, 시도 3) · 실행 모양 `poll`(상주) / `once`(204 까지 집고 끝 — Cloud Run). 늦은 `complete` 는 조건부 갱신으로 무시(멱등).
10. **Cloud Run 은 서버가 깨운다**(Admin API `jobs/<작업>:run`, 덮어쓰기 없음 — 실행이 `once` 로 집어 간다). 동시 실행 상한 · 최소 간격 · 연속 실패 경보. 깨우기 실패 때 **자동으로 다른 곳으로 넘기지 않는다**(일은 큐에 남고 경보).
11. **ffmpeg 격리**: 올린 파일은 디스크로 내려받아 `-protocol_whitelist file,pipe` · `-format_whitelist mov,mp4,m4a,3gp,3g2,mj2` · `-f mov` 강제 · `-codec_whitelist`(영상 h264 · hevc / 소리 aac · mp3 · alac · ac3 · eac3 · opus · pcm_* / 표지 mjpeg · png) — ffmpeg 6.1.1 의 CVE-2025-59734 · CVE-2024-32228 완화. 만든 조각을 다시 읽을 때는 `-f hls` · `h264,hevc,aac` 만. 비루트 · 읽기 전용 루트 · `--cap-drop ALL` · pids · 메모리 · 임시 폴더 · 벽시계(ffmpeg 180분 · 일 하나 240분) 한도. 목록 밖이면 `UNSUPPORTED_FORMAT`(재시도 없음).
12. **결과 검사**: 일꾼이 올리기 전에 ffprobe 로 길이(원본 ±2% · 최소 0.5초) · 트랙 수 · 코덱 · 크기 · 조각 수(길이÷6 ±1) · 끝 표시를 확인, 서버는 `complete` 때 폴더를 나열해 합계 크기와 폴더 이름의 해시(폴더 내용 SHA-256 앞 16자)를 대조한다. 폴더 이름이 내용 해시라 전부 `immutable`.
13. **올린 원 파일은 원본 단 검증 1시간 뒤 지운다**(원본 단이 보관본 — 위치 정보가 어디에도 안 남는다). 끝나지 않은 올리기는 마지막 움직임 1시간 뒤 멀티파트 중단 + 삭제 + `FAILED(UPLOAD_ABANDONED)`.
14. 상한: 파일 4GB(조각 16MB × 256) · 기술 상한 긴 변 8192 · 면적×fps ≤ 7680×4320×30 · 길이 ≤ 6시간(길이 한도는 따로 없다). 일꾼 URL 수명 2시간.
15. 재생: 네이티브 HLS(`canPlayType`)면 `<video src>`, 아니면 MSE 가 있을 때만 **hls.js light(1.7.3 고정, Apache-2.0)를 누를 때 지연 로드**(gzip 약 119KB **[측정]**), 둘 다 없으면 「이 브라우저에서는 재생할 수 없어요」. 순서: 마스터 → (마스터 없고 기기가 원본 코덱을 틀면) 원본 단 → 호환 단 → 포스터 + 「준비 중」. 화질 칩 「자동 / 720p / 원본」(둘 이상일 때만, 바꿔도 시각을 잇는다).

## 1. 실측 (원 구현, 그대로 옮긴다)

| 일 | 입력 | 벽시계 | 프로세스 메모리 | 비고 |
|---|---|---|---|---|
| remux | 1080p H.264 3분 387MB / 4K HEVC 3분 799MB | 1.7초 / 2.5초 | 16 / 17MB | 병목은 디스크(입력+출력, 4K 3분 ≈ 1.6GB) · 저장소 왕복 |
| 호환 단(veryfast) | 1080p 3분 / 4K HEVC 30초 | 122초 / 36.5초(1 CPU) | 189 / 238MB | 반 코어는 정확히 2배 · superfast 94초 · 109MB |
| 사다리 720+480 | 1080p 3분 | 158초 | 178MB | 분당 32MB |
| 사다리 1080+720+480 | 4K HEVC 30초 | 64.5초 | 470MB(1GB 컨테이너) | 4K 위단까지 재인코딩은 1GB 에서 OOM — **4K 사다리는 컨테이너 2~3GB** |
| 사다리 전부 | 4K HEVC 3분, M3 Pro 전 스레드 | 56초 | RSS 1.76GB | 홈서버급 기계 |
| HDR 톤매핑 | HLG 10bit 30초 | 29.3초 | 261MB | +7% |
| 검증 디코드(`-f null`) | 1080p 3분 / 4K 3분 | 52초 / 150초 | 56 / 169MB | 1 CPU |
| HLS 입력 범위 읽기 | moov 끝 387MB | 122초 | 211MB | 3회 요청 · 파일 1.003배만 읽음 |

분당 저장량 **[측정]**: 1080p H.264 17Mbps → 원본 129 + 사다리 32 = 161MB/분 · 1080p HEVC → 76.6 + 호환 21 + 32 = 129.6 · 4K HEVC → 266.4 + 21 + 62.8 = 350.2. Cloud Run(원 구현이 받은 공식 표 2026-10-05: vCPU-초 $0.000018 · GiB-초 $0.000002, 작업 무료 몫 월 240,000 vCPU-초 · 450,000 GiB-초 — 지역 단가 **[확인 필요]**): 호환 1080p 3분 ≈ $0.0024 · 4K 3분 사다리 ≈ $0.0085 **[어림]**. 측정 ffmpeg 9.0, 운영 이미지 6.1.1(가장자리 사례 시험은 6.1.1 에서 다시 통과).

## 2. 무엇을 일반화하나

| 원 구현 | 스켈레톤 |
|---|---|
| 화면 단위(소유 단위) 아래 키 · 용도 슬롯 | `owner_account_id`(올린 계정) + `purpose`(앱이 정하는 짧은 이름, 예 `note`). 앱은 영상 id 를 자기 표에 둔다 |
| 공개 버킷 고정 재생 | **영상마다 `visibility`**: `PUBLIC`(원 구현 그대로 — storage 공개 영역 `<public.prefix>/v/<id>/` + CDN immutable + purge) · `PRIVATE`(새 — §5 서명 재생 목록). 올릴 때 정하고 앱 정책이 허락한다 — 「정해야 할 것 1」 |
| 용량 게이지 · 유료만 | 확장 지점 `VideoQuota.allow(caller, bytes, purpose)`(기본 = 허용). 원 구현 규칙은 앱이 구현 |
| 신고 → 운영자 내리기 | `VideoLibrary.delete(id, reason)` + 운영자 경로(정책 `VideoOperatorPolicy`, 기본 거부) — 폴더 삭제 전 purge 큐(공개일 때), 되돌리기 없음 |
| 머리 포인터 갱신 · 알림 메일 | `VideoEvents`(`onPlayable` · `onTrackReady` · `onFailed` · `onDeleted`) — 상태가 바뀌는 트랜잭션에서 job-queue 후속(`video.finalize`)이 부른다. 앱이 알림 · 캐시를 붙인다 |
| 운영 VM · 집 PC | 「일꾼 위치」 하나: 홈서버 컨테이너(상주) · Cloud Run(깨움) · 아무 기계(같은 이미지 + 토큰) — 「정해야 할 것 2」 |
| 일꾼 토큰 표 + 운영자 발급 API | 그대로(`video_workers` — SHA-256 만, 차선 · 폐기 · 마지막 본 시각). 로컬 전용 `worker-token-sha256` 은 `skeleton.env=stage|prod` 면 `DeployGuard` 가 기동 거부 |
| 주인 알림 넷 | `alert` 가 있으면 `VIDEO_QUEUE_STALE` · `VIDEO_WAKE_FAILED` · `VIDEO_FAILED_SURGE` · `VIDEO_COMPAT_STALE`(`AlertKind` 은 열린 인터페이스) |
| (없음) | **더하는 것 둘**: ① 새로 고침 뒤 이어 올리기(`ListParts`) ② 일꾼 heartbeat 에 진행률(ffmpeg `out_time`÷길이) — 화면의 「처리 중 n%」 |

## 3. 모듈 경계 · 확장 지점

```
storage (+ PresignedStorage.listParts, storage-s3 · 장식 둘)
   ▲
video ◀─api── video-jdbc ──impl──▶ job-queue-jdbc(후속: finalize · wake · 청소), persistence-jdbc
   │  올리기 API · 일꾼 API(claim · heartbeat · upload-urls · complete · fail) · VideoResultVerifier · 청소 · 재생(공개 주소 / 서명 재생 목록)
   └─api── video-cloudrun (VideoWaker 구현 — 서비스 계정 키로 JWT 서명, 구글 라이브러리 없음)
apps/video-worker ── 서버 API 만 안다(계약 = 일꾼 API 한 장). Plans · Probe · Runner · Transfer · ResultCheck, JRE + ffmpeg 6.1.1 고정 이미지
```

| 확장 지점 | 자리 | 고른 이유 |
|---|---|---|
| **일꾼 API**(HTTP 계약) | `video` | 형 요청 「변환 실행 위치는 확장 지점」 — 원 구현의 답: 서버는 일꾼을 부르지 않고, 어디서든 같은 이미지가 토큰으로 집어 간다. 실행 위치를 바꾸는 데 코드가 필요 없다 |
| `VideoWaker`(`enabled` · `wake()`) | `video`(기본 `NoopWaker`), 구현 `video-cloudrun` | 0 으로 줄어드는 실행 환경만 깨움이 필요하다. 다른 클라우드(AWS Batch 등)도 이 하나 |
| `VideoPlayback`(`grant(video, viewer)` → `{src, compat?, hls?, poster, codec, w, h, durationMs, big, expiresAt?}`) | `video`, 구현 공개 주소 · 서명 재생 목록 | 공개/비공개 · Worker 토큰 · 벤더 스트리밍(Stream 은 올리기 · 변환 · 재생을 다 대신하므로 `VideoPlayback` + 일꾼 없이 `VideoEvents` 로 끼운다 — 다음)이 같은 자리 |
| `VideoAccessPolicy`(`canUpload` · `canPlay` · `canManage`) | `video`, 기본 = 올린 계정만(공개 영상 재생은 누구나) | 누가 볼지는 도메인이 정한다 |
| `VideoQuota` · `VideoEvents` · `VideoOperatorPolicy` | `video` | 위 표 |
| `VideoStore` · `VideoWorkerStore` | `video`, 구현 `-jdbc` | 레포 관례. `video.enabled` 인데 빈이 없으면 기동 실패 |

## 4. 데이터 (`video-jdbc`, 두 방언 짝 V 파일 · `migrations.lock`, 시각 UTC)

`videos`(원 구현 영상 자산 표의 일반화): `id char(22)`(무작위 base64url, 주소용) · `owner_account_id` · `purpose varchar(32)` · `visibility` · `status`(`UPLOADING` → `UPLOADED` → `QUEUED` → `REMUXING` → `PLAYABLE` \| `FAILED` \| `DELETED`) · `upload_key` · `upload_id` · `upload_bytes` · `last_upload_activity_at` · `src_*`(컨테이너 · 코덱 · 코덱 문자열 · 폭 · 높이 · 회전 · 길이 ms · fps · 비트레이트 · 소리 코덱) · `orig_key` · `orig_bytes` · `orig_sha256` · `compat_status` · `compat_key` · `compat_bytes` · `ladder_status` · `ladder_build` · `ladder_bytes` · `ladder_rungs`(json) — 트랙 상태 `NONE`/`SKIPPED`/`QUEUED`/`RUNNING`/`READY`/`FAILED` · `poster_key` · `poster_w` · `poster_h` · `progress smallint null` · `attempts` · `lease_owner` · `lease_until` · `lane` · `fail_code` · `fail_detail` · `created_at` · `updated_at`. 색인: `(status, lease_until)` · `(compat_status)` · `(ladder_status)` · `(owner_account_id, purpose)`.
`video_workers`: `id` · `name` · `token_sha256` unique · `lanes` · `revoked_at` · `last_seen_at` · `created_at`. `video_wake_runs`: 깨운 시각 · 실행 이름 · 결과(동시 실행 상한 계산).

키(`<video.prefix>` 기본 `videos`, 업로드 `key-prefix` · 공개 `prefix` 와 겹치면 기동 실패):
- 올린 파일 `<video.prefix>/u/<id>` (비공개, 서버가 정함)
- 산출물 `<root>/<id>/o-<해시16>/{index.m3u8, init.mp4, s#####.m4s}` · `p-<해시16>.jpg` · `c-<해시16>/…` · `h-<빌드32>/{master.m3u8, v<n>.m3u8, init_<n>.mp4, v<n>_###.m4s}` — `<root>` = `PUBLIC` 이면 `<public.prefix>/v`, `PRIVATE` 이면 `<video.prefix>/p`. `Cache-Control: public|private, max-age=31536000, immutable`, `Content-Type` 은 이름에서.

## 5. 흐름

**올리기**(브라우저 → 저장소 직접): `POST /api/v1/videos {bytes, contentType(mp4·mov), purpose, visibility}` → 정책 · 쿼터 · 4GB → 줄 `UPLOADING` + 멀티파트 시작 → `{id, uploadId, partSize}` · `POST …/{id}/parts {numbers:[…]}` → 조각 presign · **`GET …/{id}/parts`(새) → 올라간 조각 `[{n, etag, bytes}]`**(이어 올리기) · `POST …/{id}/complete {parts}` → 멀티파트 완료 · HEAD 크기 확인(멱등) → `QUEUED` · `DELETE …/{id}`.
**일꾼**(`/api/internal/video/*`, `Bearer <일꾼 토큰>`): `claim {workerId, lanes}` → 204 또는 `{jobId, lane, attempt, inputUrl, inputBytes, src 메타, expectedOutputBytes, build?, origFolder?, leaseUntil}` · `heartbeat {progress?}` · `upload-urls {names}`(차선별 허용 이름만 presign) · `complete`(폴더 나열 · 합계 · 해시 · 조건부 갱신) · `fail {code, retryable}`. 원 구현의 차선별 순서(내려받기 → probe → 조각 내기 → 검사 → 해시 → 포스터 → 올리기, 목록은 맨 끝) 그대로.
- 호환 · 사다리의 입력은 **원본 단 목록 주소**: `PUBLIC` 이면 공개 주소(원 구현), `PRIVATE` 이면 서버가 만든 짧은 수명 서명 재생 목록 주소(아래와 같은 길) — ffmpeg `-f hls` 가 그 안의 presign 조각 주소를 따라간다(프로토콜 목록 `file,http,https,tcp,tls,crypto`, 원 구현의 「만든 조각 읽기」 줄).
- `PLAYABLE` 이 되는 트랜잭션에서 트랙을 정한다(호환 조건이면 `compat SKIPPED`, 짧은 변 ≤480 이면 `ladder SKIPPED`, 사다리를 만들 일꾼이 없게 설정됐으면 `NONE`) + 후속 `video.finalize`(→ `VideoEvents`) · 깨울 일이 생기면 `video.wake`.
**청소**(`scheduler`, 5분): 임대 만료 → 시도 +1 다시 줄(3회 넘으면 `FAILED`, 트랙은 트랙만) · 버려진 올리기 1시간 · 올린 원 파일 원본 단 검증 1시간 뒤 삭제 · 늦게 끝난 중복 결과 폴더 쓸기 · 알림 문턱 · 사다리 설정이 생기면 `NONE` → `QUEUED`.
**재생** `POST /api/v1/videos/{id}/playback`(`canPlay`, `PLAYABLE` 아니면 409 `VIDEO.NOT_READY`) → `VideoPlayback.grant`:
- `PUBLIC`: 공개 주소들(원 구현의 포인터 모양). 로그인 없이 쓰는 앱은 이 응답을 자기 공개 응답에 싣는다.
- `PRIVATE`: 토큰이 **경로**에 든 주소 `/api/v1/videos/play/<토큰>/o-<해시>/index.m3u8` 등(사파리 기본 재생은 헤더를 못 붙인다 — Mux · Stream 과 같은 주소 토큰). 토큰 `v1.<id>.<만료>.<mac>`(HMAC-SHA256, 비밀 `playback.secret` env `<P>_VIDEO_PLAYBACK_SECRET` — 무작위로 충분, 수명 `playback.ttl` 4시간). `GET /api/v1/videos/play/{token}/{폴더}/{목록}.m3u8` 만 로그인 없이(`PublicEndpointContributor`) 열고, 저장된 목록의 `init`/조각 줄을 **`VideoResultVerifier` 의 폴더 안 이름일 때만** 토큰 남은 수명의 presign 으로 바꿔 쓴다(마스터의 `../o-…` 상대 줄은 그대로 — 토큰 경로 아래로 풀린다). 응답 `private, no-store`. 조각은 저장소에서 바로(R2 송신 무료 — CDN 을 안 거친다). R2 의 presign 은 객체마다이고 접두사 서명 · 서명 쿠키가 없어 이 방식이 S3 호환 어디서나 되는 관행이다; CDN 캐시가 필요하면 Cloudflare Worker 토큰 검사(다음)나 WAF HMAC 규칙(Pro 이상 유료)을 같은 자리에 끼운다.

## 6. 화면 (react 새 `@skeleton/video` 0.1.0)

`createVideoApi(client)`(시작 · 조각 · 조각 목록 · 완료 · 삭제 · 목록 · 재생) · `uploadVideo(file, {onProgress, signal, resume})`(원 구현의 올리기 로직: 형식 짐작 mp4/mov, 조각 동시 올리기 · 실패한 조각만 다시; **`resume: true` 면 `{id, 파일 지문}` 을 `localStorage` 에 두고 같은 파일을 다시 고르면 `GET parts` 로 빠진 조각만**) · `useVideos(purpose)`(처리 중이면 3초 폴링) · `<VideoUploadField>`(올리는 중 % → 준비 중 n% → 재생 가능 → 「다른 기기용 판 준비 중」 → 끝 / 실패 사유 코드 → 문구 / 버려진 올리기 안내 / 지우기) · `<VideoPlayer grant>`(§0-15 순서 · 화질 칩 · hls.js 지연 로드 · 비공개 토큰 만료면 재생 허가를 다시 받아 같은 시각에서 · `controlsList="nodownload"`). hls.js 는 이 패키지의 의존이라 영상을 안 쓰는 앱은 받지 않는다. 스토리(`play`)는 상태별 칸 · 플레이어 분기, 문구 ko/en `labels`, 눌리는 것은 눌리게.

## 7. 조립 · 배포

**kotlin `apps/sample`**: `video` · `-jdbc` 의존, 노트에 영상 하나(`PRIVATE` — 노트는 주인만 본다). yml `video.enabled: true` · 상한 모듈 기본. 로컬: `docker-compose.yml` 에 `video-worker`(세 차선 · 상주 · `/work` 디스크 볼륨 · `mem_limit` · 원 구현과 같은 격리), `scripts/dev.sh` 가 함께 올린다. `apps/workbench`: 의존 한 줄씩 + `video-cloudrun`(꺼짐).
**`apps/video-worker`**: 원 구현 일꾼을 옮기고 패키지 · 문구만 바꾼다. 환경변수 `VIDEO_WORKER_API` · `_TOKEN` · `_ID` · `_LANES` · `_MODE` · `_POLL_SECONDS` · `_NICE` · `_FFMPEG_TIMEOUT_MINUTES`(180) · `_JOB_TIMEOUT_MINUTES`(240). 이미지는 베이스 다이제스트 + ffmpeg apt 버전 고정(빌드는 `installDist` 뒤 `docker build`, Cloud Run 이면 `--platform linux/amd64`). 홈서버 배포 계약(선언 한 장 · 컨테이너 안 `wget /health`)에 맞추려고 **상주 모양에 작은 `/health`**(JDK HttpServer)를 더하고 선언 예 `deploy/video-worker.yaml`(도메인 없음 · DB 없음 · 비밀 `VIDEO_WORKER_TOKEN`). 일꾼 → 앱 주소가 플랫폼 내부 망으로 닿는지 K6 에서 확인(안 되면 앱의 공개 주소).
**배포 문서**: 비밀 `<P>_VIDEO_PLAYBACK_SECRET`(무작위) · `video-cloudrun` 의 `<P>_VIDEO_CLOUDRUN_CREDENTIALS_JSON`(운영자) · 비공개 버킷 CORS(`PUT` · `GET` · `ExposeHeaders: ETag`) · 미완료 멀티파트 수명 규칙 **[확인 필요]** · 메모리 표(§1) · Cloud Run 띄우는 순서(프로젝트 · 결제 · 예산 알림 · API · 이미지 저장소 · 작업 정의 `MODE=once` · 그 작업 하나만 실행하는 서비스 계정 키 — 원 구현 운영 문서 4절을 일반화) · 일꾼이 죽었을 때 표.

## 8. 검증 (깨지면 사고 나는 것만 — 원 구현 시험을 옮기고 아래를 더한다)

- **보안**: 일꾼 토큰 없음 · 폐기 · 차선 밖 → 401/403, 사용자 토큰으로 일꾼 API · 일꾼 토큰으로 사용자 API 401 · `upload-urls` 가 허용 이름 밖(`../` · `*.mp4`(init 제외) · 다른 영상 폴더)을 서명하지 않는다 · 해시가 폴더 이름과 다르면 `complete` 거절 · CVE 허용 목록 밖 컨테이너 · 코덱이 `UNSUPPORTED_FORMAT`(일꾼 `FfmpegIntegrationTest` · `JobGuardTest`) · 비공개 재생 토큰 위조 · 만료 · 다른 id → 404 이고 presign 이 만들어지지 않는다 · 비공개 목록 고쳐 쓰기가 폴더 밖 줄에 서명하지 않는다 · 남의 영상 재생 허가 · 조각 목록 · 삭제 404 · 로컬 일꾼 해시 설정이 stage/prod 에서 기동 거부.
- **돈**: Cloud Run 깨우기가 창 안 실행 상한 · 최소 간격을 넘지 않는다(가짜 구글 서버) · 4GB · 기술 상한 넘는 올리기 거절.
- **동시성**: 두 일꾼이 같은 일을 집지 않는다 · 임대를 잃은 일꾼의 늦은 `complete` 는 무시되고 그 폴더는 청소가 쓴다 · 같은 `complete` 두 번 → 결과 같음.
- **데이터 손실**: 올린 원 파일은 원본 단 `complete` 검증 전에는 지우지 않는다 · 트랙 실패가 원본 단을 지우지 않는다 · 삭제 · 청소가 그 영상 폴더만.
- 조립: 영상 모듈 없이 storage 가 지금과 같다 · `-jdbc` 없이 켜면 기동 실패 · 두 방언 `dbTest` · `MigrationLockTest` · `CapabilitiesCatalogTest` · `ModuleDocumentationTest`. 일꾼 ffmpeg 시험은 일꾼 이미지(또는 ffmpeg 가 있는 CI)에서.
- **react**: 이어 올리기(빠진 조각만 · `uploadId` 없으면 처음부터 · 끝나면 비움) · 재생 순서 분기(네이티브 / hls.js / 못 틂) · 토큰 만료 뒤 이어 보기 · 스토리 `play`.
- **e2e 한 여정**(react sample + kotlin sample + 로컬 일꾼, Chromium): 합성 HEVC 1080p 세로 영상 → 진행률 → 재생 가능(원본 단) → 호환 · 사다리 준비 → 자동 재생이 마스터, 세로 유지 → 다른 계정은 404. qa 수동 한 번(캡처 2장까지).

## 9. 내가 정한 것 (바꾸려면 말해 줘)

1. 원 구현의 결정(§0)은 하나도 바꾸지 않는다. 바꾸는 것은 §2 의 일반화와 더하는 둘(이어 올리기 · 진행률)뿐.
2. job-queue 는 고치지 않는다 — 원 구현처럼 변환은 영상 줄의 임대 + 별도 일꾼이 맡고, 큐는 서버 후속(finalize · wake)에만. 앞 초안의 「큐 임대 갱신 · 종류별 상한」 제안은 뺐다.
3. 일꾼은 새 Gradle 모듈이 아닌 `apps/video-worker`(서버와 코드를 나누지 않는다 — 계약은 일꾼 API 한 장).
4. 파일 묶음(`2026-11-02-files-design.md`)과는 서로 기다리지 않는다 — 영상은 자기 올리기 길을 쓰고 storage 에서는 멀티파트 · 공개 영역 · `ListParts` 만 빌린다.
5. 코덱은 H.264 · HEVC(mp4 · mov)만 — webm · VP9 · AV1 은 다음(CVE 완화 목록을 늘리는 일이라 따로).
6. `visibility` 는 올릴 때 정하고 1차에서는 바꾸지 않는다(같은 버킷 안 서버 쪽 복사로 바꾸는 길은 다음).

## 10. 작업 계획 (계획 `docs/superpowers/plans/2026-11-02-video.md`)

| 키 | 레포 | 조각 | 선행 | 크기 | 예상 |
|---|---|---|---|---|---|
| K1 | kotlin | `storage` `listParts`(S3 · 장식 둘) + `video` · `video-jdbc` 뼈대: 설정 · 표 셋(V 두 방언) · 상태 기계 · 올리기 API(이어 올리기 포함) · 정책 · 쿼터 · 청소의 올리기 부분 · 카탈로그(새 셋 + 일꾼 앱 자리 한 번에) | — | 대 | 5h |
| K2 | kotlin | 일꾼 API · 토큰(표 · 운영자 발급 · DeployGuard) · `VideoResultVerifier` · claim/heartbeat(진행률)/upload-urls/complete/fail · 트랙 결정 · `video.finalize` · `VideoEvents` · 임대 만료 · 경보 넷 | K1 | 대 | 6h |
| K3 | kotlin | 재생: `VideoPlayback` 공개(공개 영역 키 · purge 큐) · 비공개(토큰 · 목록 고쳐 쓰기 · 일꾼 입력 주소) · 삭제 · 운영자 경로 · 모듈 문서 2 | K2 | 중 | 4h |
| K4 | kotlin | `apps/video-worker` 옮기기(Plans · Probe · Runner · Transfer · ResultCheck · 시험 전부) · 비공개 입력 · heartbeat 진행률 · `/health` · Dockerfile · CI 의 ffmpeg 시험 줄 | K2 계약 | 대 | 5h |
| K5 | kotlin | `video-cloudrun`(`CloudRunWaker` · 깨우기 서비스 · 상한 · 가짜 구글 서버 시험) · Cloud Run 문서 | K2 | 중 | 2.5h |
| K6 | kotlin | 견본 · workbench 조립 · 노트 영상(마이그레이션) · 로컬 compose 일꾼 · `deploy/video-worker.yaml` · `deploy.md` · CHANGELOG · 견본 통합 시험(가짜 일꾼으로 끝까지) | K3 · K4 · K5 | 중 | 3h |
| R1 | react | `@skeleton/video` 0.1.0: API · `uploadVideo`(이어 올리기) · `useVideos` · `VideoUploadField` · `VideoPlayer`(원 구현 재생 순서 · 화질 칩) · 스토리 · README · 카탈로그 | §5 표(가짜 API) | 대 | 4.5h |
| R2 | react | 견본 노트 조립 · e2e 한 여정 | R1 · K6 합친 뒤 | 중 | 2.5h |

- 순서: K1 ∥ R1 → K2 → K3 ∥ K4 → K5 → K6 → R2 → 끝 검사(지휘: kotlin 바뀐 모듈 + 일꾼 앱 한 번 · react 전체 + `e2e:sample` 한 번, 리뷰 kotlin 1 · react 1, qa 1). 동시 최대 2.
- developer 8회 + 리뷰 2 + qa 1 = 에이전트 11. 벽시계 대략 21h(K1 5h → K2 6h → K4 5h → K5 2.5h → K6 3h 가 긴 줄, R2 2.5h 마지막).
- 같이 만지는 파일: `settings.gradle.kts` · `capabilities.json` · `docs/modules/README.md` 는 K1 이 한 번에, `docker-compose.yml` · 견본 yml · `deploy.md` · CHANGELOG 는 K6 만. 원 구현에서 옮길 때 그 앱 이름 · 도메인 · 경로 · 화면 단위 개념이 남지 않았는지 각 조각이 `grep` 으로 확인한다.

## 정해야 할 것 (그 앱과 다르게 갈 지점만)

1. **남에게 안 보이는 영상도 필요할까** — 그 앱은 모든 영상을 공개 저장소 + CDN 으로 틀었다. 스켈레톤의 견본(노트)은 주인만 보는 자료다.
   - A. 공개 · 비공개 둘 다, 영상마다 고른다 **(추천)** — 공개는 그 앱 그대로(CDN 캐시 · 주소를 알면 누구나), 비공개는 앱이 재생 목록을 내줄 때마다 조각에 몇 시간짜리 출입증을 붙인다. 설치할 것이 없고 내 컴퓨터 · R2 어디서나 같다.
   - B. 그 앱처럼 공개만 — 가장 빨리 끝난다. 견본 노트 영상도 주소를 아는 사람은 볼 수 있게 된다.
   - C. 비공개만 — 공개 서비스에서 CDN 캐시를 못 쓴다.
2. **홈서버의 일꾼이 어디까지 맡을까** — 그 앱은 1코어 VM 이라 원본 단만 하고, 호환 · 사다리는 Cloud Run 에 넘겼다. 홈서버(M3 Pro)는 4K 3분 사다리를 56초에 끝낸다(측정).
   - A. 홈서버 일꾼이 세 가지 다(CPU 2개 · 메모리 3GB · 낮은 우선순위로 제한), Cloud Run 은 켜고 싶을 때만 **(추천)** — 클라우드 계정 · 돈 없이 다 돈다. 변환하는 동안 홈서버 다른 앱이 조금 느려질 수 있다.
   - B. 그 앱처럼 홈서버는 원본 단만, 호환 · 사다리는 Cloud Run — 홈서버가 가장 한가하다. 구글 클라우드 계정이 있어야 화질 단계가 생긴다.
3. **올린 원래 파일을 지울까** — 그 앱은 원본 단(같은 화질 · 위치 정보 제거)이 만들어지고 1시간 뒤 원래 파일을 지웠다. 촬영 시각 · 위치 · 두 번째 소리 트랙 같은 부가 정보는 그때 사라진다.
   - A. 그 앱처럼 지운다 **(추천)** — 저장 공간이 절반이고 위치 정보가 어디에도 안 남는다. 화질은 그대로다.
   - B. 남긴다(비공개) — 부가 정보까지 보관한다. 저장 공간이 두 배다.
4. **아무 회원이나 올린 공개 영상을 한 번 더 검사할까** — 원본 단은 올린 사람의 영상 데이터를 다시 만들지 않고 그대로 내보내서, 조작된 영상이 보는 사람의 브라우저를 노릴 수 있다. 그 앱은 책임이 분명한 운영자 영상이라 검사를 끄고, 아무나 올리는 경우에만 켜기로 했다. 스켈레톤은 회원 누구나 올린다.
   - A. 공개 영상만 검사를 켠다 **(추천)** — 1080p 3분 영상에 1코어로 약 1분 더 걸린다(측정). 모든 문제를 잡지는 못한다.
   - B. 그 앱처럼 끈다 — 가장 빠르다.
   - C. 비공개까지 모두 켠다 — 가장 안전하지만 모든 영상이 늦어진다.

참고: [Cloudflare WAF HMAC 토큰 — Pro 이상](https://developers.cloudflare.com/waf/custom-rules/use-cases/require-valid-hmac-token/) · [Cloud Run 할당량](https://docs.cloud.google.com/run/quotas) · [S3 호환 HLS 프록시(재생 목록 presign 고쳐 쓰기)](https://awesome.ecosyste.ms/projects/github.com%2Fhighcanfly-club%2Fhls-video-s3-proxy) · [R2 + Worker](https://ittybit.com/guides/replace-cloudflare-stream-with-r2) · [Cloudflare Stream 서명 토큰](https://developers.cloudflare.com/stream/viewing-videos/securing-your-stream).

## 결정됨 (2026-11-02)

형과 정했다. 이 절이 본문보다 우선한다.

1. 비공개 영상 — 형: "이번에 넣음" → **A**: 영상마다 공개 / 비공개를 고른다. 비공개는 재생 목록을 내줄 때 조각마다 짧은 출입증(서명 URL)을 붙인다.
2. 홈서버 일꾼 범위 — **A**: 홈서버 일꾼이 원본 단 · 호환 · 사다리 전부(CPU 2 · 메모리 3GB · 낮은 우선순위). Cloud Run 은 원할 때만 켜는 선택.
3. 올린 원래 파일 — 형: "원본 화질만 유지해주면 되나" → **A**: 원본 단이 생기고 1시간 뒤 지운다. 화질은 재인코딩 없는 원본 단으로 그대로 남는다(문서에 이 점을 분명히).
4. 공개 영상 정상 여부 검사 — 형이 기준을 물어 지휘 세션이 설명하고 **기본 켜짐**으로 정했다(운영자만 올리는 앱은 설정으로 끈다). 기준: ① ffmpeg 가 모든 프레임을 오류 없이 끝까지 디코드한다 ② 허용 코덱 · 컨테이너 · 스트림 수(영상 1 · 소리 0~1, 데이터 · 첨부 스트림 없음) ③ 실제 길이 · 해상도가 메타데이터 · 상한과 맞는다. 하나라도 어긋나면 공개하지 않고 실패로 둔다.
5. 정상 여부 검사의 세부(4 를 고친다) — 형: "폰이 아니라 팬이 편집하거나 한 영상이면?" → 편집 앱(프리미어 · 캡컷 · 블로 등)이 내보낸 파일과 아이폰 MOV 에는 타임코드 · 메타데이터 트랙, 표지 그림, 챕터, 자막 트랙이 흔히 붙는다. 이것들은 **거절하지 않고 벗겨 낸다**(첫 영상 1 · 첫 소리 1 만 남김). 가변 프레임레이트 · 비표준 해상도도 통과. **거절은 디코드가 실제로 실패할 때만**(손상 · 조작된 패킷, 끝까지 못 풀림, 길이 · 해상도가 상한 초과). 타임스탬프 경고 같은 무해한 경고는 세지 않는다. 시험에 편집 앱식 파일(타임코드 트랙 · 표지 그림 · 가변 프레임레이트) 견본을 넣어 통과를 확인한다.
6. 표지 그림 — 형: "표지도 설정 해놨으면 걍 쓰면 되는거 아냐?" → 파일에 표지 그림(attached picture)이 있으면 그것을 **포스터로 쓴다**. 단 파일 묶음의 이미지 경로(업로드 검사 · 메타데이터 제거 · 표시본 굽기)를 거친다. 없으면 지금처럼 영상 프레임에서 포스터를 만든다. 올린 사람이 나중에 포스터를 바꿔 끼우는 경로도 둔다. 영상 조각에서는 그 트랙을 벗겨 낸다(재생에는 쓰이지 않음).
7. 받아들이는 원칙(4 · 5 를 단순하게 정리) — 형: "쓸데 없는거 자꾸 붙이려는거 같은데. 진짜 문제 될 부분 말고는 걍 그대로 받아주자" → 올라온 영상은 그대로 받는다. **거절은 실제로 디코드가 안 되는 파일(손상 · 조작)과 크기 · 길이 상한 초과뿐.** 부가 트랙을 골라 벗기는 규칙 · 트랙 수 검사 · 소프트 자막 살리기는 만들지 않는다 — HLS 조각은 형식상 영상 · 소리만 싣고, 나머지는 따로 처리하지 않을 뿐이다. 위치 · 촬영 정보 제거(개인정보)는 유지한다. 표지 그림이 있으면 포스터로 쓰는 것(6)은 유지.
8. 움짤(GIF) → 영상 변환 — 형: "용량 줄일 수 있는 방식 적용하면 좋지. 화질 해치지 않는 이상." → 큰 플랫폼 관행대로 **움직이는 GIF(그리고 움직이는 WebP)를 같은 ffmpeg 일꾼이 소리 없는 반복 MP4(H.264, 화질 손실이 눈에 안 띄는 설정 — CRF 낮게 · 원본 크기 그대로 · 프레임 시간 유지)로 바꿔** 표시본으로 쓴다. 화면 부품은 `<video autoplay loop muted playsinline>` 로 GIF 처럼 보인다. **투명 배경이 있는 움짤은 MP4 가 투명을 못 담으므로 움직이는 WebP(투명 유지)로 바꾼다**(형: "mp4 가 정배야? webp 이런거 말고?" — 관행은 MP4 가 기본: 가장 작고 기기 하드웨어로 재생, WebP 는 투명이 필요할 때). 변환본이 원본보다 크면 원본 사본을 쓴다. 파일 묶음의 「움직이는 이미지 display」 경로와 이어 붙이는 조각 하나(K · R 각 소)로 작업 계획에 더한다.
9. 투명 판정 — 형: "다른 애들 투명은 어케 하는데? 그거 잘 판단 가능해?" → 파일의 투명 표시(GIF 그래픽 제어 확장의 투명 색 · WebP 알파 플래그)로 판정하지 않는다 — GIF 는 「앞 장면과 같은 부분」을 투명으로 적는 최적화가 흔해 거의 다 오판한다. ffmpeg 가 변환하며 **합성된 실제 화면(각 프레임을 앞 장면 위에 겹친 결과)** 을 RGBA 로 풀 때, 알파 < 255 인 픽셀이 하나라도 있으면 투명 → 움직이는 WebP, 없으면 MP4. 변환과 같은 디코드에서 본다(추가 디코드 없음). 시험: 최적화 투명만 쓰는 GIF 는 MP4, 실제 투명 배경 GIF 는 WebP.
10. 움직이는 WebP 입력 — ffmpeg 의 움직이는 WebP 디코드는 최근 버전에만 있다. 구현 때 일꾼 이미지의 ffmpeg 버전으로 실제 파일을 돌려 보고(시험 견본 포함), 못 읽으면 움직이는 WebP 는 변환하지 않고 지금의 메타데이터 제거 사본을 쓴다(이미 가벼운 형식). 결과는 진행 문서에 실측으로 남긴다.
