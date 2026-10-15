#!/usr/bin/env bash
# 네이티브 시험 — 앱을 네이티브로 빌드하고(scripts/native-build.sh), 새 데이터베이스와 함께 컨테이너에서 띄워, 흐름 시험 스크립트를 돌린다 (docs/native-image.md).
#   scripts/native-test.sh [--app <앱 디렉토리 이름>] [--smoke <시험 스크립트>] [--full] [--skip-build]
#     --app    기본: 이 레포(스켈레톤)에는 tools/native-check, 새 프로젝트에는 api. 앱은 apps/<이름> 또는 tools/<이름>
#     --smoke  띄운 서버 주소를 `--url <주소>` 로 받는 스크립트 (종료 코드 0 = 통과). 기본: scripts/native-smoke.sh 가 있으면 그것(스켈레톤의 핵심 흐름),
#              없으면 내장 최소 확인(/health 200 + OpenAPI 문서 200). **프로젝트는 자기 핵심 흐름(가입 · 결제 …)을 이 틀로 직접 쓴다** —
#              scripts/native-smoke.sh 의 함수 틀(ok · bad · expect · call)을 복사해 쓰면 된다 (스켈레톤 레포에 있다)
#     --full   (이 레포의 tools/native-check 전용) 기본 흐름에 더해 scripts/native-smoke-full.sh 를 돌린다 — 메일(비밀번호 재설정) · 파일 저장(로컬 S3) · 소셜 로그인 5종 · 토스 · Paddle · SSE.
#              가짜 상대(scripts/native-fakes.py)와 로컬 S3(SeaweedFS)를 함께 띄운다. **릴리스 전**에 돌린다 (평소 기본 시험은 --full 없이)
# 기본 흐름도 메일은 실제 SMTP 로 나가 mailpit 이 받는다(compose 의 mail 프로필) — 가입 확인 코드를 거기서 읽는다.
# 필요한 것: docker · curl · python3. 호스트 Postgres 는 compose 의 postgres 를 전용 프로젝트로 올린다.
# 자기 개발 컨테이너(kotlin-skeleton)와 섞이지 않게 전용 compose 프로젝트(kotlin-skeleton-native-test)의 Postgres 를 따로 올리고 끝나면 지운다 — 개발 DB 는 건드리지 않는다.
# 환경변수: DB_PORT(전용 Postgres 의 호스트 포트, 기본 15432) · NATIVE_TEST_PORT(기본 18110) · NATIVE_COMPOSE_PROJECT
#           · NATIVE_TEST_ENV(앱에 넘길 환경변수, 공백으로 구분한 KEY=VALUE — 비밀·주소 등) · NATIVE_IMAGE_BUILDER(실행에도 쓴다: glibc 가 있는 이미지) · NATIVE_PLATFORM · (빌드용 변수는 native-build.sh 주석)
set -euo pipefail
cd "$(dirname "$0")/.."

SKIP=0; APP=""; SMOKE=""; FULL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP=1; shift ;;
    --app) APP="$2"; shift 2 ;;
    --smoke) SMOKE="$2"; shift 2 ;;
    --full) FULL=1; shift ;;
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
export COMPOSE_PROJECT_NAME="${NATIVE_COMPOSE_PROJECT:-kotlin-skeleton-native-test}" DB_PORT="${DB_PORT:-15432}" MAIL_SMTP_PORT="${MAIL_SMTP_PORT:-11025}" MAIL_HTTP_PORT="${MAIL_HTTP_PORT:-18025}" S3_PORT="${S3_PORT:-18333}"
FAKE_PORT="${NATIVE_FAKE_PORT:-19000}"; FAKE_PID=""

[ "$SKIP" = 1 ] || scripts/native-build.sh --app "$APP"
[ -x "$BIN_DIR/app" ] || { echo "✗ $BIN_DIR/app 이 없다 — --skip-build 를 빼고 다시" >&2; exit 1; }

if [ "$APP" = native-check ]; then
  PROFILES=(--profile mail); [ "$FULL" = 0 ] || PROFILES+=(--profile s3)
  docker compose "${PROFILES[@]}" up -d --wait postgres mail
  [ "$FULL" = 0 ] || { docker compose --profile s3 up -d s3; docker compose --profile s3 run --rm s3-init >/dev/null; }
else
  docker compose up -d --wait postgres
fi
PSQL=(docker compose exec -T postgres psql -U "$DB_USER" -d app -v ON_ERROR_STOP=1)
"${PSQL[@]}" -c "drop database if exists $DB" -c "create database $DB" >/dev/null
cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  [ -z "$FAKE_PID" ] || docker rm -f "$FAKE_PID" >/dev/null 2>&1 || true
  docker compose --profile mail --profile s3 down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

PLATFORM=(); [ -z "${NATIVE_PLATFORM:-}" ] || PLATFORM=(--platform "$NATIVE_PLATFORM")
EXTRA=(); for kv in ${NATIVE_TEST_ENV:-}; do EXTRA+=(-e "$kv"); done
NETWORK=(-p "$PORT:8080")
if [ "$APP" = native-check ]; then
  EXTRA+=(-e "NATIVE_MAIL_HOST=host.docker.internal" -e "NATIVE_MAIL_PORT=$MAIL_SMTP_PORT" -e "NATIVE_S3_URL=http://host.docker.internal:$S3_PORT" -e "NATIVE_FAKE_URL=http://localhost:$FAKE_PORT")
fi
if [ "$FULL" = 1 ]; then
  # 가짜 상대 서버는 앱과 **같은 네트워크 이름공간**에서 띄운다: OIDC 모듈은 http 엔드포인트를 localhost 에만 허용한다 — 앱이 가짜를 localhost 로 보게. (python 이미지 하나가 필요하다)
  FAKE_NAME="native-fakes-$$"
  docker run -d --name "$FAKE_NAME" --add-host host.docker.internal:host-gateway -p "$PORT:8080" -p "$FAKE_PORT:$FAKE_PORT" \
    -v "$PWD/scripts/native-fakes.py:/native-fakes.py:ro" "${FAKE_IMAGE:-python:3-slim}" python /native-fakes.py --port "$FAKE_PORT" --base "http://localhost:$FAKE_PORT" --app-base http://localhost:8080 >/dev/null
  FAKE_PID="$FAKE_NAME"
  NETWORK=(--network "container:$FAKE_NAME")
fi
docker run -d --name "$NAME" "${PLATFORM[@]}" $( [ "$FULL" = 1 ] || echo --add-host host.docker.internal:host-gateway ) --cpus 1 --memory 512m "${NETWORK[@]}" -v "$BIN_DIR:/app:ro" \
  -e "SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:$DB_PORT/$DB" -e "SPRING_DATASOURCE_USERNAME=$DB_USER" -e "SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD" \
  ${EXTRA[@]+"${EXTRA[@]}"} --entrypoint /app/app "$IMAGE" >/dev/null

URL="http://localhost:$PORT"; rc=0
if [ -n "$SMOKE" ]; then
  if [ "$APP" = native-check ]; then "$SMOKE" --url "$URL" --mail "http://localhost:$MAIL_HTTP_PORT" || rc=$?; else "$SMOKE" --url "$URL" || rc=$?; fi
  if [ "$rc" = 0 ] && [ "$FULL" = 1 ]; then
    scripts/native-smoke-full.sh --url "$URL" --mail "http://localhost:$MAIL_HTTP_PORT" --fakes "http://localhost:$FAKE_PORT" --s3-host-port "localhost:$S3_PORT" || rc=$?
  fi
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
