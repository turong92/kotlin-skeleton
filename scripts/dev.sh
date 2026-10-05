#!/usr/bin/env bash
# 로컬 풀스택 한 줄 실행 — DB(+ 로컬 S3)를 올리고 백엔드를 띄운다. 옆에 프론트가 있으면 같이 띄우고, 끝나면(Ctrl-C) 프론트도 내린다.
#
#   scripts/dev.sh            # 컨테이너(DB · S3) → (프론트) → ./gradlew :apps:api:bootRun --spring.profiles.active=local
#   APP=sample scripts/dev.sh # 같은 흐름으로 샘플 앱(apps/sample, docs/sample.md) — 짝 프론트까지 한 번에는 scripts/dev-sample.sh
#   scripts/dev.sh down       # 컨테이너를 내린다 (데이터 볼륨은 남는다. 지우려면 docker compose --profile s3 down -v)
#
# 프론트는 이 레포와 나란히 둔다: <작업 폴더>/api (이 레포) 와 <작업 폴더>/web (react-skeleton 에서 찍은 것).
# 다른 위치면 WEB_DIR=../my-web scripts/dev.sh, 프론트 없이 백엔드만이면 WEB_DIR= scripts/dev.sh.
# 프론트는 Vite(5173)가 /api/v1 을 localhost:8080 으로 넘긴다 — CORS 설정이 필요 없다.
# 포트가 겹치면 환경변수로 바꾼다: SERVER_PORT(백엔드 8080) · DB_PORT(5432) · S3_PORT(8333) — compose 와 샘플 앱 yml 이 같은 이름을 읽는다
#   (스타터 apps/api 의 yml 은 DB 주소가 5432 고정이라 DB_PORT 는 샘플 앱용이다). 프론트 인자는 WEB_DEV_ARGS="--port 5199".
set -euo pipefail
cd "$(dirname "$0")/.."

APP="${APP:-api}"
[ -d "apps/$APP" ] || { echo "apps/$APP 가 없다 (APP=api|sample|workbench)" >&2; exit 2; }

# 앱이 쓰는 모듈이 그 인프라를 정한다 — 앱의 build.gradle.kts 에 어떤 모듈이 적혔는가 (스켈레톤 레포는 두 DB 모듈이 다 있어 폴더로는 알 수 없다.
# new-project.sh 로 찍은 프로젝트에서도 그대로 맞다)
APP_BUILD="apps/$APP/build.gradle.kts"
DB=postgres
uses() { grep -Eq "^[[:space:]]*(implementation|api)\(project\(\":modules:$1\"\)\)" "$APP_BUILD"; }   # 주석에 적힌 이름은 세지 않는다
uses db-mysql && DB=mysql
WITH_S3=0
uses storage-s3 && WITH_S3=1

# 시험용: 무엇을 올릴지만 찍고 끝낸다 (scripts/test-new-project.sh 가 쓴다)
[ -z "${DEV_DRY_RUN:-}" ] || { echo "infra: $DB s3=$WITH_S3 app=$APP"; exit 0; }

if [ "${1:-}" = down ]; then
  docker compose --profile s3 down
  exit 0
fi

echo "== 컨테이너: $DB$([ "$WITH_S3" = 1 ] && echo ' + s3 (storage-s3)')"
docker compose up -d --wait "$DB"
if [ "$WITH_S3" = 1 ]; then
  docker compose --profile s3 up -d s3
  docker compose --profile s3 run --rm s3-init      # 버킷 app + CORS (이미 있으면 그대로)
fi

WEB_DIR="${WEB_DIR-../web}"
WEB_PID=""
cleanup() { [ -z "$WEB_PID" ] || kill "$WEB_PID" 2>/dev/null || true; }
trap cleanup EXIT INT TERM
if [ -n "$WEB_DIR" ] && [ -f "$WEB_DIR/package.json" ]; then
  echo "== 프론트: $WEB_DIR → http://localhost:5173"
  (cd "$WEB_DIR" && exec pnpm dev ${WEB_DEV_ARGS:-}) &
  WEB_PID=$!
else
  echo "== 프론트 없음 ($WEB_DIR) — 백엔드만 띄운다"
fi

echo "== 백엔드($APP): http://localhost:${SERVER_PORT:-8080}"
./gradlew ":apps:$APP:bootRun" --args='--spring.profiles.active=local'
