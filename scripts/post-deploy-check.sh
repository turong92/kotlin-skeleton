#!/usr/bin/env bash
# 배포 후 자동 점검 — 배포된 주소에 /health 와 로그인 한 번을 찔러 본다 (docs/deploy.md 「배포 후 자동 점검」).
#   SMOKE_EMAIL=… SMOKE_PASSWORD=… scripts/post-deploy-check.sh <주소> [-H '이름: 값']… [--wait <초>]
#   예) 후보 리비전 태그 주소:  … scripts/post-deploy-check.sh https://candidate---api-xxxx.a.run.app
#       헤더로 라우팅하는 경우: … scripts/post-deploy-check.sh https://api.example.com -H 'X-Revision-Tag: candidate'
# 하는 일: GET /health 가 200 이 될 때까지(--wait 초, 기본 60 — 콜드스타트) → POST /api/v1/auth/login → GET /api/v1/auth/me(받은 토큰으로) → POST /api/v1/auth/logout(실패해도 무시).
# 종료 코드: 0 = 통과 · 1 = 배포가 어긋남(단계 이름과 상태 코드를 출력) · 2 = 사용법 오류(주소 · 환경변수 없음).
# 시험 계정은 미리 만들어 둔다(시드 계정은 로컬 전용이다 — 가입 · 확인으로 만든다). 비밀번호 · 토큰 · 헤더 값은 출력하지도, 명령줄 인자로 넘기지도 않는다(임시 파일 0600).
# native-smoke.sh 는 가짜 결제사 · 메일함이 있는 시험용 서버의 전체 흐름이라 쓰지 않는다 — 이 스크립트는 운영 주소에 읽기 · 로그인만 한다. 필요한 것: curl(7.55+) · python3.
set -uo pipefail

URL=""; WAIT=60; HEADERS=()
while [ $# -gt 0 ]; do
  case "$1" in
    -H|--header) [ $# -ge 2 ] || { echo "$1 에 '이름: 값' 이 필요하다" >&2; exit 2; }; HEADERS+=("$2"); shift 2 ;;
    --wait) [ $# -ge 2 ] || { echo "--wait 에 초가 필요하다" >&2; exit 2; }; WAIT="$2"; shift 2 ;;
    -*) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
    *) [ -z "$URL" ] || { echo "주소는 하나만 받는다: $1" >&2; exit 2; }; URL="${1%/}"; shift ;;
  esac
done
[ -n "$URL" ] || { echo "사용법: SMOKE_EMAIL=… SMOKE_PASSWORD=… $0 <주소> [-H '이름: 값']… [--wait <초>]" >&2; exit 2; }
[[ "$WAIT" =~ ^[0-9]+$ ]] || { echo "--wait 는 정수 초다" >&2; exit 2; }
[ -n "${SMOKE_EMAIL:-}" ] && [ -n "${SMOKE_PASSWORD:-}" ] || { echo "SMOKE_EMAIL · SMOKE_PASSWORD 환경변수(시험 계정)가 필요하다" >&2; exit 2; }

TMP=$(mktemp -d) || exit 2
chmod 700 "$TMP"
trap 'rm -rf "${TMP:?}"' EXIT
: > "$TMP/headers"; chmod 600 "$TMP/headers"
for h in ${HEADERS[@]+"${HEADERS[@]}"}; do printf '%s\n' "$h" >> "$TMP/headers"; done

# req <METHOD> <경로> [본문 파일] [bearer 토큰] → 상태 코드(응답 본문은 $TMP/out). 값은 모두 파일로 넘겨 ps 에 남지 않는다
req() {
  local method=$1 path=$2 body=${3:-} bearer=${4:-}
  cp "$TMP/headers" "$TMP/h"
  [ -z "$bearer" ] || printf 'Authorization: Bearer %s\n' "$bearer" >> "$TMP/h"
  local args=(-sS -m 30 -o "$TMP/out" -w '%{http_code}' -X "$method" "$URL$path" -H @"$TMP/h")
  [ -z "$body" ] || args+=(-H 'Content-Type: application/json' --data-binary @"$body")
  curl "${args[@]}" 2>/dev/null || true
}

FAILS=0
ok() { printf '  ✓ %s\n' "$1"; }
bad() { printf '  ✗ %s\n' "$1" >&2; FAILS=$((FAILS + 1)); }

echo "== 배포 후 점검: $URL"
DEADLINE=$((SECONDS + WAIT))
while :; do
  CODE=$(req GET /health)
  [ "$CODE" = 200 ] && break
  [ "$SECONDS" -lt "$DEADLINE" ] || break
  sleep 1
done
if [ "$CODE" = 200 ]; then ok "/health 200"; else bad "/health 가 200 이 아니다 (마지막 상태 ${CODE:-000}, ${WAIT}초 기다렸다)"; exit 1; fi

python3 -c 'import json,os;print(json.dumps({"email":os.environ["SMOKE_EMAIL"],"password":os.environ["SMOKE_PASSWORD"]}))' > "$TMP/login.json"
CODE=$(req POST /api/v1/auth/login "$TMP/login.json")
if [ "$CODE" != 200 ]; then bad "로그인이 200 이 아니다 (상태 ${CODE:-000}) — 시험 계정 · 비밀번호 · 마이그레이션을 본다"; exit 1; fi
TOKENS=$(python3 -c 'import json,sys
try:
    v=json.load(open(sys.argv[1]))["value"]
    print(v["accessToken"]); print(v.get("refreshToken") or "")
except Exception:
    pass' "$TMP/out")
ACCESS=$(printf '%s\n' "$TOKENS" | sed -n 1p); REFRESH=$(printf '%s\n' "$TOKENS" | sed -n 2p)
if [ -z "$ACCESS" ]; then bad "로그인 응답에 accessToken 이 없다"; exit 1; fi
ok "로그인 200"

CODE=$(req GET /api/v1/auth/me "" "$ACCESS")
if [ "$CODE" = 200 ]; then ok "받은 토큰으로 내 정보 200"; else bad "받은 토큰으로 내 정보가 200 이 아니다 (상태 ${CODE:-000})"; fi

# 점검이 세션을 쌓아 두지 않게 닫는다 — 실패해도 점검 결과와 무관하다
if [ -n "$REFRESH" ]; then
  printf '{"refreshToken":"%s"}' "$REFRESH" > "$TMP/logout.json"
  req POST /api/v1/auth/logout "$TMP/logout.json" "$ACCESS" >/dev/null
fi

[ "$FAILS" -eq 0 ] && { echo "== 통과"; exit 0; }
echo "== 실패 $FAILS" >&2; exit 1
