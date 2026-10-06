#!/usr/bin/env bash
# 홈서버 배포 계약(docs/deploy.md)을 진짜 컨테이너로 증명한다.
#
#   scripts/test-deploy-contract.sh                 # apps/api 와 apps/sample 이미지를 빌드해 검사한다
#   scripts/test-deploy-contract.sh --apps api      # 하나만
#   scripts/test-deploy-contract.sh --reuse         # 이미 빌드한 이미지(skeleton-contract-<app>:test)가 있으면 다시 빌드하지 않는다
#
# 하는 일 (앱마다)
#   1. Dockerfile 로 이미지를 빌드한다 (--build-arg APP=<app>)
#   2. 일회용 네트워크 + postgres 컨테이너를 만들고, 앱을 **계약의 환경변수만** 주어 띄운다:
#        SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD  (앱 접두사가 붙은 DB 변수는 없다)
#   3. 플랫폼과 같은 헬스체크 명령을 컨테이너 **안에서** 돌려 2xx · 401 · 403 을 "살아 있음" 으로 읽는다 (이미지에 wget 이 있어야 한다)
#   4. 0.0.0.0 에서 듣는지 (같은 네트워크의 다른 컨테이너가 이름으로 닿는다 + 소켓이 루프백이 아니다)
#   5. 로그가 stdout 으로만 나오는지 (docker logs 에 있고, 컨테이너 안에 새 로그 파일이 없다)
#   6. 쓰지 않는 모듈의 설정을 요구하지 않는다 (AWS 자격 증명 따위를 찾으며 죽지 않는다)
#   7. 보호 환경(SPRING_PROFILES_ACTIVE=prod / SKELETON_ENV=prod)에서는 안전하지 않은 구성이 읽을 수 있는 가드 메시지와 함께 stdout 으로 실패하고 값은 새지 않는다
#   8. 정직한 헬스: GET /health 는 인증 없이 200 {"status":"UP"} 뿐이고, DB 컨테이너를 멈추면 503, 다시 올리면 200
#   8b. (A2) DB 보다 앱이 먼저 뜬다: DB 컨테이너가 없는 채로 앱을 먼저 띄우고 DB 를 늦게 올려도 앱이 재시작 없이 /health 200 에 이른다(startup-wait). DB 가 끝내 안 나타나면
#      host:port · 원인 · 가능한 원인을 말하는 메시지로 실패하고 URL 의 비밀번호는 새지 않는다 (docs/deploy.md §3)
#   9. (메일 모듈이 있는 앱 = sample) 시험 배포 조합(docs/deploy.md §10) — JWT 비밀 · 메일 발송 길 · 링크 주소 · 첫 관리자 + 플랫폼이 넣는 모양 그대로의
#      <PREFIX>_CLIENT_IP_MODE(소문자) / _TRUSTED_PROXIES(실제 도커 네트워크 CIDR + 하나 더, 쉼표로 이어)를 주면 SKELETON_ENV=prod 로 **실제로 뜬다**. 하나씩 빼면 가드가 그 이름을 말하며 실패한다
#
# Docker 가 필요하다. marina 가 도커를 가로채는 기계에서는 `MARINA_DIRECT=1 scripts/test-deploy-contract.sh`.
# 만든 컨테이너 · 네트워크 · 이미지는 끝나면 지운다 (KEEP=1 이면 전부, KEEP_IMAGES=1 이면 이미지만 남긴다 — `--reuse` 와 함께 쓰면 반복이 빠르다). 종료 코드: 0 전부 통과 / 1 실패 / 2 인자 오류.
#
# 아래 HEALTH_CMD 는 홈서버 레포 infra/modules/app-docker/app.tf 의 healthcheck_test 와 같은 문장이다 — 플랫폼이 바꾸면 여기도 바꾼다.
set -uo pipefail

SRC="$(cd "$(dirname "$0")/.." && pwd -P)"
APPS=""
REUSE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --apps) [ $# -ge 2 ] || { echo "--apps needs a value" >&2; exit 2; }; APPS="$2"; shift 2 ;;
    --apps=*) APPS="${1#--apps=}"; shift ;;
    --reuse) REUSE=1; shift ;;
    -h|--help) sed -n 2,24p "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
if [ -z "$APPS" ]; then   # 기본: 이 프로젝트에 있는 앱 (스켈레톤은 둘 다, new-project.sh 로 찍은 프로젝트는 api 만 — --with-sample 이면 둘 다)
  for a in api sample; do [ ! -d "$SRC/apps/$a" ] || APPS="${APPS:+$APPS,}$a"; done
