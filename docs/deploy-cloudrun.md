# Cloud Run + Cloudflare 에 올리기

홈서버 대신 GCP Cloud Run(서울, 최소 0 · 최대 1, 네이티브)과 Cloudflare(Pages · Worker · R2)에 올리는 길이다. 홈서버 계약은 `docs/deploy.md`, 0대 운영의 앱 규칙은 `docs/cloud-run-scale-to-zero.md`.
**선언 한 장(`deploy/app.yaml`) + `v*` 태그 푸시**가 전부다. 바닥(저장소 · 계정 · 비밀 · 큐 · Worker · Pages · R2)은 `deploy/cloudrun/` 의 terraform 이 한 번 만들고, 서비스와 리비전은 `.github/workflows/deploy-cloudrun.yml` 이 만든다.

| 누가 | 무엇 |
|---|---|
| 사람(한 번) | GCP 프로젝트 · 결제 · Cloudflare 영역 · state 버킷 · DB 역할 · 운영자 비밀 값 |
| terraform (`deploy/cloudrun/`) | Artifact Registry · 서비스 계정 · WIF · 비밀 그릇 · Tasks 큐 · Scheduler · Worker · Pages · R2 — 설명은 `deploy/cloudrun/README.md` |
| 워크플로 (`v*` 태그) | 선언을 `deploy/cloudrun/render.sh` 로 플래그 · env 파일로 바꿔 이미지를 빌드 · 후보 리비전 배포 · `scripts/post-deploy-check.sh` 통과 뒤 트래픽 100% |

## 1. 사람이 할 순서

1. **GCP 프로젝트**를 만들고(앱 하나 = 프로젝트 하나) 결제 계정을 연결한다. `serviceusage` · `cloudresourcemanager` API 를 켠다(나머지 API 는 terraform 이 켠다).
2. **Cloudflare**: 도메인이 든 영역(zone)을 준비하고, 계정 ID · 영역 ID 를 적어 둔다. 토큰은 환경변수 `CLOUDFLARE_API_TOKEN`(Workers · Pages · R2 · DNS 편집).
3. **state 버킷** `<project_id>-tfstate` 를 만든다(버전 켬, 소유자만 접근). 무작위 비밀 값이 state 에 남으므로 접근을 좁힌다.
4. **DB — PlanetScale**(공용 클러스터 하나, 앱마다 DB 와 역할 하나). 대시보드에서 역할을 만들고 `psql`(관리 역할로 접속)로 DB 를 가른다:
   ```sql
   CREATE DATABASE acme;
   REVOKE CONNECT ON DATABASE acme FROM PUBLIC;       -- 다른 앱의 역할이 이 DB 에 못 붙게
   GRANT CONNECT, CREATE ON DATABASE acme TO acme;
   \c acme
   GRANT CREATE, USAGE ON SCHEMA public TO acme;      -- public 스키마 권한
   ```
   접속은 공개 주소 + TLS 다: `jdbc:postgresql://<호스트>:5432/acme?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`. 풀러 없이 직결한다. `verify-full` 만 쓰면 pgjdbc 가 `~/.postgresql/root.crt` 를 찾다 실패하므로, JDK 의 신뢰 저장소를 쓰는 `DefaultJavaSSLFactory` 를 같이 붙인다.
5. **선언을 채운다**(`deploy/app.yaml`): `domain` · `api_prefix` 를 켜고, `db: external`, `redis: false`, `secrets` + `operator_only`(`SPRING_DATASOURCE_URL` · `_USERNAME` · `_PASSWORD` 를 둘 다에), 주석 `cloudrun:` 블록을 풀어 값을 적는다. **이 칸들을 채우기 전에는 루트 `terraform validate`/`plan` 이 칸 이름과 함께 거부한다 — 정상이다**(스켈레톤 원본 선언은 홈서버용이다). **앱에 깨우기 모듈을 넣는다**: 새 프로젝트면 `--modules job-queue-wake-cloudtasks`, 기존 앱이면 `implementation(project(":modules:job-queue-wake-cloudtasks"))`. 모듈이 없으면 Tasks 예약이 일어나지 않고 **조용히 Scheduler 주기로만** 돈다(일을 넣어도 다음 주기까지 기다린다). `env:` 에는 비밀을 쓰지 않는다 — 네이티브 이미지의 빌드 인자라 이미지 메타데이터에 남는다(비밀은 `secrets:`).
6. `deploy/cloudrun/terraform.tfvars`(git 밖, 예: `terraform.tfvars.example`)를 쓰고 맥에서 돌린다:
   ```bash
   cloud prod terraform -chdir=deploy/cloudrun init
   cloud prod terraform -chdir=deploy/cloudrun apply
   ```
   바로 다시 돌린 plan 이 비어 있어야 한다.
