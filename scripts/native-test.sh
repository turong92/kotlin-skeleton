#!/usr/bin/env bash
# 네이티브 시험 — 앱을 네이티브로 빌드하고(scripts/native-build.sh), 새 데이터베이스와 함께 컨테이너에서 띄워, 흐름 시험 스크립트를 돌린다 (docs/native-image.md).
#   scripts/native-test.sh [--app <앱 디렉토리 이름>] [--smoke <시험 스크립트>] [--skip-build]
#     --app    기본: 이 레포(스켈레톤)에는 tools/native-check, 새 프로젝트에는 api. 앱은 apps/<이름> 또는 tools/<이름>
#     --smoke  띄운 서버 주소를 `--url <주소>` 로 받는 스크립트 (종료 코드 0 = 통과). 기본: scripts/native-smoke.sh 가 있으면 그것(스켈레톤의 핵심 흐름),
#              없으면 내장 최소 확인(/health 200 + OpenAPI 문서 200). **프로젝트는 자기 핵심 흐름(가입 · 결제 …)을 이 틀로 직접 쓴다** —
#              scripts/native-smoke.sh 의 함수 틀(ok · bad · expect · call)을 복사해 쓰면 된다 (스켈레톤 레포에 있다)
# 필요한 것: docker · curl · python3. 호스트 Postgres 는 compose 의 postgres 를 전용 프로젝트로 올린다.
# 자기 개발 컨테이너(kotlin-skeleton)와 섞이지 않게 전용 compose 프로젝트(kotlin-skeleton-native-test)의 Postgres 를 따로 올리고 끝나면 지운다 — 개발 DB 는 건드리지 않는다.
# 환경변수: DB_PORT(전용 Postgres 의 호스트 포트, 기본 15432) · NATIVE_TEST_PORT(기본 18110) · NATIVE_COMPOSE_PROJECT
#           · NATIVE_TEST_ENV(앱에 넘길 환경변수, 공백으로 구분한 KEY=VALUE — 비밀·주소 등) · NATIVE_IMAGE_BUILDER(실행에도 쓴다: glibc 가 있는 이미지) · NATIVE_PLATFORM · (빌드용 변수는 native-build.sh 주석)
set -euo pipefail
cd "$(dirname "$0")/.."

SKIP=0; APP=""; SMOKE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP=1; shift ;;
    --app) APP="$2"; shift 2 ;;
    --smoke) SMOKE="$2"; shift 2 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
if [ -z "$APP" ]; then if [ -d tools/native-check ]; then APP=native-check; else APP=api; fi; fi
DIR="apps/$APP"; [ -d "$DIR" ] || DIR="tools/$APP"
[ -d "$DIR" ] || { echo "앱 디렉토리가 없다: apps/$APP · tools/$APP" >&2; exit 2; }
if [ -z "$SMOKE" ] && [ -x scripts/native-smoke.sh ]; then SMOKE=scripts/native-smoke.sh; fi

IMAGE="${NATIVE_IMAGE_BUILDER:-ghcr.io/graalvm/native-image-community:25}"
DB=native_smoke; PORT="${NATIVE_TEST_PORT:-18110}"; NAME="native-test-$$"
BIN_DIR="$PWD/$DIR/build/native/nativeCompile"
DB_USER=app; DB_PASSWORD=dev   # docker-compose.yml 의 postgres 서비스 값
export COMPOSE_PROJECT_NAME="${NATIVE_COMPOSE_PROJECT:-kotlin-skeleton-native-test}" DB_PORT="${DB_PORT:-15432}"

[ "$SKIP" = 1 ] || scripts/native-build.sh --app "$APP"
[ -x "$BIN_DIR/app" ] || { echo "✗ $BIN_DIR/app 이 없다 — --skip-build 를 빼고 다시" >&2; exit 1; }

docker compose up -d --wait postgres
PSQL=(docker compose exec -T postgres psql -U "$DB_USER" -d app -v ON_ERROR_STOP=1)
"${PSQL[@]}" -c "drop database if exists $DB" -c "create database $DB" >/dev/null
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; docker compose down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

PLATFORM=(); [ -z "${NATIVE_PLATFORM:-}" ] || PLATFORM=(--platform "$NATIVE_PLATFORM")
EXTRA=(); for kv in ${NATIVE_TEST_ENV:-}; do EXTRA+=(-e "$kv"); done
docker run -d --name "$NAME" "${PLATFORM[@]}" --add-host host.docker.internal:host-gateway --cpus 1 --memory 512m -p "$PORT:8080" -v "$BIN_DIR:/app:ro" \
  -e "SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:$DB_PORT/$DB" -e "SPRING_DATASOURCE_USERNAME=$DB_USER" -e "SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD" \
  ${EXTRA[@]+"${EXTRA[@]}"} --entrypoint /app/app "$IMAGE" >/dev/null

URL="http://localhost:$PORT"; rc=0
if [ -n "$SMOKE" ]; then
  "$SMOKE" --url "$URL" || rc=$?
else
  echo "== 내장 최소 확인 (프로젝트 자기 흐름 시험은 --smoke <스크립트>)"
  up=0; for _ in $(seq 1 60); do [ "$(curl -s -o /dev/null -w '%{http_code}' "$URL/health")" = 200 ] && { up=1; break; }; sleep 1; done
  [ "$up" = 1 ] && echo "  ✓ /health 200" || { echo "  ✗ /health 가 200 이 아니다" >&2; rc=1; }
  if [ "$rc" = 0 ]; then
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$URL/api/v1/docs")" = 200 ] && echo "  ✓ OpenAPI 문서 200" || { echo "  ✗ /api/v1/docs 가 200 이 아니다" >&2; rc=1; }
  fi
fi
if [ "$rc" != 0 ]; then echo "---- 컨테이너 로그(끝 60줄)" >&2; docker logs "$NAME" 2>&1 | tail -60 >&2; fi
exit "$rc"
