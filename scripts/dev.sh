#!/usr/bin/env bash
# 로컬 풀스택 한 줄 실행 — DB(+ 로컬 S3)를 올리고 백엔드를 띄운다. 옆에 프론트가 있으면 같이 띄우고, 끝나면(Ctrl-C) 프론트도 내린다.
#
#   scripts/dev.sh            # 컨테이너(DB · S3) → (프론트) → ./gradlew :apps:api:bootRun --spring.profiles.active=local
#   scripts/dev.sh down       # 컨테이너를 내린다 (데이터 볼륨은 남는다. 지우려면 docker compose --profile s3 down -v)
#
# 프론트는 이 레포와 나란히 둔다: <작업 폴더>/api (이 레포) 와 <작업 폴더>/web (react-skeleton 에서 찍은 것).
# 다른 위치면 WEB_DIR=../my-web scripts/dev.sh, 프론트 없이 백엔드만이면 WEB_DIR= scripts/dev.sh.
# 프론트는 Vite(5173)가 /api/v1 을 localhost:8080 으로 넘긴다 — CORS 설정이 필요 없다.
set -euo pipefail
cd "$(dirname "$0")/.."

# 모듈이 있으면 그 인프라를 올린다 (고른 모듈이 정한다 — new-project.sh 로 찍은 프로젝트에서도 그대로 맞다)
DB=postgres
[ -d modules/db-mysql ] && DB=mysql
WITH_S3=0
[ -d modules/storage-s3 ] && WITH_S3=1

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
  (cd "$WEB_DIR" && exec pnpm dev) &
  WEB_PID=$!
else
  echo "== 프론트 없음 ($WEB_DIR) — 백엔드만 띄운다"
fi

echo "== 백엔드: http://localhost:8080  (curl localhost:8080/api/v1/hello)"
./gradlew :apps:api:bootRun --args='--spring.profiles.active=local'
