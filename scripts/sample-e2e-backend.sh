#!/usr/bin/env bash
# 샘플 앱 백엔드를 e2e 테스트용으로 올리고 내리는 비대화형 스크립트 (react-skeleton 의 Playwright e2e 가 부른다).
#
#   scripts/sample-e2e-backend.sh start    # DB + 로컬 S3 컨테이너(전용 compose 프로젝트) → bootJar → java -jar(local 프로필) → /health UP 까지 기다린다
#   scripts/sample-e2e-backend.sh stop     # 앱 프로세스를 끄고 컨테이너 · 볼륨을 지운다 (깨끗한 DB 로 다시 시작)
#   scripts/sample-e2e-backend.sh status   # 떠 있으면 0
#
# 환경변수: SERVER_PORT(기본 8080) · DB_PORT(5432) · S3_PORT(8333) · E2E_COMPOSE_PROJECT(kotlin-skeleton-sample-e2e).
# 자기 개발 컨테이너(kotlin-skeleton)와 섞이지 않게 전용 compose 프로젝트 이름을 쓴다. 로그: build/sample-e2e/backend.log
# 시작에 성공하면 마지막 줄이 `READY http://localhost:<SERVER_PORT>` 다. 이 환경처럼 직접 서버가 막힌 곳에서는 `MARINA_DIRECT=1 scripts/sample-e2e-backend.sh start`.
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER_PORT="${SERVER_PORT:-8080}"; DB_PORT="${DB_PORT:-5432}"; S3_PORT="${S3_PORT:-8333}"
export SERVER_PORT DB_PORT S3_PORT
export COMPOSE_PROJECT_NAME="${E2E_COMPOSE_PROJECT:-kotlin-skeleton-sample-e2e}"
OUT=build/sample-e2e; PID_FILE="$OUT/backend.pid"; LOG="$OUT/backend.log"

running() { [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }

stop() {
  if running; then
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
    for _ in $(seq 1 30); do running || break; sleep 1; done
    running && kill -9 "$(cat "$PID_FILE")" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  docker compose --profile s3 down -v --remove-orphans >/dev/null 2>&1 || true
  echo "STOPPED"
}

case "${1:-}" in
  start)
    running && { echo "이미 떠 있다 (pid $(cat "$PID_FILE"))" >&2; exit 1; }
    mkdir -p "$OUT"
    trap 'rc=$?; [ $rc -eq 0 ] || { echo "✗ 시작 실패 — 정리한다 (로그: $LOG)" >&2; stop >/dev/null; }' EXIT
    docker compose up -d --wait postgres
    docker compose --profile s3 up -d s3
    docker compose --profile s3 run --rm s3-init >/dev/null
    ./gradlew :apps:sample:bootJar -q --console=plain
    JAR="$(ls apps/sample/build/libs/*.jar | grep -v -- '-plain.jar' | head -1)"
    nohup java -jar "$JAR" --spring.profiles.active=local > "$LOG" 2>&1 &
    echo $! > "$PID_FILE"
    for _ in $(seq 1 120); do
      running || { echo "✗ 앱이 먼저 종료됐다" >&2; tail -30 "$LOG" >&2; exit 1; }
      curl -fsS "http://localhost:$SERVER_PORT/health" 2>/dev/null | grep -q '"UP"' && { trap - EXIT; echo "READY http://localhost:$SERVER_PORT"; exit 0; }
      sleep 1
    done
    echo "✗ 120초 안에 /health 가 UP 이 되지 않았다" >&2; tail -30 "$LOG" >&2; exit 1 ;;
  stop) stop ;;
  status) running && { echo "UP pid $(cat "$PID_FILE")"; } || { echo "DOWN"; exit 1; } ;;
  *) echo "usage: $0 start|stop|status" >&2; exit 2 ;;
esac
