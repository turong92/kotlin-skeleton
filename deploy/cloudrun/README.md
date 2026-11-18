# deploy/cloudrun — Cloud Run + Cloudflare 바닥

선언 한 장(`deploy/app.yaml`)에서 프로젝트 단위 바닥을 만든다. 서비스와 리비전은 배포 워크플로가 만든다. 사람이 할 순서는 `docs/` 의 Cloud Run 문서를 따른다.

| 만드는 것 | 이름(`N` = 선언의 `name`) |
|---|---|
| Artifact Registry(불변 태그, 최근 10개) | `N` |
| 서비스 계정 · WIF(GitHub OIDC, 앱 레포 한 개만) | `N-run` · `N-deploy` |
| Secret Manager 그릇 + 무작위 값 | 선언 `secrets` · `ORIGIN_AUTH_SECRET` · `SMOKE_*` · 플랫폼 비밀 |
| Cloud Tasks 큐 · Scheduler (`cloudrun.wake` 가 있을 때만) | `N-drain` · `N-tick` |
| Worker(`worker.js`) + 경로 `<domain><api_prefix>/*` | `N-api-proxy` |
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

## 시험 (실계정 없이)

```
node --test deploy/cloudrun/worker.test.mjs
terraform -chdir=deploy/cloudrun/module init -backend=false
terraform -chdir=deploy/cloudrun/module test
```
