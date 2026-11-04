# apps/video-worker — 영상 변환 일꾼

서버의 일꾼 API(`/api/internal/video/*`, 모듈 `video`)에서 일을 **집어 가** 원본 단(재인코딩 없는 fMP4 HLS 조각) · 호환 단(H.264 720p) · 사다리(1080/720/480)를 만들어 올리는 작은 Kotlin 프로그램 + ffmpeg 이미지다.
서버 코드에 의존하지 않고(계약은 JSON 한 장 — `Api.kt` 의 DTO), **저장소 키를 갖지 않는다** — 서버가 서명한 GET · PUT 주소만 쓴다. 같은 이미지가 홈서버 컨테이너 · Cloud Run 작업 · 아무 기계(토큰만 있으면)에서 돈다.

## 환경변수

| 이름 | 기본 | 뜻 |
|---|---|---|
| `VIDEO_WORKER_API` | (필수) | 서버 주소 (`http://app:8080`) |
| `VIDEO_WORKER_TOKEN` | (필수) | 일꾼 토큰 — 운영자 발급 API 가 한 번 보여 준 평문. 로그 · 출력에 안 찍힌다 |
| `VIDEO_WORKER_ID` | `worker-<HOSTNAME>` | 일꾼 이름(`[A-Za-z0-9._:-]{1,64}`) |
| `VIDEO_WORKER_LANES` | `REMUX,COMPAT,LADDER,ANIMATED` | 이 일꾼이 맡을 차선 (토큰의 차선과 겹치는 것만 받는다). `ANIMATED` = 움직이는 GIF · WebP 의 표시본 변환(소리 없는 MP4, 투명 배경이면 움직이는 WebP) |
| `VIDEO_WORKER_MODE` | `poll` | `poll` = 상주(큐가 비면 쉬었다 다시 집는다) · `once` = 일이 없을 때까지 하고 끝낸다(Cloud Run 작업) |
| `VIDEO_WORKER_POLL_SECONDS` | `5` | `poll` 에서 큐가 비었을 때 쉬는 간격 |
| `VIDEO_WORKER_NICE` | `0` | 양수면 `nice -n` 으로 낮춘 우선순위로 ffmpeg 를 돌린다(홈서버에서 다른 일과 같이 쓸 때) |
| `VIDEO_WORKER_FFMPEG_TIMEOUT_MINUTES` | `180` | ffmpeg 한 번의 벽시계 한도 |
| `VIDEO_WORKER_JOB_TIMEOUT_MINUTES` | `240` | 일 하나(내려받기 · ffmpeg · 올리기 전부)의 한도 — 넘으면 ffmpeg 를 죽이고 재시도 가능한 실패로 알린다 |
| `VIDEO_WORKER_HEALTH_PORT` | `8080` | `poll` 모양의 `GET /health`(200 `{"status":"UP"}`) 포트. `0` 이면 안 띄운다. `once` 는 안 띄운다 |
| `VIDEO_WORKER_WORKDIR` | `/work` | 일마다 임시 폴더를 만드는 곳 (이미지에서는 `/work`) |
| `VIDEO_WORKER_STORAGE_ORIGIN` | (없음) | 로컬 compose 에서만 — 서명 주소의 출처가 컨테이너 안에서 안 열릴 때 이 출처로 바꿔 부른다(서명에 든 Host 는 유지) |

## 움직이는 이미지 차선 (`ANIMATED`)

서버(`storage-image` 와 `video` 가 함께 켜졌을 때)가 올린 움직이는 GIF · WebP 의 표시본 변환을 준다. 한 번의 ffmpeg 디코드에서 ① 소리 없는 H.264 MP4(`-crf 14`, 크기 · 프레임 시간 그대로, 홀수 크기는 마지막 한 줄만 자름, BT.709 표시) ② 움직이는 WebP(무손실, 투명 유지) ③ 합성한 실제 화면(RGBA)의 알파 최솟값을 한꺼번에 낸다. 알파가 255 미만인 픽셀이 하나라도 있으면 WebP(`display.webp`), 없으면 MP4(`display.mp4`)를 올리고 나머지는 버린다 - 파일의 투명 표시가 아니라 합성 결과를 본다. 올리지 않고 끝나는 길은 모두 **다시 하지 않는 실패**다(서버의 표시본은 사본으로 남는다): 변환본이 원본보다 작지 않다 → `PROCESSING_FAILED` · ffmpeg 가 못 읽는다(움직이는 WebP 는 ffmpeg 6.1 이 못 풀고, 정지 이미지로만 풀려도 같다) → `UNSUPPORTED_FORMAT` · 이미 투명한 움직이는 WebP → 변환하지 않는다. 입력 허용 목록(`-format_whitelist` · `-codec_whitelist`)은 이 차선만 `gif` · `webp` 를 열고 영상 차선의 목록은 그대로다.

