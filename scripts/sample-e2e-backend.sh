#!/usr/bin/env bash
# 샘플 앱 백엔드를 e2e 테스트용으로 올리고 내리는 비대화형 스크립트 (react-skeleton 의 Playwright e2e 가 부른다).
#
#   scripts/sample-e2e-backend.sh start    # DB + 로컬 S3 컨테이너(전용 compose 프로젝트) → bootJar → java -jar(local 프로필) → /health UP 까지 기다린다
#   scripts/sample-e2e-backend.sh stop     # 앱 프로세스를 끄고 컨테이너 · 볼륨을 지운다 (깨끗한 DB 로 다시 시작)
#   scripts/sample-e2e-backend.sh status   # 떠 있으면 0
#
# 환경변수: SERVER_PORT(기본 8080) · DB_PORT(5432) · S3_PORT(8333) · MAIL_SMTP_PORT(1025) · MAIL_HTTP_PORT(8025) · E2E_COMPOSE_PROJECT(kotlin-skeleton-sample-e2e).
# 메일 수신기(mailpit, compose 의 mail 프로필)도 함께 올린다 — 앱은 localhost:$MAIL_SMTP_PORT 로 보내고, 받은 메일은 http://localhost:$MAIL_HTTP_PORT (UI) ·
#   GET http://localhost:$MAIL_HTTP_PORT/api/v1/messages (JSON), 한 통: /api/v1/message/<ID>, 비우기: DELETE /api/v1/messages — 프런트 e2e 는 자기 mailpit 을 띄우지 말고 이것을 쓴다 (같은 포트 · 같은 API).
#   프런트가 다른 포트를 쓰고 싶으면 MAIL_SMTP_PORT · MAIL_HTTP_PORT 를 넘긴다 (앱과 수신기가 같은 값을 쓴다).
# 네이티브 백엔드: E2E_NATIVE=1 이면 java -jar 대신 apps/sample 을 GraalVM 네이티브로 빌드(scripts/native-build.sh, 컨테이너 Gradle)해 리눅스 컨테이너로 띄운다 — 같은 시험을 네이티브에 대고 돌려 보는 길(docs/native-image.md).
#   모듈 켜기/끄기는 빌드 때 굳으므로 **지금 넘긴 환경(SKELETON_* · SPRING_* · PADDLE_*)이 곧 빌드 구성**이다. 컨테이너는 앱이 쓰는 localhost 포트(DB · S3 · 메일 · E2E_NATIVE_FORWARD_PORTS 의 가짜 서버들)를
#   호스트로 이어 주는 socat 곁 컨테이너와 네트워크를 나눠, JVM 때와 같은 설정(localhost:<포트>)이 그대로 맞는다. E2E_NATIVE_SKIP_BUILD=1 이면 이미 만든 실행 파일을 쓴다. 필요: docker 와 로컬 이미지(native-image-community · alpine/socat).
# 자기 개발 컨테이너(kotlin-skeleton)와 섞이지 않게 전용 compose 프로젝트 이름을 쓴다. 로그: build/sample-e2e/backend.log · 상태: build/sample-e2e/state.env (포트 · compose 프로젝트 — stop 이 읽는다)
# 시작에 성공하면 마지막 줄이 `READY http://localhost:<SERVER_PORT>` 다. 이 환경처럼 직접 서버가 막힌 곳에서는 `MARINA_DIRECT=1 scripts/sample-e2e-backend.sh start`.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=build/sample-e2e; PID_FILE="$OUT/backend.pid"; LOG="$OUT/backend.log"; STATE="$OUT/state.env"
# stop · status 는 start 가 쓴 포트 · compose 프로젝트 이름을 그대로 쓴다 — 같은 환경변수를 다시 넘기지 않아도 된다 (start 의 값이 항상 이긴다)
case "${1:-}" in stop|status) if [ -f "$STATE" ]; then . "$STATE"; E2E_COMPOSE_PROJECT="$COMPOSE_PROJECT_NAME"; fi ;; esac

SERVER_PORT="${SERVER_PORT:-8080}"; DB_PORT="${DB_PORT:-5432}"; S3_PORT="${S3_PORT:-8333}"; MAIL_SMTP_PORT="${MAIL_SMTP_PORT:-1025}"; MAIL_HTTP_PORT="${MAIL_HTTP_PORT:-8025}"
export SERVER_PORT DB_PORT S3_PORT MAIL_SMTP_PORT MAIL_HTTP_PORT
export COMPOSE_PROJECT_NAME="${E2E_COMPOSE_PROJECT:-kotlin-skeleton-sample-e2e}"

