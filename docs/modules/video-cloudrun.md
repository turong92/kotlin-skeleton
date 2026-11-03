# video-cloudrun

변환 일꾼이 0 으로 줄어드는 실행 환경(**Google Cloud Run 작업**)에서 돌 때, 서버가 호환 단 · 사다리 일이 줄 서면 그 작업을 **깨우는** `VideoWaker` 구현이다. 서버는 일꾼을 부르지 않는다 - 일꾼이 `claim` 으로 집어 간다. Cloud Run 은 0 으로 줄면 스스로 집지 못하니 서버가 **작업 실행만** 시작한다(Admin API `POST …/jobs/<작업>:run`, 덮어쓰기 없이 작업 정의 그대로). 실행은 `MODE=once` 로 큐가 빌 때까지 집고 끝난다.

**꺼짐이 기본이다** - Cloud Run 은 원할 때만 켜는 선택이다. 홈서버 컨테이너나 아무 기계에서 같은 일꾼 이미지를 상주(`poll`)로 돌리면 이 모듈이 필요 없다. 켜기: `skeleton.video.cloud-run.enabled=true` + `project` · `region` · `job` + `credentials-json`(+ `skeleton.video.enabled=true` 와 `video-jdbc`). 설정 이름 모양이 아니거나 키가 비었거나 읽을 수 없으면 **기동 실패**(속성 이름만 말하고 값은 싣지 않는다).

**서비스 계정 키는 운영자 비밀이다.** 환경변수 `<P>_VIDEO_CLOUDRUN_CREDENTIALS_JSON`(배포 선언의 `secrets:`)로만 넣는다. 키 · 접근 토큰 · 구글 응답 본문은 로그 · 예외 · `toString` · 경보 어디에도 나오지 않고, 실패 이유는 상태 코드 · 예외 종류만 든다. 구글 라이브러리 없이 JDK 로 JWT(RS256)를 서명해 토큰 주소에서 접근 토큰을 받고, 만료 2분 전까지 다시 쓴다. 키 파일 안의 `token_uri` 는 읽지 않는다(키 파일이 나가는 주소를 정하지 못하게) - 토큰 주소는 설정의 고정값이다. 리다이렉트는 따라가지 않는다. 프로젝트 · 지역 · 작업 이름은 `[A-Za-z0-9_-]{1,63}` 만 통과해 요청 경로에 다른 것이 끼지 못한다.

## 돈 상한 - 깨우기는 돈이다

`video.wake` 후속이 부르는 깨우기를 아래 순서로 거른다. 걸러지면 구글을 **부르지 않고** 조용히 넘어간다(이미 도는 실행이 큐가 빌 때까지 집으니 일은 잃지 않는다).

| 설정 | 기본 | 뜻 |
|---|---|---|
| `execution-window` · `max-executions` | 10m · 3 | 창 안에 받아들여진 깨우기가 상한이면 부르지 않는다 |
| `min-wake-gap` | 30s | 마지막으로 받아들여진 깨우기에서 이 간격이 안 지났으면 부르지 않는다 |

기록은 `video-jdbc` 의 `video_wake_runs` 표(성공 · 실패 한 줄씩, 7일 지나면 지운다)이고, 포트는 `VideoWakeRuns` 다. 같은 일을 두 실행이 집어도 임대 때문에 안전하다 - 상한 · 간격은 안전이 아니라 비용 장치다.

## 실패 - 자동으로 다른 곳으로 넘기지 않는다

호출이 거절되거나 닿지 않으면 실패를 기록하고 던진다 - `video.wake` 작업이 큐의 백오프로 다시 한다. 마지막 성공 뒤 연속 `alert-after-failures`(3)번이면 `alert` 모듈이 있을 때 `VIDEO_WAKE_FAILED`(즉시)를 낸다. **일은 큐에 그대로 남고, 다른 일꾼으로 자동으로 돌리지 않는다** - 자격 · 설정을 고치거나, 상주 일꾼에 `COMPAT` 차선을 주는 것은 사람이 정한다. 상한 때문에 깨우기가 걸러진 것은 실패가 아니다(경보도 없다). 호환판이 오래 기다리면 별도로 `VIDEO_COMPAT_STALE` 이 간다.

## Cloud Run 띄우는 순서

1 ~ 5 는 **운영자가 한다**(계정 · 결제는 대신 못 한다). 이 문서의 명령 · 설정은 가짜 구글 서버 시험까지만 확인됐다 - **실제 구글과의 연결은 계정을 만든 뒤 처음 돈다.** 값은 자리표(`<…>`)다.