## 한 일의 순서

claim → 내려받기(remux 만) → probe → 거절 판정 → 조각 내기 → (공개 영상이면 `verifyDecode`: 쓰는 두 스트림을 끝까지 디코드, 오류가 나면 `CORRUPT`) → 결과 검사 → 폴더 해시 → 포스터 → `upload-urls` → PUT 전부(**목록 `index.m3u8` 은 맨 끝**) → `complete`.
heartbeat(30초)가 임대를 늘리고 진행률(0..99)을 싣는다. 임대를 잃으면(409) ffmpeg 를 멈추고 일을 버린다.

- **거절**은 실제로 안 열리거나 디코드가 안 되는 파일(손상 · 조작), 허용 목록 밖 컨테이너 · 코덱(`UNSUPPORTED_FORMAT`, 재시도 없음), 크기 · 길이 상한 초과뿐이다. 타임코드 · 메타데이터 트랙 · 표지 그림 · 챕터 · 소프트 자막 · 가변 프레임율 · 비표준 해상도는 통과한다 — HLS 조각에는 첫 영상 · 첫 소리만 실린다. 위치 · 촬영 정보는 지운다.
- **포스터**: 파일에 표지 그림(attached picture)이 있으면 그것, 없으면 1초 지점 프레임. 긴 변 1280 이하 JPEG.
- **비공개 영상**: 호환 · 사다리의 입력이 서명 재생 목록 주소일 수 있다 — 목록 · init · 조각이 모두 질의 서명이 붙은 절대 주소여도 ffmpeg `-f hls` 가 따라간다(프로토콜 목록 `file,http,https,tcp,tls,crypto`).

## ffmpeg 격리 (바꾸면 보안 회귀)

믿지 못하는 올린 파일은 디스크로 내려받아 `-protocol_whitelist file,pipe` · `-format_whitelist mov,mp4,m4a,3gp,3g2,mj2` · `-f mov` 강제 · `-codec_whitelist`(영상 h264 · hevc / 소리 aac · mp3 · alac · ac3 · eac3 · opus · pcm_* / 표지 mjpeg · png / 자막 mov_text · cc_dec(c608) · webvtt · subrip · srt — 산출물에는 안 싣는다)로만 읽는다 — ffmpeg 6.1.1 에 고쳐진 패키지가 없는 CVE-2025-59734(SANM) · CVE-2024-32228 완화. 만든 조각을 다시 읽을 때는 `-f hls` · `h264,hevc,aac` 만. 목록은 `Plans.kt` 의 `Allowed` 한 곳이고, 늘릴 때는 `Probe.rejection` 도 같이 본다. 회귀 시험: `FfmpegIntegrationTest` · `JobGuardTest` · `PlansTest`.
이미지는 비루트(uid 10001) · 읽기 전용 루트 파일시스템 · 임시 폴더 `/work` 만 쓰는 것을 전제로 한다. 실행 쪽이 `--cap-drop ALL` · `--pids-limit` · 메모리 한도 · `/work` 마운트를 정한다(compose 예는 견본 조각).

## 시험 · 이미지

```bash
./gradlew :apps:video-worker:test            # ffmpeg · ffprobe 가 PATH 에 있으면 합성 영상으로 전부, 없으면 ffmpeg 시험은 건너뜀
./gradlew :apps:video-worker:installDist
docker build -t video-worker apps/video-worker      # Cloud Run 이면 --platform linux/amd64
```

이미지는 베이스 **다이제스트** + ffmpeg apt **버전**을 고정한다(`Dockerfile` 머리말). 올릴 때는 두 줄을 함께 바꾸고 `FfmpegIntegrationTest` 를 그 버전으로 다시 돌린다(호스트 ffmpeg 가 이미지와 다르면 이미지 안에서 돌린다).
JVM 은 `-Xmx96m` — 힙이 아니라 ffmpeg 가 메모리를 쓴다(호환 단 1080p 약 190MB · 4K 사다리 약 470MB~1GB 이상, `docs/superpowers/specs/2026-11-02-video-design.md` §1).