running() { [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }

stop() {
  if running; then
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
    for _ in $(seq 1 30); do running || break; sleep 1; done
    running && kill -9 "$(cat "$PID_FILE")" 2>/dev/null || true
  fi
  rm -f "$PID_FILE" "$STATE"
  docker rm -f "$COMPOSE_PROJECT_NAME-native-app" "$COMPOSE_PROJECT_NAME-native-fwd" >/dev/null 2>&1 || true
  docker compose --profile s3 --profile mail down -v --remove-orphans >/dev/null 2>&1 || true
  echo "STOPPED"
}

case "${1:-}" in
  start)
    running && { echo "이미 떠 있다 (pid $(cat "$PID_FILE"))" >&2; exit 1; }
    mkdir -p "$OUT"
    # stop · status 가 다시 읽는 상태 (포트 · compose 프로젝트)
    { for v in SERVER_PORT DB_PORT S3_PORT MAIL_SMTP_PORT MAIL_HTTP_PORT COMPOSE_PROJECT_NAME; do printf '%s=%q\n' "$v" "${!v}"; done; } > "$STATE"
    trap 'rc=$?; [ $rc -eq 0 ] || { echo "✗ 시작 실패 — 정리한다 (로그: $LOG)" >&2; stop >/dev/null; }' EXIT
    docker compose up -d --wait postgres
    docker compose --profile s3 up -d s3
    docker compose --profile s3 run --rm s3-init >/dev/null
    docker compose --profile mail up -d mail
    for _ in $(seq 1 30); do curl -fsS "http://localhost:$MAIL_HTTP_PORT/api/v1/info" >/dev/null 2>&1 && break; sleep 1; done
    curl -fsS "http://localhost:$MAIL_HTTP_PORT/api/v1/info" >/dev/null || { echo "✗ 메일 수신기(mailpit)가 $MAIL_HTTP_PORT 에서 뜨지 않았다" >&2; exit 1; }
    if [ "${E2E_NATIVE:-0}" = 1 ]; then
      # 빌드 구성 = 지금 환경. 숫자(포트)만 다른 실행은 AOT 를 다시 하지 않는다(scripts/native-build.sh)
      [ "${E2E_NATIVE_SKIP_BUILD:-0}" = 1 ] || NATIVE_BUILD_ENV="SPRING_PROFILES_ACTIVE $(env | cut -d= -f1 | grep -E '^(SKELETON_|SPRING_APPLICATION_JSON|PADDLE_)' | tr '\n' ' ')" \
        SPRING_PROFILES_ACTIVE=local scripts/native-build.sh --app sample >&2
      BIN_DIR="$PWD/apps/sample/build/native/nativeCompile"; [ -x "$BIN_DIR/app" ] || { echo "✗ 네이티브 실행 파일이 없다: $BIN_DIR/app" >&2; exit 1; }
      IMAGE="${NATIVE_IMAGE_BUILDER:-ghcr.io/graalvm/native-image-community:25}"; FWD="$COMPOSE_PROJECT_NAME-native-fwd"; APPC="$COMPOSE_PROJECT_NAME-native-app"
      FWD_CMD=""; for p in $DB_PORT $S3_PORT $MAIL_SMTP_PORT ${E2E_NATIVE_FORWARD_PORTS:-}; do FWD_CMD+="socat TCP-LISTEN:$p,fork,reuseaddr TCP:host.docker.internal:$p & "; done
      docker run -d --name "$FWD" -p "$SERVER_PORT:$SERVER_PORT" --entrypoint sh alpine/socat -c "$FWD_CMD wait" >/dev/null
      ENV_ARGS=(); for name in $(env | cut -d= -f1 | grep -E '^(SKELETON_|SPRING_|PADDLE_|MAIL_|S3_|DB_|SERVER_)'); do ENV_ARGS+=(-e "$name"); done
      nohup perl -MPOSIX -e 'POSIX::setsid(); exec @ARGV' docker run --rm --name "$APPC" --network "container:$FWD" --cpus 2 --memory 1g -v "$BIN_DIR:/app:ro" \
        "${ENV_ARGS[@]}" -e SPRING_PROFILES_ACTIVE=local --entrypoint /app/app "$IMAGE" > "$LOG" 2>&1 &
    else
      ./gradlew :apps:sample:bootJar -q --console=plain
      JAR="$(ls apps/sample/build/libs/*.jar | grep -v -- '-plain.jar' | head -1)"
      # 자기 세션(프로세스 그룹)에서 띄운다 — 부른 쪽이 끝나며 자기 그룹을 죽여도 백엔드가 같이 죽지 않게. macOS 에는 setsid 명령이 없어 perl 로 한다 (exec 이라 $! 가 java 의 pid)
      nohup perl -MPOSIX -e 'POSIX::setsid(); exec @ARGV' java -jar "$JAR" --spring.profiles.active=local > "$LOG" 2>&1 &
    fi
    echo $! > "$PID_FILE"
    for _ in $(seq 1 120); do
      running || { echo "✗ 앱이 먼저 종료됐다" >&2; tail -30 "$LOG" >&2; exit 1; }
      curl -fsS "http://localhost:$SERVER_PORT/health" 2>/dev/null | grep -q '"UP"' && { trap - EXIT; echo "MAIL smtp=localhost:$MAIL_SMTP_PORT api=http://localhost:$MAIL_HTTP_PORT/api/v1"; echo "READY http://localhost:$SERVER_PORT"; exit 0; }
      sleep 1
    done
    echo "✗ 120초 안에 /health 가 UP 이 되지 않았다" >&2; tail -30 "$LOG" >&2; exit 1 ;;
  stop) stop ;;
  status) running && { echo "UP pid $(cat "$PID_FILE")"; } || { echo "DOWN"; exit 1; } ;;
  *) echo "usage: $0 start|stop|status" >&2; exit 2 ;;
esac