fi
for a in $(echo "$APPS" | tr ',' ' '); do
  case "$a" in api|sample) ;; *) echo "unknown app: $a (api | sample)" >&2; exit 2 ;; esac
  [ -d "$SRC/apps/$a" ] || { echo "apps/$a does not exist in this project" >&2; exit 2; }
done
command -v docker >/dev/null || { echo "docker is required" >&2; exit 2; }

PORT=8080
RUN="skdc$$"
NET="$RUN-net"
CONTAINERS=""
IMAGES=""
FAILURES=0

pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1"; FAILURES=$((FAILURES + 1)); }
note() { echo "    $1"; }

cleanup() {
  if [ "${KEEP:-0}" = 1 ]; then echo "(KEEP=1: containers $CONTAINERS network $NET left)"; return; fi
  # shellcheck disable=SC2086
  [ -z "$CONTAINERS" ] || docker rm -f $CONTAINERS >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
}
trap cleanup EXIT

health_path() { echo /health; }   # 두 앱 모두 management base-path 가 / 다 — 인증 없이 앱 + DB 가 떠 있을 때만 200, 아니면 503 (docs/deploy.md §3)

# 플랫폼 헬스체크와 같은 문장 (2xx · 401 · 403 = 살아 있음)
health_cmd() { # <health path>
  printf '%s' "S=\$(wget -S -q -O /dev/null http://localhost:$PORT$1 2>&1 | sed -n 's|^ *HTTP/1\\.[01] \\([0-9][0-9][0-9]\\).*|\\1|p' | head -1); case \"\$S\" in 2??|401|403) exit 0;; *) exit 1;; esac"
}
health_status() { docker exec "$1" sh -c "wget -S -q -O /dev/null http://localhost:$PORT$2 2>&1 | sed -n 's|^ *HTTP/1\\.[01] \\([0-9][0-9][0-9]\\).*|\\1|p' | head -1"; }

health_is() { [ "$(health_status "$1" "$2")" = "$3" ]; }   # <컨테이너> <경로> <기대 코드>

wait_for() { # <timeout s> <명령...> — 성공할 때까지 1초 간격
  local limit="$1" i=0; shift
  while [ "$i" -lt "$limit" ]; do "$@" >/dev/null 2>&1 && return 0; sleep 1; i=$((i + 1)); done
  return 1
}
running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = true ]; }
stopped() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = false ]; }

docker network create "$NET" >/dev/null || { echo "cannot create a docker network"; exit 1; }

echo "== 데이터베이스 (일회용 postgres:18)"
PG="$RUN-pg"; CONTAINERS="$CONTAINERS $PG"
docker run -d --name "$PG" --network "$NET" --network-alias db \
  -e POSTGRES_DB=app -e POSTGRES_USER=app -e POSTGRES_PASSWORD=contract-test-pw postgres:18 >/dev/null
if wait_for 90 docker exec "$PG" pg_isready -h 127.0.0.1 -U app -d app; then pass "postgres 가 받는다"; else fail "postgres 가 뜨지 않는다"; docker logs "$PG" 2>&1 | tail -5; exit 1; fi
sleep 2   # 초기화 직후 재시작 구간을 넘긴다

# 계약의 환경변수만 — 스프링 프로필 · 앱 접두사 DB 변수 · 비밀 없음. 앱마다 새 데이터베이스(앱의 마이그레이션이 서로 섞이면 Flyway 검증이 실패한다)
DBNAME=app
contract_env() { printf '%s\n' "-e" "SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/$DBNAME" "-e" "SPRING_DATASOURCE_USERNAME=app" "-e" "SPRING_DATASOURCE_PASSWORD=contract-test-pw"; }

