#!/usr/bin/env bash
# 네이티브 시험 — tools/native-check 를 네이티브로 빌드하고(scripts/native-build.sh), 컨테이너에서 띄워 scripts/native-smoke.sh 의 핵심 흐름을 돌린다 (docs/native-image.md).
#   scripts/native-test.sh [--skip-build]
# 필요한 것: docker · 호스트 Postgres(compose 의 postgres 를 올린다) · curl · python3.
# 자기 개발 컨테이너(kotlin-skeleton)와 섞이지 않게 전용 compose 프로젝트(kotlin-skeleton-native-test)의 Postgres 를 따로 올리고 끝나면 지운다 — 개발 DB 는 건드리지 않는다.
# 환경변수: DB_PORT(전용 Postgres 의 호스트 포트, 기본 15432) · NATIVE_TEST_PORT(기본 18110) · NATIVE_COMPOSE_PROJECT
#           · NATIVE_IMAGE_BUILDER(실행에도 쓴다: glibc 가 있는 이미지) · NATIVE_PLATFORM · (빌드용 변수는 native-build.sh 주석)
set -euo pipefail
cd "$(dirname "$0")/.."

SKIP=0; [ "${1:-}" != "--skip-build" ] || SKIP=1
IMAGE="${NATIVE_IMAGE_BUILDER:-ghcr.io/graalvm/native-image-community:25}"
DB=native_smoke; PORT="${NATIVE_TEST_PORT:-18110}"; NAME="native-check-$$"
BIN_DIR="$PWD/tools/native-check/build/native/nativeCompile"
DB_USER=app; DB_PASSWORD=dev   # docker-compose.yml 의 postgres 서비스 값
export COMPOSE_PROJECT_NAME="${NATIVE_COMPOSE_PROJECT:-kotlin-skeleton-native-test}" DB_PORT="${DB_PORT:-15432}"

[ "$SKIP" = 1 ] || scripts/native-build.sh --app native-check
[ -x "$BIN_DIR/app" ] || { echo "✗ $BIN_DIR/app 이 없다 — --skip-build 를 빼고 다시" >&2; exit 1; }

docker compose up -d --wait postgres
PSQL=(docker compose exec -T postgres psql -U "$DB_USER" -d app -v ON_ERROR_STOP=1)
"${PSQL[@]}" -c "drop database if exists $DB" -c "create database $DB" >/dev/null
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; docker compose down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

PLATFORM=(); [ -z "${NATIVE_PLATFORM:-}" ] || PLATFORM=(--platform "$NATIVE_PLATFORM")
docker run -d --name "$NAME" "${PLATFORM[@]}" --add-host host.docker.internal:host-gateway --cpus 1 --memory 512m -p "$PORT:8080" -v "$BIN_DIR:/app:ro" \
  -e "SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:$DB_PORT/$DB" -e "SPRING_DATASOURCE_USERNAME=$DB_USER" -e "SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD" \
  --entrypoint /app/app "$IMAGE" >/dev/null
rc=0
scripts/native-smoke.sh --url "http://localhost:$PORT" || rc=$?
if [ "$rc" != 0 ]; then echo "---- 컨테이너 로그(끝 60줄)" >&2; docker logs "$NAME" 2>&1 | tail -60 >&2; fi
exit "$rc"