7. **운영자 비밀 값**을 넣는다(`operator_only` 마다 한 번). 넣기 전에 배포하면 리비전 생성이 실패하고 그 이름이 보인다:
   ```bash
   printf '%s' '<값>' | cloud prod gcloud secrets versions add SPRING_DATASOURCE_URL --data-file=-
   ```
   무작위여도 되는 비밀(`JWT_SECRET` 등)과 플랫폼 비밀(`ORIGIN_AUTH_SECRET` · `<P>_WEB_CLIENT_IP_EDGE_SECRET_SHA256` · `<P>_JOB_QUEUE_WAKE_SECRET`)은 terraform 이 넣었다.
8. **레포 변수 옮기기**: `terraform output -json github_variables` 의 열 칸을 이 앱 레포의 GitHub 변수로, react 레포에는 `web_github_variables`(`PAGES_PROJECT`)를 옮긴다. react 쪽은 Pages 편집 권한만 있는 Cloudflare 토큰을 GitHub 비밀로 둔다(react-skeleton `docs/new-project-recipe.md`).
9. **첫 태그**: 서버 먼저, 웹 나중이다. `git tag v0.1.0 && git push origin v0.1.0`.
   - 첫 배포는 새 서비스라 `SMOKE_EMAIL` · `SMOKE_PASSWORD` 판이 아직 없다 — 워크플로가 **로그인 확인을 건너뛰고** `/health` 200 과 원점 비밀 없는 호출 403 만 본다(로그에 그렇게 한 줄 남는다).
   - 서비스가 뜬 뒤 시험 계정을 가입시키고 두 값을 넣는다(`cloud prod gcloud secrets versions add SMOKE_EMAIL --data-file=-` · `SMOKE_PASSWORD`). 이후 배포에서 판이 없으면 워크플로가 실패한다.

## 2. 원점 비밀 — run.app 은 공개 주소다

Worker 가 `X-Origin-Auth` 를 붙이고, 앱(`client-ip.mode=edge-secret`, `docs/client-ip.md`)은 그 헤더의 SHA-256 이 `<P>_WEB_CLIENT_IP_EDGE_SECRET_SHA256` 과 맞는 요청만 받는다. 헤더 없이 run.app 을 직접 부르면 403 이다(drain 경로만 예외 — 자기 비밀 `X-Wake-Secret` 이 막는다). Worker 는 `api_prefix` 아래만 넘기므로 `/internal/*` 은 Worker 를 거쳐 닿지 않는다.
**회전은 아직 틀이 없다.** Worker 의 비밀과 앱의 해시는 terraform(`random_password`)이 만든 값이라, 손으로 한쪽만 바꾸면 다음 `apply` 가 되돌려 Worker 와 앱이 어긋나고 모든 API 호출이 403 이 된다. 지금은 바꾸지 않는다 — 필요해지면 두 해시를 받는 앱 기능(`edge-secret-sha256` 에 쉼표로 둘)을 쓰는 순서를 terraform 쪽에 먼저 만든다.

## 3. 깨우기 — Cloud Tasks + Scheduler

선언에 `cloudrun.wake` 가 있으면 큐 `<name>-drain` 과 Scheduler `<name>-tick` 이 생기고 작업 큐가 요청 구동(`on-request`)이 된다. 일을 넣은 직후 Tasks 가 그 시각에 drain 하나를 예약하고, Scheduler 는 주기 일과 그물이다. 앱 쪽 규칙은 `docs/cloud-run-scale-to-zero.md`, 공급자는 `modules/job-queue-wake-cloudtasks`.
**깨우기 비밀은 태스크 정의에 남는다.** 태스크가 `X-Wake-Secret` 을 헤더로 싣고 Tasks 가 그 정의를 보관하므로, 큐를 볼 수 있는 사람(`cloudtasks.viewer` 등)은 비밀을 읽는다 — 그 역할을 따로 주지 않고 프로젝트 소유자로 묶는다. 실행 계정에는 그 큐의 `cloudtasks.enqueuer` 만 있다.
**drain 경로는 `/internal/jobs/drain` 으로 고정이다.** terraform 의 Scheduler 대상이 이 경로로 정해지므로, 앱이 `wake.path` 를 바꾸면 Scheduler 가 엉뚱한 주소를 부른다 — 바꾸지 않는다.

## 4. 비용 주의