for APP in $(echo "$APPS" | tr ',' ' '); do
  IMG="skeleton-contract-$APP:test"
  DBNAME="app_$APP"
  docker exec "$PG" psql -U app -d postgres -qc "create database $DBNAME" >/dev/null || { fail "데이터베이스 $DBNAME 을 만들지 못했다"; continue; }
  HEALTH="$(health_path "$APP")"
  echo
  echo "== apps/$APP"
  if [ "$REUSE" = 1 ] && docker image inspect "$IMG" >/dev/null 2>&1; then
    pass "이미지 재사용 ($IMG)"
  else
    echo "  (이미지 빌드 — 몇 분 걸린다)"
    IMAGES="$IMAGES $IMG"
    if docker build -q --build-arg "APP=$APP" -t "$IMG" "$SRC" >/dev/null; then pass "이미지 빌드 ($IMG)"; else fail "이미지 빌드 실패"; continue; fi
  fi

  echo "-- A. 계약의 환경변수만으로 뜬다"
  C="$RUN-$APP"; CONTAINERS="$CONTAINERS $C"
  # shellcheck disable=SC2046
  docker run -d --name "$C" --network "$NET" --network-alias "app-$APP" $(contract_env) "$IMG" >/dev/null
  CMD="$(health_cmd "$HEALTH")"
  if wait_for 180 docker exec "$C" sh -c "$CMD"; then pass "컨테이너 안 wget 헬스체크가 통과한다 ($HEALTH)"; else
    fail "헬스체크가 통과하지 않는다 ($HEALTH)"; docker logs "$C" 2>&1 | tail -25; continue
  fi
  CODE="$(health_status "$C" "$HEALTH")"
  note "응답 코드: ${CODE:-없음}"
  case "$CODE" in 2??|401|403) pass "응답 코드가 2xx · 401 · 403 중 하나다 ($CODE)" ;; *) fail "응답 코드가 살아 있음이 아니다: $CODE" ;; esac
  docker exec "$C" sh -c 'command -v wget' >/dev/null 2>&1 && pass "이미지에 wget 이 있다" || fail "이미지에 wget 이 없다"
  if [ "$CODE" = 200 ]; then pass "/health 는 인증 없이 200 이다 (401 이 아니다)"; else fail "/health 가 200 이 아니다: $CODE"; fi
  BODY="$(docker exec "$C" sh -c "wget -q -O - http://localhost:$PORT$HEALTH" 2>/dev/null)"
  if grep -q '"status":"UP"' <<<"$BODY" && ! grep -q 'components\|details' <<<"$BODY"; then pass "본문은 status UP 뿐이다 (세부 정보 없음)"; else fail "health 본문이 이상하다: $BODY"; fi
  # 0.0.0.0 — 다른 컨테이너가 이름으로 닿고, 듣는 소켓이 루프백이 아니다
  OTHER="$(docker run --rm --network "$NET" alpine:3 sh -c "wget -S -q -O /dev/null http://app-$APP:$PORT$HEALTH 2>&1 | sed -n 's|^ *HTTP/1\\.[01] \\([0-9][0-9][0-9]\\).*|\\1|p' | head -1")"
  case "$OTHER" in 2??|401|403) pass "같은 네트워크의 다른 컨테이너가 app-$APP:$PORT 에 닿는다 ($OTHER)" ;; *) fail "다른 컨테이너에서 닿지 않는다 (0.0.0.0 이 아니다?): '$OTHER'" ;; esac
  LISTEN="$(docker exec "$C" sh -c "netstat -tln 2>/dev/null | grep ':$PORT '" || true)"
  note "듣는 소켓: $(echo "$LISTEN" | head -1)"
  if grep -Eq '(0\.0\.0\.0|:::|\*)[:.]?'"$PORT" <<<"$LISTEN"; then pass "0.0.0.0:$PORT (모든 인터페이스)에서 듣는다"; else fail "듣는 소켓이 모든 인터페이스가 아니다: $LISTEN"; fi
  [ -z "$(docker port "$C")" ] && pass "호스트 포트를 열지 않았다 (플랫폼이 네트워크로만 잇는다)" || fail "호스트 포트가 열려 있다"

  # stdout 로깅
  LOGS="$(docker logs "$C" 2>&1)"
  [ -n "$LOGS" ] && grep -q 'Started' <<<"$LOGS" && pass "로그가 docker logs(stdout)에 나온다 ('Started …')" || fail "docker logs 에 기동 로그가 없다"
  NEWLOGS="$(docker diff "$C" | grep -Ei '\.log' || true)"
  [ -z "$NEWLOGS" ] && pass "컨테이너 안에 새 로그 파일이 생기지 않는다" || fail "로그 파일이 생겼다: $NEWLOGS"
  grep -Eqi 'traceId=' <<<"$LOGS" && pass "로그 줄에 traceId 가 붙는다 (스켈레톤 로그 형식)" || note "(traceId 형식은 확인하지 못했다 — 요청이 아직 없다)"

  # 쓰지 않는 모듈의 설정을 요구하지 않는다
  if grep -Eqi 'credential|SdkClientException|aws.*(profile|region).*(missing|not)|Unable to load' <<<"$LOGS"; then
    fail "AWS 자격 증명 따위를 찾은 흔적이 로그에 있다"; grep -Ei 'credential|SdkClient|Unable to load' <<<"$LOGS" | head -3
  else
    pass "쓰지 않는 모듈의 설정을 요구하지 않는다 (자격 증명 · 설정 요구 로그 없음)"
  fi
  grep -q 'deploy guards:' <<<"$LOGS" && pass "기동 로그에 배포 가드 요약이 있다: $(echo "$LOGS" | grep 'deploy guards:' | head -1 | sed 's/^.*deploy guards:/deploy guards:/')" || fail "기동 로그에 배포 가드 요약이 없다"
  # 정직한 헬스 — 마지막에 한다 (DB 를 멈추면 로그가 오류로 넘친다)
  docker stop "$PG" >/dev/null
  if wait_for 60 health_is "$C" "$HEALTH" 503; then pass "DB 컨테이너를 멈추면 /health 가 503 이다 (죽은 앱이 살아 있다고 나오지 않는다)"; else fail "DB 를 멈췄는데 /health 가 503 이 되지 않았다: $(health_status "$C" "$HEALTH")"; fi
  docker start "$PG" >/dev/null
  if wait_for 90 docker exec "$PG" pg_isready -h 127.0.0.1 -U app -d app; then pass "DB 를 다시 올렸다"; else fail "DB 가 다시 뜨지 않는다"; fi
  if wait_for 90 health_is "$C" "$HEALTH" 200; then pass "DB 가 돌아오면 /health 가 다시 200 이다"; else fail "DB 복구 뒤에도 /health 가 200 이 아니다: $(health_status "$C" "$HEALTH")"; fi

  docker rm -f "$C" >/dev/null

  echo "-- A2. DB 보다 앱이 먼저 뜬다 (실제 배포에서 재시작을 두 번 해야 했던 경우) — 기다렸다가 재시작 없이 뜬다 / 끝내 안 나타나면 읽을 수 있는 메시지로 실패한다"
  # (1) 앱 먼저: DB 컨테이너가 아직 없다 — 이름 late-db 가 풀리지 않는다. 그다음 DB 를 올리면 첫 초기화 동안 연결 거부 → 시작 중 구간을 지난다
  LATE="$RUN-$APP-latepg"; C="$RUN-$APP-late"; CONTAINERS="$CONTAINERS $C $LATE"
  docker run -d --name "$C" --network "$NET" -e "SPRING_DATASOURCE_URL=jdbc:postgresql://late-db:5432/app" -e SPRING_DATASOURCE_USERNAME=app -e SPRING_DATASOURCE_PASSWORD=late-test-pw "$IMG" >/dev/null
  if wait_for 90 bash -c "docker logs '$C' 2>&1 | grep -q '\[startup-wait\] waiting for database late-db:5432'"; then pass "DB 가 없는 동안 앱이 기다린다는 줄이 나온다 ([startup-wait] waiting for database late-db:5432)"; else fail "기다린다는 로그가 없다"; docker logs "$C" 2>&1 | tail -15; fi
  STARTED1="$(docker inspect -f '{{.State.StartedAt}}' "$C")"
  docker run -d --name "$LATE" --network "$NET" --network-alias late-db -e POSTGRES_DB=app -e POSTGRES_USER=app -e POSTGRES_PASSWORD=late-test-pw postgres:18 >/dev/null
  if wait_for 180 health_is "$C" "$HEALTH" 200; then pass "DB 가 늦게 올라온 뒤 앱이 /health 200 에 이른다 (기다린 뒤 붙었다)"; else
    fail "DB 를 늦게 올렸는데 앱이 뜨지 않았다: $(health_status "$C" "$HEALTH")"; docker logs "$C" 2>&1 | tail -25
  fi
  if running "$C" && [ "$(docker inspect -f '{{.State.StartedAt}}' "$C")" = "$STARTED1" ]; then pass "앱 컨테이너는 한 번도 죽거나 재시작하지 않았다 (StartedAt 같음)"; else fail "앱 컨테이너가 죽었거나 다시 시작했다"; fi
  LOGS="$(docker logs "$C" 2>&1)"
  grep -q 'is reachable (attempt' <<<"$LOGS" && pass "로그에 '데이터베이스에 닿았다' 줄이 있다: $(grep 'is reachable' <<<"$LOGS" | head -1)" || fail "닿았다는 로그가 없다"
  grep -q 'APPLICATION FAILED TO START\|JdbcAggregateOperations' <<<"$LOGS" && fail "기다리는 동안 기동 실패 화면 · 빈 스택 트레이스가 나왔다" || pass "기다리는 동안 기동 실패 화면 · 빈 스택 트레이스가 없다"
  docker rm -f "$C" "$LATE" >/dev/null
  # (2) 끝내 안 나타나는 DB — 짧은 timeout 으로 읽을 수 있는 메시지와 함께 실패한다. URL 에 비밀번호를 넣어 두고 새지 않는지도 본다
  C="$RUN-$APP-nodb"; CONTAINERS="$CONTAINERS $C"
  docker run -d --name "$C" --network "$NET" -e "SPRING_DATASOURCE_URL=jdbc:postgresql://nowhere-db:5432/app?user=leakme&password=leak-pw-123" \
    -e SKELETON_PERSISTENCEJDBC_STARTUPWAIT_TIMEOUT=6s -e SKELETON_PERSISTENCEJDBC_STARTUPWAIT_INTERVAL=1s "$IMG" >/dev/null
  if wait_for 120 stopped "$C"; then
    EXIT="$(docker inspect -f '{{.State.ExitCode}}' "$C")"; LOGS="$(docker logs "$C" 2>&1)"
    [ "$EXIT" != 0 ] && pass "DB 가 끝내 안 나타나면 기동이 실패한다 (exit $EXIT)" || fail "기동이 실패해야 하는데 exit 0"
    grep -q 'APPLICATION FAILED TO START' <<<"$LOGS" && pass "스택 트레이스 대신 FailureAnalyzer 화면이다" || fail "FailureAnalyzer 화면이 아니다"
    grep -q 'Cannot connect to the database at nowhere-db:5432' <<<"$LOGS" && pass "메시지가 host:port 를 말한다 (nowhere-db:5432)" || { fail "host:port 메시지가 없다"; echo "$LOGS" | tail -15; }
    grep -q 'unknown host' <<<"$LOGS" && pass "원인을 말한다 (unknown host)" || fail "원인(unknown host)이 없다"
    grep -q 'SPRING_DATASOURCE_URL' <<<"$LOGS" && grep -q 'still starting' <<<"$LOGS" && pass "가능한 원인(아직 시작 중 · SPRING_DATASOURCE_URL · 망)을 나열한다" || fail "가능한 원인 안내가 없다"
    grep -q 'waited 6s' <<<"$LOGS" && pass "기다린 시간(6s)을 말한다" || fail "기다린 시간을 말하지 않는다"
    grep -q 'leak-pw-123\|leakme' <<<"$LOGS" && fail "실패 화면에 URL 의 사용자 · 비밀번호가 새었다" || pass "실패 화면에 URL 의 사용자 · 비밀번호가 없다"
    grep -q 'JdbcAggregateOperations' <<<"$LOGS" && fail "빈 스택 트레이스(JdbcAggregateOperations)가 남아 있다" || pass "JdbcAggregateOperations 스택 트레이스가 없다"
    echo "$LOGS" | sed -n '/APPLICATION FAILED TO START/,$p' | head -12 | sed 's/^/    | /'
  else
    fail "DB 가 없는데 기동이 끝나지 않았다 (timeout 6s)"; docker logs "$C" 2>&1 | tail -10
  fi
  docker rm -f "$C" >/dev/null
  # (3) 기다리기를 끈 앱(모듈 기본) — 빈 생성 중에 연결이 실패해도 JdbcAggregateOperations 스택 트레이스 대신 같은 읽을 수 있는 메시지가 나온다 (실제 배포에서 본 실패의 자리)
  C="$RUN-$APP-nowait"; CONTAINERS="$CONTAINERS $C"
  docker run -d --name "$C" --network "$NET" -e "SPRING_DATASOURCE_URL=jdbc:postgresql://nowhere-db:5432/app?user=leakme&password=leak-pw-123" -e SKELETON_PERSISTENCEJDBC_STARTUPWAIT_ENABLED=false "$IMG" >/dev/null
  if wait_for 150 stopped "$C"; then
    LOGS="$(docker logs "$C" 2>&1)"
    grep -q 'Cannot connect to the database at nowhere-db:5432' <<<"$LOGS" && pass "기다리기를 끈 앱도 빈 생성 중 연결 실패를 host:port 메시지로 말한다" || { fail "기다리기를 끈 앱의 실패가 읽을 수 있는 메시지가 아니다"; echo "$LOGS" | tail -20; }
    grep -q 'APPLICATION FAILED TO START' <<<"$LOGS" && grep -q 'waited' <<<"$LOGS" && fail "기다리기를 껐는데 기다렸다는 말이 있다" || pass "기다리기를 껐으면 기다리지 않는다 (기다렸다는 말 없음)"
    grep -q 'leak-pw-123\|leakme' <<<"$LOGS" && fail "이 실패 화면에도 URL 의 비밀번호가 새었다" || pass "이 실패 화면에도 URL 의 사용자 · 비밀번호가 없다"
  else
    fail "DB 가 없는데 기동이 끝나지 않았다 (기다리기 끔)"; docker logs "$C" 2>&1 | tail -10
  fi
  docker rm -f "$C" >/dev/null

  echo "-- B. 보호 프로필(prod) — 안전하지 않은 구성이면 읽을 수 있는 메시지로 stdout 에서 실패한다"
  C="$RUN-$APP-prod"; CONTAINERS="$CONTAINERS $C"
  # shellcheck disable=SC2046
  docker run -d --name "$C" --network "$NET" $(contract_env) -e SPRING_PROFILES_ACTIVE=prod "$IMG" >/dev/null
  if wait_for 120 stopped "$C"; then
    EXIT="$(docker inspect -f '{{.State.ExitCode}}' "$C")"
    [ "$EXIT" != 0 ] && pass "프로필 prod 에 기본 비밀로는 기동이 실패한다 (exit $EXIT)" || fail "기동이 실패해야 하는데 exit 0"
    LOGS="$(docker logs "$C" 2>&1)"
    grep -q 'skeleton.auth.jwt.secret' <<<"$LOGS" && pass "실패 메시지가 설정 이름을 말한다 (skeleton.auth.jwt.secret)" || { fail "실패 메시지에 설정 이름이 없다"; echo "$LOGS" | tail -15; }
    grep -q 'dev-local-jwt-secret' <<<"$LOGS" && fail "실패 화면에 비밀 값이 새었다" || pass "실패 화면에 비밀 값이 없다"
    grep -Eqi 'credential|SdkClient' <<<"$LOGS" && fail "AWS 자격 증명 요구로 죽었다" || pass "실패 원인이 AWS 설정이 아니다"
  else
    fail "prod 프로필인데 기동이 막히지 않았다"; docker logs "$C" 2>&1 | tail -10
  fi
  docker rm -f "$C" >/dev/null

  echo "-- C. SKELETON_ENV=prod (프로필 없이) — 스위치만으로도 같은 가드가 선다"
  C="$RUN-$APP-env"; CONTAINERS="$CONTAINERS $C"
  # shellcheck disable=SC2046
  docker run -d --name "$C" --network "$NET" $(contract_env) -e SKELETON_ENV=prod "$IMG" >/dev/null
  if wait_for 120 stopped "$C"; then
    LOGS="$(docker logs "$C" 2>&1)"
    grep -q 'skeleton.env=prod' <<<"$LOGS" && pass "SKELETON_ENV=prod 만으로 기동이 막히고 이유에 skeleton.env=prod 가 보인다" || { fail "스위치가 보호로 이어지지 않았다"; echo "$LOGS" | tail -12; }
    grep -q 'APPLICATION FAILED TO START' <<<"$LOGS" && pass "스택 트레이스 대신 FailureAnalyzer 화면이다" || fail "FailureAnalyzer 화면이 아니다"
    echo "$LOGS" | sed -n '/APPLICATION FAILED TO START/,$p' | head -16 | sed 's/^/    | /'
  else
    fail "SKELETON_ENV=prod 인데 기동이 막히지 않았다"; docker logs "$C" 2>&1 | tail -10
  fi
  docker rm -f "$C" >/dev/null

  echo "-- C2. JWT 비밀만 있고 계정 가드가 요구하는 것은 없다 — 가드가 빠진 것을 이름으로 말한다"
  C="$RUN-$APP-env2"; CONTAINERS="$CONTAINERS $C"
  C2JWT="$(head -c 36 /dev/urandom | base64 | tr -d '\n=')"
  # shellcheck disable=SC2046
  docker run -d --name "$C" --network "$NET" $(contract_env) -e SKELETON_ENV=prod -e "JWT_SECRET=$C2JWT" "$IMG" >/dev/null
  if wait_for 120 stopped "$C"; then
    LOGS="$(docker logs "$C" 2>&1)"
    if grep -q 'skeleton.web.client-ip.mode' <<<"$LOGS"; then pass "클라이언트 IP mode 미설정이 가드 메시지에 있다 (IP 한도 우회 방지)"; else fail "client-ip 가드 메시지가 없다"; echo "$LOGS" | tail -12; fi
    if grep -q 'mail transport' <<<"$LOGS"; then pass "메일 발송 길 없음이 가드 메시지에 있다"; else note "(메일 모듈이 있는 앱 — 메일 메시지 없음)"; fi
    if grep -q "$C2JWT" <<<"$LOGS"; then fail "실패 화면에 JWT 비밀이 새었다"; else pass "실패 화면에 비밀 값이 없다"; fi
  else
    fail "JWT 만 있는데 기동이 막히지 않았다 (client-ip · 메일 가드)"; docker logs "$C" 2>&1 | tail -10
  fi
  docker rm -f "$C" >/dev/null

  echo "-- C3. JWT_SECRET 이 선언의 secrets 에서 빠진 배포 (빈 값) — Empty key 크래시가 아니라 이름 붙은 가드 메시지"
  C="$RUN-$APP-nojwt"; CONTAINERS="$CONTAINERS $C"
  # shellcheck disable=SC2046
  docker run -d --name "$C" --network "$NET" $(contract_env) -e JWT_SECRET= "$IMG" >/dev/null
  if wait_for 120 stopped "$C"; then
    LOGS="$(docker logs "$C" 2>&1)"
    if grep -q 'Empty key' <<<"$LOGS"; then fail "서명기가 Empty key 로 죽었다 (가드 메시지가 아니다)"; else pass "Empty key 크래시가 아니다"; fi
    if grep -q 'JWT_SECRET' <<<"$LOGS"; then pass "메시지가 환경변수 이름(JWT_SECRET)을 말한다"; else fail "JWT_SECRET 이름이 메시지에 없다"; echo "$LOGS" | tail -10; fi
  else
    fail "빈 JWT 비밀인데 기동이 막히지 않았다"
  fi
  docker rm -f "$C" >/dev/null

  if [ "$APP" = sample ]; then   # 메일 모듈(notification-mail)이 있는 앱 — 시험 배포 조합이 실제로 뜬다
    echo "-- D. 시험 배포 조합 (docs/deploy.md §10) — SKELETON_ENV=prod 로 실제로 뜬다"
    SUBNET="$(docker network inspect "$NET" -f '{{(index .IPAM.Config 0).Subnet}}')"
    JWT="$(head -c 36 /dev/urandom | base64 | tr -d '\n=')"
    base_d_env() {   # $1 = nomail 이면 메일 발송 길을 뺀다
      printf '%s\n' -e SKELETON_ENV=prod -e SPRING_PROFILES_ACTIVE=prod -e "JWT_SECRET=$JWT" \
        -e SKELETON_ACCOUNT_MAIL_LINK_BASE_URL=https://app.example.com -e SKELETON_ACCOUNT_BOOTSTRAP_ADMIN_EMAIL=boss@example.com \
        -e SKELETON_LEGAL_ACKNOWLEDGE_TEMPLATE=true   # 약관 문서가 모듈의 TEMPLATE 인 시험 배포 — 일부러 허락 (docs/deploy.md §10, docs/legal.md)
      [ "${1:-}" = nomail ] || printf '%s\n' -e SKELETON_NOTIFICATION_MAIL_ENABLED=true -e SKELETON_NOTIFICATION_MAIL_FROM=no-reply@example.com -e SPRING_MAIL_HOST=mail-relay.invalid
    }
    C="$RUN-$APP-d"; CONTAINERS="$CONTAINERS $C"
    # 플랫폼이 넣는 이름 · 모양 그대로 (홈서버 infra/modules/app-docker/app.tf — client_ip_env):
    #   "${var.app.env_prefix}_CLIENT_IP_MODE=${local.has_domain ? "cloudflare" : "proxy"}"      (WEB_ 없음 · 소문자)
    #   "${var.app.env_prefix}_CLIENT_IP_TRUSTED_PROXIES=${local.web_trusted_proxies}"          (join(",", local.web_subnets) — 쉼표로 이은 CIDR)
    # shellcheck disable=SC2046
    docker run -d --name "$C" --network "$NET" $(contract_env) $(base_d_env) -e SKELETON_CLIENT_IP_MODE=proxy -e "SKELETON_CLIENT_IP_TRUSTED_PROXIES=$SUBNET,10.99.0.0/16" "$IMG" >/dev/null
    if wait_for 180 health_is "$C" "$HEALTH" 200; then
      pass "SKELETON_ENV=prod 에서 가드를 모두 통과해 뜬다 (/health 200)"
      LOGS="$(docker logs "$C" 2>&1)"
      if grep -qi 'Client IP: proxy' <<<"$LOGS"; then pass "<PREFIX>_CLIENT_IP_MODE · _TRUSTED_PROXIES(플랫폼이 넣는 이름 · 소문자 · 쉼표 목록)가 바인딩된다 ($(echo "$LOGS" | grep 'Client IP:' | head -1 | sed 's/^.*Client IP:/Client IP:/'))"; else fail "client-ip 환경변수가 바인딩되지 않았다"; fi
      if grep -q 'deploy guards: env=prod' <<<"$LOGS"; then pass "가드 요약에 env=prod"; else fail "가드 요약이 env=prod 가 아니다"; fi
    else
      fail "시험 배포 조합이 뜨지 않는다"; docker logs "$C" 2>&1 | tail -25
    fi
    docker rm -f "$C" >/dev/null

    C="$RUN-$APP-d1"; CONTAINERS="$CONTAINERS $C"   # client-ip 를 빼면
    # shellcheck disable=SC2046
    docker run -d --name "$C" --network "$NET" $(contract_env) $(base_d_env) "$IMG" >/dev/null
    if wait_for 120 stopped "$C"; then
      LOGS="$(docker logs "$C" 2>&1)"   # 변수에 담아 grep (파이프 + pipefail 은 grep -q 가 일찍 닫으면 거짓 실패한다)
      if grep -q 'skeleton.web.client-ip.mode' <<<"$LOGS"; then pass "client-ip 를 빼면 가드가 그 이름을 말하며 실패한다"; else fail "client-ip 를 뺀 실패에 그 이름이 없다"; fi
    else fail "client-ip 를 뺐는데 기동이 막히지 않았다"; fi
    docker rm -f "$C" >/dev/null

    C="$RUN-$APP-d3"; CONTAINERS="$CONTAINERS $C"   # TEMPLATE 허락을 빼면 — 예시 약관으로는 prod 가 뜨지 않는다
    # shellcheck disable=SC2046
    docker run -d --name "$C" --network "$NET" $(contract_env) $(base_d_env) -e SKELETON_LEGAL_ACKNOWLEDGE_TEMPLATE=false -e SKELETON_CLIENT_IP_MODE=proxy -e "SKELETON_CLIENT_IP_TRUSTED_PROXIES=$SUBNET,10.99.0.0/16" "$IMG" >/dev/null
    if wait_for 120 stopped "$C"; then
      LOGS="$(docker logs "$C" 2>&1)"
      if grep -q 'skeleton.legal.acknowledge-template' <<<"$LOGS"; then pass "TEMPLATE 허락을 빼면 가드가 말하며 실패한다 (예시 약관 그대로는 prod 불가)"; else fail "TEMPLATE 허락을 뺀 실패에 skeleton.legal.acknowledge-template 가 없다"; echo "$LOGS" | tail -8; fi
    else fail "TEMPLATE 허락을 뺐는데 기동이 막히지 않았다"; fi
    docker rm -f "$C" >/dev/null

    C="$RUN-$APP-d2"; CONTAINERS="$CONTAINERS $C"   # 메일을 빼면
    # shellcheck disable=SC2046
    docker run -d --name "$C" --network "$NET" $(contract_env) $(base_d_env nomail) -e SKELETON_CLIENT_IP_MODE=proxy -e "SKELETON_CLIENT_IP_TRUSTED_PROXIES=$SUBNET,10.99.0.0/16" "$IMG" >/dev/null
    if wait_for 120 stopped "$C"; then
      LOGS="$(docker logs "$C" 2>&1)"
      if grep -q 'mail transport' <<<"$LOGS"; then pass "메일 발송 길을 빼면 가드가 말하며 실패한다"; else fail "메일을 뺀 실패에 mail transport 메시지가 없다"; fi
    else fail "메일을 뺐는데 기동이 막히지 않았다"; fi
    docker rm -f "$C" >/dev/null
  fi
done

echo
if [ "$FAILURES" = 0 ]; then
  echo "✓ all passed (deploy contract: $APPS)"
else
  echo "✗ $FAILURES 개 실패"
fi
if [ "${KEEP:-0}" != 1 ] && [ "${KEEP_IMAGES:-0}" != 1 ]; then
  # shellcheck disable=SC2086
  [ -z "$IMAGES" ] || docker rmi $IMAGES >/dev/null 2>&1
fi
[ "$FAILURES" = 0 ]
