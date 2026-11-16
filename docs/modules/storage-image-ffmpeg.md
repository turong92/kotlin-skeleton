# storage-image-ffmpeg

움직이는 GIF · WebP 의 표시본(`display`)을 **앱 안에서 ffmpeg 로** 소리 없는 H.264 MP4 로 바꾼다(투명이면 움직이는 WebP) - `storage-image` 의 `AnimatedDisplayConverter` 확장 지점 구현이다. 별도 일꾼 서버 없이 `job-queue-jdbc` 의 비동기 작업으로 돈다(서버가 여럿이어도 줄 하나는 한 서버가 집는다). 10MB GIF 가 보통 1/5 ~ 1/10 이 된다. 기본 꺼짐 - 모듈을 넣기만 해서는 지금과 같다(사본 display).

- **계약** 사본 display 가 먼저 `READY` 로 쓰이고 변환은 뒤에서 한다. 변환본은 `<폴더>display.<ext>` 로 올라가 `AnimatedDisplays.use` 로 display 의 다른 형식(`alt`)에 더해진다 - 상태 응답의 `display.alternates` 에 `mp4`(또는 `webp`)가 나타나고, 사본(원본 형식)은 어떤 경우에도 그대로다. 실패하거나 변환본이 원본보다 작지 않으면 올리지 않고 사본이 계속 쓰인다. `<img>` 로 그리는 소비자는 늘 사본을 받는다 - MP4 를 쓰는 쪽(`<video loop muted playsinline>`)만 alt 를 본다.
- **한 번의 디코드에서 결과를 가른다** ffmpeg 가 앞 장면 위에 합성한 실제 화면의 알파 최솟값이 255 미만이면 투명이라 움직이는 WebP(무손실), 아니면 MP4 - 안 쓰는 쪽은 버린다. GIF 파일의 투명 색 표시는 「앞 장면과 같은 부분」 최적화로도 켜져 믿을 수 없어 합성한 화면을 본다(`video` 일꾼과 같은 규칙). 투명한 움직이는 WebP 는 이미 알맞아 변환하지 않는다(결과 이름이 사본과 겹친다).
- **인코딩** 기본 `-an`(소리 없음) · libx264 `-crf 27` · `-preset veryfast` · `-pix_fmt yuv420p` · 짝수 크기(홀수는 마지막 한 줄을 자른다) · `-movflags +faststart` · 프레임 시간 그대로 · 색은 BT.709 표시. `crf` · `preset` · `pixel-format` 은 설정으로 바꾼다. 반복은 플레이어 몫이다.
- **ffmpeg 를 가두는 법** 입력은 gif · webp 뿐이다 - 원본 머리 바이트(`GIF87a` · `GIF89a` · `RIFF…WEBP`)로 판별하고 확장자 · Content-Type 은 믿지 않는다. ffmpeg 에는 `-protocol_whitelist file`(네트워크 · 파이프 없음) · `-format_whitelist` · `-codec_whitelist`(그 형식 하나) · `-f`(형식 고정)를 입력 앞에 건다. 시간(`timeout`, 넘으면 프로세스를 죽이고 실패) · 입력 바이트(`max-source-bytes`) · 그림 크기(`max-pixels`, 기본 4096 x 4096 - 넘으면 ffmpeg 를 부르지 않는다) · 스레드(`threads`, 기본 2) · 출력(원본 크기에서 쓰기를 멈추고 원본보다 작지 않으면 버림)에 상한이 있다. 원본의 메타데이터(GIF 주석 등)는 변환본에 남지 않는다.
- **실패와 재시도** 던지는 것은 다시 해 볼 만한 실패(ffmpeg 를 못 띄움 · 시간 초과 · 신호 종료(메모리 부족 등) · 저장소 오류)뿐이다 - 큐가 백오프로 `max-attempts` 번까지 다시 하고, 소진되면 DEAD 로 끝난다(사본은 그대로). 허용 목록 밖 · 상한 초과 · 변환본이 안 작음 · ffmpeg 의 오류 종료(고장 난 입력, 8.0 미만이 못 푸는 움직이는 WebP - 같은 입력은 매번 같다)는 던지지 않고 로그만 남기고 끝난다.
- **멱등** 같은 `keyHash` 요청이 또 와도(중복 작업 · 올린 뒤 죽은 일의 재시도) 이미 올라간 변환본을 그대로 `use` 하므로 다시 인코딩하지 않고 결과가 같다.
- **`video` 와 같은 자리** `video` 모듈도 같은 확장 지점을 채운다(외부 일꾼으로 보냄). 둘 다 켜면 **이 모듈이 이긴다**(자동설정이 먼저 평가된다). 앱이 자기 `AnimatedDisplayConverter` 빈을 두면 그것이 둘 다 이긴다.
- **켜려면** `storage-image`(켜짐 + `storage-image-jdbc` + 엔진) · 저장소(`storage-s3`) · `job-queue-jdbc` 가 있어야 한다 - 없으면 기동이 실패하고 빠진 빈을 말한다. 마이그레이션은 없다(기존 `jobs` 표). 작업 종류는 `storage.image.ffmpeg.animated`.
- **배포** 컨테이너 이미지에 ffmpeg 가 있어야 한다: Dockerfile 에 `RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*`(또는 정적 빌드를 `path` 로 가리킨다). **움직이는 WebP 입력은 ffmpeg 8.0 이상이 풀 수 있다**(6.1 은 못 푼다 - 그 입력은 변환하지 않고 사본이 쓰인다). GIF 는 어느 버전이든 된다. ffmpeg 는 `libx264` · `libwebp_anim` 이 들어 있어야 한다. 큐의 `concurrency` 가 1(기본)이면 변환이 도는 동안(최대 `timeout`) 같은 프로세스의 다른 작업(썸네일 등)이 기다린다 - 이미지를 많이 받으면 큐의 `concurrency` 를 올린다. `timeout` 은 큐의 `stale-lock-timeout`(기본 10분)보다 짧게 둔다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:storage-image-ffmpeg"))` |
| 함께 오는 모듈 | `storage-image`, `job-queue-jdbc`, `persistence-jdbc`, `platform`, `storage` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.storage.image.ffmpeg` ([설정 블록](../config/modules/storage-image-ffmpeg.yml)) |
| 기본 동작 | 꺼짐. `skeleton.storage.image.ffmpeg.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | 켜면 `storage-image`(켜짐) · `storage-s3` · `job-queue-jdbc` 의 빈과 실행 파일 ffmpeg. |
| 교체 지점 | `AnimatedConversion`, `AnimatedDisplayConverter` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/storage-image-ffmpeg/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