- 고정비는 PlanetScale 하나여야 한다. IP 허용 목록은 NAT(고정비)가 필요해 쓰지 않는다. Cloud Run 은 요청 기반이라 놀 때 0원이고 `max_instances: 1` 이 상한이다.
- Scheduler 무료 3개는 **결제 계정 전체** 기준이다. Cloud Tasks 는 월 100만 건까지 무료다.
- **Workers 무료는 하루 10만 요청이다.** API 요청은 모두 Worker 를 지난다. 앱마다 요청 빈도(잦은 PUT · 폴링 · 조회)를 어림해 적고, **하루 7만을 넘을 것 같으면** Workers 유료($5/월)로 바꾼다. 넘으면 무료 플랜은 오류로 답해 API 가 선다.
- 따로 세는 것: Secret Manager 판 수(판 하나에 월 $0.06, 6개까지 무료 — 안 쓰는 모듈의 비밀 줄은 지운다) · 송신(Cloud Run → R2 · 인터넷, GiB 당 $0.12~0.19 — ffmpeg 결과물도 포함) · AR 저장 용량(최근 10개만 남긴다).
- **ffmpeg**(최대 60초 영상)를 쓰는 앱: 선언의 `cloudrun.build_args` 에 `FFMPEG: "1"`(`Dockerfile.native` 가 실행 이미지에 ffmpeg 를 넣는다), `cloudrun.memory` 는 `1Gi` 이상(`/tmp` 가 메모리에서 빠진다), 앱 yml 의 `skeleton.job-queue.batch-size: 1`(예산을 한 건마다 본다). `ffmpeg.timeout` < `time-budget` < `cloudrun.timeout` 순서를 지킨다.
- SSE · 웹소켓은 연결이 인스턴스를 붙잡아 과금된다 — 최소 0 앱에서는 끈다.
- DB 연결 수: 앱 수 × (최대 1 + 배포 중 남는 옛 리비전 1) × 풀 크기가 클러스터 한도 안이어야 한다.

## 5. 롤백

- API: `gcloud run services update-traffic <name>-api --to-revisions <이전 리비전>=100`. 마이그레이션은 더하기만이라(`docs/schema-management.md`) 옛 리비전이 그대로 돈다.
- 웹: Cloudflare Pages 의 이전 배포로 되돌린다.
- 비밀: 새 판을 사용 중지하고 다시 배포한다(Cloud Run 은 `:latest` 를 참조한다).
- 0대 운영을 그만둘 때: 같은 이미지 · 같은 선언으로 홈서버에 올리고(`docs/deploy.md`) Scheduler 를 멈추고 Tasks 큐를 비운다.

## 6. 리허설 결과

실계정 리허설에서 아래가 전부 통과해야 끝이다. 아직 돌리지 않았다.

| # | 확인 | 결과 |
|---|---|---|
| 1 | `terraform apply` 한 번에 바닥이 서고 바로 다시 돌린 plan 이 비어 있다 | |
| 2 | `v0.1.0` 태그 → 워크플로 초록, post-deploy-check 통과 뒤 트래픽 100% | |
| 3 | 깊은 웹 주소 새로고침은 Pages 에서 뜨고 `/api/v1/…` 은 API 로 간다(쿠키 로그인 유지) | |
| 4 | 바깥 콜백(AdMob SSV · 보상 지급 · 소셜 로그인 · 결제 웹훅)이 `/api/v1` → Worker → 원점으로 닿는다 | |
| 5 | 헤더 없이 run.app 직접 403, `/internal/…` 은 Worker 404, 비밀 없는 drain 은 401 | |
| 6 | 앱이 보는 클라이언트 IP 가 실제 IP 다 | |
| 7 | 인스턴스 0대에서 일을 넣으면 Tasks 가 수 초 안에 깨워 처리하고, 큐를 멈춰도 Scheduler 주기 안에 처리된다 | |
| 8 | 60초 영상 ffmpeg 변환이 1Gi 에서 OOM 없이 drain 안에서 끝나고 그동안 다른 drain 은 `busy` | |
| 9 | 20분 비운 뒤 인스턴스 0대, 첫 요청 콜드스타트(초) | |
| 10 | `v0.1.1` 배포 뒤 이전 리비전으로 되돌리기 | |
| 11 | 두 번째 앱이 같은 클러스터의 다른 DB · 역할로 뜨고 서로의 DB 에 CONNECT 가 거부된다 | |
| 12 | 두 스켈레톤 변경분에 실제 도메인 · 프로젝트 ID · 계정 문자열이 없다(grep) | |
| 13 | DB 가 `sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` 로 첫 기동에서 붙는다(`root.crt` 를 못 찾는 오류가 없다) | |
