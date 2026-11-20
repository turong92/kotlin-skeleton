# deploy/cloudrun — Cloud Run + Cloudflare 바닥

선언 한 장(`deploy/app.yaml`)에서 프로젝트 단위 바닥을 만든다. 서비스와 리비전은 배포 워크플로가 만든다. 사람이 할 순서는 `docs/` 의 Cloud Run 문서를 따른다.

| 만드는 것 | 이름(`N` = 선언의 `name`) |
|---|---|
| Artifact Registry(불변 태그, 최근 10개) | `N` |
| 서비스 계정 · WIF(GitHub OIDC, 앱 레포 한 개만) | `N-run` · `N-deploy` |
| Secret Manager 그릇 + 무작위 값 | 선언 `secrets` · `ORIGIN_AUTH_SECRET` · `SMOKE_*` · 플랫폼 비밀 |
| Cloud Tasks 큐 · Scheduler (`cloudrun.wake` 가 있을 때만) | `N-drain` · `N-tick` |
| Worker(`worker.js`) + 경로 `<domain><api_prefix>/*` | `N-api-proxy` |
| 바깥 감시: 업타임 체크 · 경보 정책 2 · Discord 중계 Worker(`alert-relay.js`) (`cloudrun.monitoring` 이 있을 때만) | `N-alert-relay` · 경로 `<domain>/_alert-relay` |
| Pages(직접 올리기) + 도메인 + DNS | `N-web` |
| R2 + CORS | `N-files` |

선언이 틀리면 plan 이 고칠 칸 이름과 함께 거부한다(`module/variables.tf`). Cloud Run 서비스 주소는 `https://N-api-<프로젝트 번호>.asia-northeast3.run.app` 로 서비스가 생기기 전에도 정해진다.

## 실행

```
cloud prod terraform -chdir=deploy/cloudrun init
cloud prod terraform -chdir=deploy/cloudrun plan
```

- state 버킷(`<project_id>-tfstate`)은 래퍼가 `init` 에 넣는다 — 직접 `-backend-config` 를 주지 않는다.
- 래퍼가 `TF_VAR_project_id` · `TF_VAR_cloudflare_account_id` 를 넣는다. `github_repository` · `cloudflare_zone_id` 는 `terraform.tfvars`(git 밖, 예: `terraform.tfvars.example`).
- Cloudflare 토큰은 환경변수 `CLOUDFLARE_API_TOKEN`.
- 처음 한 번 사람이 하는 일: 프로젝트 · 결제 연결, `serviceusage` · `cloudresourcemanager` API 켜기, state 버킷 만들기.
- 출력의 `github_variables`(kotlin 레포) · `web_github_variables`(react 레포)를 GitHub 레포 변수로 옮긴다.

## 바깥 감시 (선택)

Cloud Run 앞단이 앱에 닿기 전에 내는 503(연결 부족 · 인스턴스 포화)은 앱 안의 `alert` 모듈이 못 본다. 선언에 `cloudrun.monitoring:` 을 넣으면 GCP Cloud Monitoring 이 밖에서 보고 Discord 로 알린다. 칸이 없으면 아무것도 만들지 않는다.

- **켜는 법**: `app.yaml` 의 `cloudrun:` 아래에 `monitoring: {}`(기본값 그대로) 또는 값을 적는다 — `uptime_period_minutes`(1·5·10·15, 기본 5) · `error_threshold`(기본 5건) · `error_window_minutes`(기본 5분). 그리고 `terraform.tfvars` 에 `discord_webhook_url`(sensitive, 값은 사람이 넣는다)을 적고 `apply`.
- **만드는 것**: 업타임 체크 1(공개 `https://<domain><api_prefix>/health` — `worker.js` 가 그 한 경로만 원점 `/health` 로 다시 써서 넘긴다, 비밀은 업타임 설정에 없다), 경보 정책 2(업타임 실패 · Cloud Run `request_count` 5xx), 알림 채널 1(webhook_basicauth, 비밀번호는 `random_password`) → 중계 Worker(`Authorization: Basic` 이 틀리면 401, Discord 에 한국어 한 줄).
- **비용 메모**: 업타임 체크 1개(5분 간격)와 알림 채널은 GCP 무료 한도 안의 크기로 보이나(정확한 한도 · 가격은 GCP 문서에서 확인), 중계 Worker 는 경보 때만 불려 Workers 무료 한도로 충분하다.
- **인스턴스를 깨운다**: 업타임 체크가 `/health`(DB 까지 확인)를 여러 지점에서 계속 불러 0대 축소와 외부 DB 오토서스펜드를 사실상 막는다. 간격이 길수록 덜 깨운다.
- **DB 연결 수**는 외부 DB(PlanetScale 등)라 GCP 지표가 없다 — 그쪽 대시보드 지표로 따로 본다.

## 시험 (실계정 없이)

```
node --test deploy/cloudrun/worker.test.mjs deploy/cloudrun/alert-relay.test.mjs
terraform -chdir=deploy/cloudrun/module init -backend=false
terraform -chdir=deploy/cloudrun/module test
```
