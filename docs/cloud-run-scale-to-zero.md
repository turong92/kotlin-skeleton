# Cloud Run 0대 운영 — 안 쓸 때는 인스턴스 0

단발 서비스(가끔 쓰는 이벤트 · 도구)를 Cloud Run 에 올려 **안 쓸 때 0대**로 내리는 방법이다. 비용은 요청이 있는 동안만 든다. 대가는 **콜드스타트**(0 → 1 때 첫 요청이 기다리는 시간)와 **타이머가 없다**는 것이다.
이 문서가 정본이다. 홈서버 배포(`docs/deploy.md`)와는 따로 읽는다 — 홈서버는 인스턴스가 항상 떠 있다. (실제 Cloud Run 배포 · 클라우드 계정 작업은 이 레포가 하지 않는다. 여기에는 앱이 지킬 것과 설정 예만 있다.)

## 1. JVM 으로 갈까, 네이티브로 갈까

| | JVM 그대로 | JVM 다듬기 | 네이티브 |
|---|---|---|---|
| 빌드 | 지금 `Dockerfile` | AOT + CDS 아카이브(이미지에 굽는다) | `Dockerfile.native` ([native-image](native-image.md)), 몇 분 |
| 콜드스타트 (/health 200) | 약 7.7s | 약 3.3s | **약 0.6s** |
| 첫 로그인 | 320~380ms | 237ms | **120ms** |
| 메모리 (요청 뒤) | 약 257MB (`docker stats`, 페이지 캐시 포함 — 방식이 달라 참고만) | 234MB (프로세스 RSS) | **208MB** (프로세스 RSS) |
| 켜기/끄기(환경변수) | 실행 때 가능 | **빌드 때 굳음** | **빌드 때 굳음** |
| 위험 | 없음 | 낮음 | 새 의존성마다 리플렉션 규칙 · 릴리스 전 시험 |

(**전부 같은 앱(스타터 `apps/api`)·같은 조건**: 리눅스 arm64 컨테이너, CPU 1 · 메모리 512MB 제한, DB 는 컨테이너 밖, 5회 중앙값 — 정본은 측정 보고서. 맥(M3 Pro)에서 잰 값이라 Cloud Run(amd64)과 다를 수 있다.
모듈을 모두 얹은 `tools/native-check` 네이티브(arm64)는 새 DB 마이그레이션 포함 약 0.92s · RSS 212MB(기본 흐름 전체를 돈 뒤 약 288MB)다. amd64 는 에뮬레이션으로만 돌려 봤다(약 3.65s — 진짜 x86 값이 아니다). **고르는 기준**: 콜드스타트가 사용자가 기다리는 요청 경로면 네이티브,
무거운 라이브러리(JS 압축기 · ImageIO · jOOQ)가 핵심이거나 모듈이 자주 바뀌면 JVM 다듬기. 512MB 제한에서 세 방식 모두 요청 몇 개 동안은 들어맞았다 — 실제 부하의 메모리(동시성 · 힙)는 재 보지 않았으니 배포 값은 부하로 정한다.)

## 2. 0대에서는 타이머가 돌지 않는다 — "요청 구동" 모드

인스턴스가 없으면 앱 안의 폴링 스레드 · 주기 작업이 없다. 일감은 DB 에 남아 있고(`jobs` 표), **밖의 스케줄러가 주기로 앱을 깨우면** 그때 일하고 끝낸다.

```yaml
skeleton:
  job-queue:
    mode: on-request                 # 폴링 스레드를 띄우지 않는다 (기본 polling = 지금까지와 같다)
    wake:
      path: /internal/jobs/drain     # 깨우기 주소 (POST)
      secret: ${JOB_QUEUE_WAKE_SECRET}   # 공유 비밀값 — 헤더 X-Wake-Secret 과 맞춘다. 비면 주소는 닫혀 있다(404)
      time-budget: 25s               # 새 묶음을 집기를 멈추는 시간 (이미 집은 일감은 끝까지 돈다)
```