1. 구글 클라우드 **프로젝트**를 만들고 **결제 계정**을 연결한다. **예산 알림**도 같이 만든다 - 상한이 없으면 폭주 때 비용이 사용량에 비례한다.
2. API 를 켠다: Cloud Run Admin API, (이미지 저장소를 쓰면) Artifact Registry.
3. **이미지 저장소**를 만들고 일꾼 이미지를 올린다 - 빌드는 `apps/video-worker`(그 README 가 정본). **x86 Cloud Run 이면 `--platform linux/amd64`** - 개발 PC 이미지는 arm64 일 수 있다.
4. **Cloud Run 작업(Job)** 을 정의한다: 이미지, 환경변수 `VIDEO_WORKER_API=https://<앱 주소>` · `VIDEO_WORKER_MODE=once` · `VIDEO_WORKER_LANES=COMPAT,LADDER` · `VIDEO_WORKER_TOKEN`(Secret Manager 에 둔 일꾼 토큰 - 운영자 API 로 이 차선만 주고 발급). vCPU · 메모리 · 작업 시간 · 병렬 수는 아래 표를 보고 정한다.
5. **깨우기용 서비스 계정**을 만든다: 그 작업 **하나**를 실행할 권한만(작업 단위 `run.jobs.run`, 예 `roles/run.invoker` 를 그 작업에만) 주고 키(JSON)를 만든다. 일꾼 실행 자체의 서비스 계정(Secret 읽기)과 따로 둔다.
6. 서버 설정: `skeleton.video.cloud-run.project` · `region` · `job`(선언의 `env:`) 과 `credentials-json`(`<P>_VIDEO_CLOUDRUN_CREDENTIALS_JSON`, 선언의 `secrets:`). 상한 `max-executions` · `execution-window` · `min-wake-gap` 은 위 표.
7. **먼저 stage 에서** 합성 영상으로 끝까지 본다(호환 조건 밖 영상 → 호환판 → 사다리 → 재생). 그다음 운영.

작업 크기 지침(**[측정]**/**[어림]** - Cloud Run x86 에서 재지 않았다):

| 차선 | 메모리 | 비고 |
|---|---|---|
| 호환판 | 프로세스 약 240MB + **호환판 출력 크기**(21MB/분 - Cloud Run 의 쓰기 가능 파일시스템은 메모리) | 일상 크기(4K 3분 63MB)는 1GiB 안팎으로 충분. 아주 긴 영상은 수 GB |
| 사다리 | 4K 는 2 ~ 3GB(1GB 에서 OOM **[측정]**) | 1080p 는 그보다 작다 |

## 단가 (어림)

Cloud Run 작업 단가(공식 표 2026-10-05, 지역 단가는 **[확인 필요]**): vCPU-초 $0.000018 · GiB-초 $0.000002, 작업 무료 몫 월 240,000 vCPU-초 · 450,000 GiB-초. 호환 1080p 3분 ≈ $0.0024 · 4K 3분 사다리 ≈ $0.0085 **[어림]**. **[확인 필요]**: 서울(`asia-northeast3`) 단가 · 송신 단가 · 임시 디스크 가격 · x86 vCPU 의 ffmpeg 속도 · 작업 시작 지연 · 키 없이 Workload Identity 로 하는 법(키 방식으로 시작).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:video-cloudrun"))` |
| 함께 오는 모듈 | `video`, `platform`, `storage` |
| 컴파일 전용 | `alert` (연속 실패 경보) |
| 설정 접두사 | `skeleton.video.cloud-run` - [docs/config/modules/video-cloudrun.yml](../config/modules/video-cloudrun.yml) |
| 기본 동작 | 꺼짐. `skeleton.video.cloud-run.enabled=true` 로 켜면 `video` 의 기본 `NoopWaker` 대신 상한을 두른 Cloud Run 깨우기가 선다. |
| 부팅에 필요한 것 | 켰다면 `project` · `region` · `job` 과 서비스 계정 키(`<P>_VIDEO_CLOUDRUN_CREDENTIALS_JSON`, 운영자 비밀) · `VideoWakeRuns`(`video-jdbc`) - 없거나 못 읽으면 기동 실패. |
| 교체 지점 | `VideoWaker`(`videoWaker` 빈 - 다른 클라우드의 깨우기를 끼운다), `WakeFailureAlert`(경보 방식) |
| 마이그레이션 | 없음 (`video_wake_runs` 는 `video-jdbc` 의 것) |
| 프론트 짝 | 없음 |
| 테스트 | `modules/video-cloudrun/src/test` (가짜 구글 서버 - 실제 구글은 부르지 않는다) |

[모듈 색인](README.md) · [video](video.md) · [video-jdbc](video-jdbc.md)