환경변수로는 `<PREFIX>_JOB_QUEUE_MODE=on-request` · `<PREFIX>_JOB_QUEUE_WAKE_SECRET=…` (배포 선언의 `secrets:` 에 이름을 적는다). **`mode` · `wake.secret` 은 실행 때 읽는다** — 깨우기 주소는 모드와 상관없이 이미지에 들어 있고(폴링 모드에서는 404), 같은 네이티브 이미지를 환경변수로 폴링 ↔ 요청 구동으로 바꿀 수 있다. (일반 `@ConditionalOnProperty` 와 달리 AOT 에 굳지 않게 만들었다 — [native-image](native-image.md#켜기끄기는-빌드-때-굳는다-aot).) 환경변수의 `ON_REQUEST` · 설정의 `on-request` 둘 다 받는다.

깨우기 한 번이 하는 일: ① 주기 작업을 깨운다 — `OnRequestTask` 빈(계정 삭제 정리 `account-purge` · 세션 정리 `auth-session-purge`)을 한 번씩 부른다(큐에 넣거나 한 묶음 지운다) ② 밀린 일감을 판마다 한 묶음씩(`batch-size`) 처리하고, 처리한 것이 있고 시간이 남았으면 다음 판 — 비면 끝낸다.
프로젝트 N개 모드([multi-project](multi-project.md))면 판마다 프로젝트를 모두 돈다. 응답은 `{ tasks:[{name,ok}], processed, rounds, timedOut, elapsedMillis }`. `timedOut:true` 면 한도로 멈춘 것이다(남은 일감은 다음 호출이 이어받는다).

- **인증**: 공유 비밀값 헤더(상수 시간 비교). 틀리거나 없으면 401, 서버에 비밀값이 비어 있으면 404 이고 어느 쪽도 일감에 손대지 않는다. **보호 환경(stage · prod)에서 비밀값이 없거나 약하면(24자 미만 · 서로 다른 글자 8개 미만) 기동 실패**한다.
  Cloud Run 을 `--no-allow-unauthenticated` 로 두고 Scheduler 가 서비스 계정 OIDC 로 부르게 하면 플랫폼(IAM)이 한 겹 더 막는다 — 앱 비밀값과 겹쳐 써도 된다(둘 다 맞아야 닿는다).
  OIDC 는 Google ID 토큰을 `Authorization: Bearer` 에 싣는데, **요청 구동 모드에서는 깨우기 경로의 Bearer 를 JWT 필터가 읽지 않는다**(`BearerIgnoredPaths`) — 이 경로의 인증은 `X-Wake-Secret` 하나이고 Bearer 는 무시된다(플랫폼이 이미 검증했다).
  프로젝트 N개 모드에서 프로젝트 없는 경로(서비스 주소)에 오는 다른 Bearer 는 어느 프로젝트의 키로도 검증할 수 없어 **401**(인증 실패)이다 — 500 이 아니다.
- **재시도 대기 중인 일감**(백오프)은 때가 안 되면 집지 않는다 — 다음 호출이 그때 집는다. 그래서 스케줄 간격이 곧 **일감 지연의 상한**이다.
- 핸들러는 지금처럼 **멱등**이어야 한다(깨우기가 겹쳐 불려도 `SKIP LOCKED` 가 나눠 주지만, 시간 한도로 끊긴 호출 · 인스턴스 종료 뒤 재시도가 있다).
- 새 주기 작업을 더하려면 `OnRequestTask` 빈을 등록한다 (`dev.sumin.skeleton.common.wake`). 이미 큐로 도는 작업은 필요 없다.

### Cloud Scheduler 설정 예

```bash
gcloud scheduler jobs create http drain-jobs \
  --location=asia-northeast3 --schedule="* * * * *" --time-zone="Asia/Seoul" \
  --uri="https://<서비스>.run.app/internal/jobs/drain" --http-method=POST \
  --headers="X-Wake-Secret=<비밀값>" \
  --attempt-deadline=60s
# 서비스를 비공개로 두면 --oidc-service-account-email=<호출자 SA> --oidc-token-audience=https://<서비스>.run.app 를 더한다
```

- **주기**: 일감 지연이 허용하는 만큼 — 1분이 흔하다(`* * * * *`). 안 쓸 때는 `*/10 * * * *` 처럼 늘려도 된다. 깨울 때마다 인스턴스가 한 번 뜨므로 간격이 짧을수록 비용 · 콜드스타트가 는다. 일감이 없으면 호출은 DB 한 번 보고 바로 끝난다.
- `--attempt-deadline` 은 `time-budget` 보다 크게(여유 2배). 실패하면 Scheduler 가 재시도한다(응답이 200 이 아니면).
- 비밀값은 Secret Manager 에서 가져와 Scheduler 헤더 · 서비스 환경변수 양쪽에 같은 값으로 넣는다. **저장소에 값을 커밋하지 않는다.**

## 3. Cloud Run 서비스 설정

| 설정 | 권장 | 이유 |
|---|---|---|
| 최소 인스턴스 | **0** | 0대가 목적. 콜드스타트가 문제면 1 로 올리는 대신 네이티브를 쓴다 |
| 최대 인스턴스 | 작게(1~3) | 이 레포는 DB 연결 풀(Hikari) · `FOR UPDATE SKIP LOCKED` 를 쓰므로 인스턴스당 연결 수 × 인스턴스 수가 DB 한도 안이어야 한다 |
| 동시성 | 80(기본) 이하, 작으면 20~40 | 요청 스레드가 많을수록 메모리가 는다. CPU 1 이면 20~40 으로 시작 |
| CPU | 1 이상, **요청 중에만 할당**(기본) | 깨우기 중 일감이 도는 동안은 요청이 열려 있어 CPU 가 있다 |
| 기동 CPU 부스트 | **켠다** (`--cpu-boost`) | JVM 은 기동 때 CPU 를 많이 쓴다. 부스트가 콜드스타트를 줄인다(이 레포의 측정은 부스트 없는 CPU 1 이다) |
| 메모리 | 부하로 정한다 (시작점 512MB) | 위 표는 512MB 제한에서 요청 몇 개만 돌렸다 — 동시 요청 · 힙 크기에 따라 는다. 부하 상태는 재 보지 않았다 |
| 요청 제한 시간 | `time-budget` + 여유 | 깨우기 요청이 이 안에 끝나야 한다 |
| 헬스 | 시작 프로브 `/health` | 앱이 DB 를 못 보면 503 — 시작 프로브 임계값은 기동 시간(JVM 약 7s, 네이티브 약 1s)보다 넉넉히 |

DB 는 인스턴스 밖(Cloud SQL 등)이다. 깨우기가 DB 를 깨우는 비용도 같이 본다. 비밀(`JWT_SECRET` · 암호 키 · 깨우기 비밀값 · 웹훅 조각)은 Secret Manager → 환경변수.

## 4. 앱이 지킬 것

- **인스턴스가 사라져도 되게**: 상태를 메모리에 두지 않는다(캐시는 괜찮지만 유일한 사본이면 안 된다). 응답을 보낸 뒤의 백그라운드 작업은 CPU 가 없을 수 있다 — **큐에 넣고** 깨우기가 처리하게 한다.
- **종료 신호**: Cloud Run 은 SIGTERM 뒤 짧게 기다리고 내린다. 일감 핸들러는 멱등으로 짜서 중간에 끊겨도 다음에 다시 돈다 (`stale-lock-timeout` 뒤 복구).
- **모듈 켜기/끄기는 빌드 때 굳는다**(AOT · 네이티브) — 환경별 구성이 다르면 이미지를 따로 만든다. 값(주소 · 비밀)만 환경변수로 바꾼다.
- 네이티브면 [리플렉션 규칙](native-image.md#리플렉션-규칙--새-코드를-쓸-때)(컨트롤러 밖에서 JSON 으로 바꾸는 타입은 등록 표시) · 새 의존성은 릴리스 전 `scripts/native-test.sh`.
- 로그는 stdout, 포트는 `$PORT`(Cloud Run 이 8080 을 넣는다 — `server.port` 를 정하지 않는다).

## 5. 확인하는 법

`scripts/native-test.sh` 가 이 모양(on-request 모드 + 네이티브)을 그대로 시험한다: 폴링 없이 일감이 쌓이고, 깨우기 주소(비밀값 없음 · 틀림 → 401)를 맞는 비밀값으로 부르면 처리된다. 자세한 단계는 `scripts/native-smoke.sh` 상단.
