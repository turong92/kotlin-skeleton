#!/usr/bin/env bash
# 진짜 소셜 로그인 · 진짜 메일 시험 전 점검 (docs/real-provider-setup.md §6). 사람의 클릭이 필요 없는 데까지만 확인하고 ✓ / ✗ / · 줄로 말한다.
#
#   scripts/check-real-providers.sh                          # .env.local 의 모양 + 제공자 서버가 client id/secret 을 받아 주는지(가짜 코드로 한 번 두드려 본다)
#   scripts/check-real-providers.sh --send-test-mail me@gmail.com   # SMTP 가 채워져 있으면 시험 메일 한 통 (curl 로 보낸다)
#   scripts/check-real-providers.sh --base-url http://localhost:8080  # 떠 있는 백엔드의 GET /api/v1/auth/methods 와 맞는지도 본다 (기본: 백엔드가 있으면 본다)
#   ENV_FILE=other.env scripts/check-real-providers.sh
#
# 끝날 때 ✗ 가 하나라도 있으면 종료 코드 1. 비밀 값은 출력하지 않는다 (client id 는 앞 6글자만).
# 시험용: CHECK_GOOGLE_TOKEN_URL · CHECK_KAKAO_TOKEN_URL · CHECK_GOOGLE_DISCOVERY_URL 로 주소를 바꿀 수 있다.
set -uo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-.env.local}"; BASE_URL="${BASE_URL:-http://localhost:${SERVER_PORT:-8080}}"; TEST_MAIL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --send-test-mail) TEST_MAIL="${2:?--send-test-mail <주소>}"; shift 2 ;;
    --base-url) BASE_URL="${2:?--base-url <주소>}"; shift 2 ;;
    --env-file) ENV_FILE="${2:?--env-file <파일>}"; shift 2 ;;
    --self-test) exec bash scripts/check-real-providers.d/self-test.sh ;;
    -h|--help) sed -n 2,11p "$0"; exit 0 ;;
    *) echo "usage: $0 [--send-test-mail <주소>] [--base-url <주소>] [--env-file <파일>]" >&2; exit 2 ;;
  esac
done

BAD=0
ok()   { echo "  ✓ $*"; }
bad()  { echo "  ✗ $*"; BAD=$((BAD+1)); }
note() { echo "  · $*"; }
head6() { printf '%s…' "${1:0:6}"; }

if [ -f "$ENV_FILE" ]; then
  set -a; . "$ENV_FILE"; set +a
  echo "환경 파일: $ENV_FILE"
else
  echo "환경 파일 $ENV_FILE 없음 — 현재 셸 환경만 본다 (.env.example 을 .env.local 로 복사해 채운다)"
fi

P=SKELETON_AUTH_SOCIAL_PROVIDERS
val() { local n="${P}_$1_$2"; printf '%s' "${!n:-}"; }

# 가짜 코드로 토큰 엔드포인트를 두드린다 — 응답 본문에서 error 코드를 뽑는다 (client id/secret 이 틀리면 invalid_client 류, 맞으면 invalid_grant)
probe_token() {   # <url> <client_id> <client_secret> <redirect_uri>
  curl -sS -m 15 -X POST "$1" -H 'Content-Type: application/x-www-form-urlencoded;charset=utf-8' \
    --data-urlencode grant_type=authorization_code --data-urlencode "client_id=$2" --data-urlencode "client_secret=$3" \
    --data-urlencode "redirect_uri=$4" --data-urlencode code=check-real-providers-dummy-code -w '\n%{http_code}' 2>&1
}
interpret() {   # <label> <probe output>
  local body status err
  status="$(printf '%s' "$2" | tail -n1)"; body="$(printf '%s' "$2" | sed '$d')"
  err="$(printf '%s' "$body" | grep -oE '"error"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed -E 's/.*"([^"]*)"$/\1/')"
  local code; code="$(printf '%s' "$body" | grep -oE '"error_code"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed -E 's/.*"([^"]*)"$/\1/')"
  case "$err" in
    invalid_grant|bad_verification_code) ok "$1: 제공자가 client id/secret 을 받아들였다 (가짜 코드라 $err 로 거절 — 정상${code:+, $code})" ;;
    invalid_client|unauthorized_client|KOE*) bad "$1: client id 또는 client secret 이 틀렸다 ($err${code:+ $code}, HTTP $status) — 콘솔에서 값을 다시 복사한다" ;;
    redirect_uri_mismatch|invalid_request) bad "$1: 요청이 거절됐다 ($err${code:+ $code}, HTTP $status) — REDIRECT_URI 를 콘솔 등록값과 비교한다" ;;
    "") if [ "$status" = 000 ] || [ -z "$status" ]; then bad "$1: 제공자 서버에 닿지 못했다 (네트워크): $(printf '%s' "$body" | head -c 120)"; else note "$1: 해석하지 못한 응답 HTTP $status — $(printf '%s' "$body" | head -c 160)"; fi ;;
    *) note "$1: 응답 error=$err${code:+ $code} (HTTP $status) — 의미를 모르겠다, 확인 필요" ;;
  esac
}

shape_redirect() {   # <label> <uri>
  local uri="$2"
  [ -n "$uri" ] || { note "$1: REDIRECT_URI 가 비어 있다 — 프런트가 자기 주소 + /auth/callback 으로 정한다(로그인). 값을 두면 /auth/methods 에 보이고 이 점검이 글자 그대로 비교한다"; return; }
  case "$uri" in
    http://localhost*|http://127.0.0.1*|https://*) ok "$1: REDIRECT_URI 형식 ($uri)" ;;
    *) bad "$1: REDIRECT_URI 는 https:// (로컬은 http://localhost) 로 시작해야 한다: $uri" ;;
  esac
  case "$uri" in *' '*|*[[:cntrl:]]*) bad "$1: REDIRECT_URI 에 공백이 있다";; esac
  case "$uri" in */) bad "$1: REDIRECT_URI 끝의 / — 제공자는 글자 그대로 비교한다. 프런트의 값(/auth/callback)과 같은지 본다";; esac
  case "$uri" in */auth/callback) ;; *) note "$1: 경로가 /auth/callback 이 아니다 — 프런트 라우트가 다르면 맞다. 연결 · 다시 인증은 /account/link-callback 도 콘솔에 등록해야 한다" ;; esac
}

echo; echo "== 소셜 제공자"
ANY=0
for X in GOOGLE KAKAO NAVER; do
  x="$(printf '%s' "$X" | tr 'A-Z' 'a-z')"; en="$(val $X ENABLED)"; cid="$(val $X CLIENT_ID)"; sec="$(val $X CLIENT_SECRET)"; red="$(val $X REDIRECT_URI)"
  if [ "$en" != true ]; then
    if [ -n "$cid$sec" ]; then bad "$x: 키는 있는데 ${P}_${X}_ENABLED=true 가 없다 — 꺼진 채 시작한다"; else note "$x: 꺼짐 (비어 있음)"; fi
    continue
  fi
  ANY=1; echo " $x"
  [ -n "$cid" ] && ok "$x: CLIENT_ID 있음 ($(head6 "$cid"))" || bad "$x: CLIENT_ID 가 비었다 — 켜져 있으면 기동 실패 (client id must not be blank)"
  [ -n "$sec" ] && ok "$x: CLIENT_SECRET 있음" || bad "$x: CLIENT_SECRET 이 비었다 — 켜져 있으면 기동 실패"
  case "$X:$cid" in
    GOOGLE:*.apps.googleusercontent.com) ok "google: client id 모양 (…apps.googleusercontent.com)" ;;
    GOOGLE:?*) bad "google: client id 가 *.apps.googleusercontent.com 모양이 아니다 (웹 애플리케이션 유형의 OAuth 클라이언트 ID 를 복사했나?)" ;;
    KAKAO:????????????????????????????????) ok "kakao: REST API 키 모양 (32자)" ;;
    KAKAO:?*) bad "kakao: CLIENT_ID 는 'REST API 키' 32자다 (네이티브 · JavaScript · Admin 키가 아니다)" ;;
  esac
  shape_redirect "$x" "$red"
  [ -n "$cid" ] && [ -n "$sec" ] || continue
  case "$X" in
    GOOGLE)
      disc="${CHECK_GOOGLE_DISCOVERY_URL:-https://accounts.google.com/.well-known/openid-configuration}"
      d="$(curl -sS -m 10 "$disc" 2>&1 || true)"
      te="$(printf '%s' "$d" | grep -oE '"token_endpoint"[^,]*' | sed -E 's/.*: *"([^"]*)"/\1/')"; ue="$(printf '%s' "$d" | grep -oE '"userinfo_endpoint"[^,]*' | sed -E 's/.*: *"([^"]*)"/\1/')"
      [ "$te" = https://oauth2.googleapis.com/token ] && ok "google: 디스커버리의 token_endpoint = 스켈레톤이 쓰는 주소" || note "google: 디스커버리 token_endpoint='$te' (스켈레톤: https://oauth2.googleapis.com/token)"
      [ "$ue" = https://openidconnect.googleapis.com/v1/userinfo ] && ok "google: 디스커버리의 userinfo_endpoint = 스켈레톤이 쓰는 주소" || note "google: 디스커버리 userinfo_endpoint='$ue' (스켈레톤: https://openidconnect.googleapis.com/v1/userinfo)"
      interpret google "$(probe_token "${CHECK_GOOGLE_TOKEN_URL:-https://oauth2.googleapis.com/token}" "$cid" "$sec" "${red:-http://localhost:5173/auth/callback}")" ;;
    KAKAO) interpret kakao "$(probe_token "${CHECK_KAKAO_TOKEN_URL:-https://kauth.kakao.com/oauth/token}" "$cid" "$sec" "${red:-http://localhost:5173/auth/callback}")" ;;
    NAVER) note "naver: 서버 두드리기는 하지 않는다 (토큰 요청에 state 가 필요하다 — docs/real-provider-setup.md §3)" ;;
  esac
done
[ "$ANY" = 1 ] || note "켜진 제공자가 없다 — 이 상태로 시작하면 오늘과 똑같이 소셜 버튼이 없다"

echo; echo "== 떠 있는 백엔드 ($BASE_URL/api/v1/auth/methods)"
M="$(curl -sS -m 5 "$BASE_URL/api/v1/auth/methods" 2>/dev/null || true)"
if [ -z "$M" ]; then
  note "백엔드가 떠 있지 않다 — scripts/dev-sample.sh 로 띄운 뒤 다시 실행하면 이 구역도 본다"
else
  for X in GOOGLE KAKAO NAVER; do
    x="$(printf '%s' "$X" | tr 'A-Z' 'a-z')"; [ "$(val $X ENABLED)" = true ] || continue
    if printf '%s' "$M" | grep -q "\"provider\":\"$x\""; then
      ok "$x: /auth/methods 에 나온다"
      red="$(val $X REDIRECT_URI)"
      [ -z "$red" ] || { printf '%s' "$M" | grep -qF "\"redirectUri\":\"$red\"" && ok "$x: redirectUri 가 환경 파일과 같다" || bad "$x: /auth/methods 의 redirectUri 가 환경 파일 값($red)과 다르다 — 백엔드를 다시 시작했나?"; }
    else bad "$x: 켰는데 /auth/methods 에 없다 — 백엔드를 이 환경 파일로 다시 시작해야 한다 (기동 로그 'account sign-in methods' 줄을 본다)"; fi
  done
fi

echo; echo "== 메일"
HOST="${SPRING_MAIL_HOST:-}"; PORT="${SPRING_MAIL_PORT:-}"; FROM="${SKELETON_NOTIFICATION_MAIL_FROM:-}"
if [ -z "$HOST" ]; then
  note "SPRING_MAIL_HOST 가 비어 있다 — 로컬 mailpit(http://localhost:8025)으로 간다. 진짜 메일 시험이면 §4 의 값을 채운다"
else
  [ "${SKELETON_NOTIFICATION_MAIL_ENABLED:-}" = true ] && ok "SKELETON_NOTIFICATION_MAIL_ENABLED=true" || bad "SKELETON_NOTIFICATION_MAIL_ENABLED=true 가 없다 — 발송기 빈이 만들어지지 않아 메일은 로그로만 간다"
  [ -n "$FROM" ] && ok "FROM: $FROM" || bad "SKELETON_NOTIFICATION_MAIL_FROM 이 비었다 — 켜져 있으면 기동 실패"
  case "$FROM" in *'<'*'@'*'>'*|?*@?*) ;; ?*) bad "FROM 이 이메일 모양이 아니다: $FROM" ;; esac
  [ -n "$PORT" ] && ok "포트: $PORT" || bad "SPRING_MAIL_PORT 가 비었다 (587 STARTTLS · 465 SSL · 2525)"
  [ -n "${SPRING_MAIL_USERNAME:-}" ] && ok "USERNAME 있음" || note "USERNAME 없음 — 인증 없는 릴레이가 아니면 보내지지 않는다"
  [ -n "${SPRING_MAIL_PASSWORD:-}" ] && ok "PASSWORD 있음" || note "PASSWORD 없음"
  if [ "${SPRING_MAIL_USERNAME:-}" != "" ] && [ "${SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH:-}" != true ]; then bad "USERNAME 이 있는데 SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true 가 없다 — 인증을 하지 않고 보낸다 (대부분 거절)"; fi
  if [ "$PORT" = 587 ] && [ "${SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE:-}" != true ]; then bad "587 인데 SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true 가 없다 — 암호화 없이 시도한다"; fi
  if [ "$PORT" = 465 ] && [ "${SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE:-}" != true ] && [ "${SPRING_MAIL_PROTOCOL:-}" != smtps ]; then bad "465 는 암시적 SSL — SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE=true 가 필요하다"; fi
  if [ -n "$PORT" ]; then (exec 3<>"/dev/tcp/$HOST/$PORT") 2>/dev/null && ok "$HOST:$PORT 에 TCP 로 닿는다" || bad "$HOST:$PORT 에 닿지 못했다 (호스트 · 포트 · 방화벽 — 가정용 망은 25 번 포트를 막는 경우가 많다)"; fi
  dom="${FROM##*@}"; dom="${dom%%>*}"
  if [ -n "$dom" ] && command -v dig >/dev/null 2>&1 && [ "$dom" != "${dom%.*}" ]; then
    dig +short TXT "$dom" 2>/dev/null | grep -qi 'v=spf1' && ok "$dom: SPF TXT 레코드가 있다" || bad "$dom: SPF(v=spf1) TXT 레코드가 없다 — 보내는 서비스의 안내대로 추가한다"
    dig +short TXT "_dmarc.$dom" 2>/dev/null | grep -qi 'v=DMARC1' && ok "$dom: DMARC TXT 레코드가 있다" || note "$dom: _dmarc TXT 가 없다 — 없어도 보내지만 Gmail · Yahoo 의 대량 발송 기준은 요구한다 (§4)"
    note "$dom: DKIM 은 서비스마다 선택자 이름이 달라 여기서는 확인하지 않는다 — 서비스 대시보드의 'Verified' 표시를 본다"
  fi
fi
if [ -n "$TEST_MAIL" ]; then
  if [ -z "$HOST" ] || [ -z "$PORT" ] || [ -z "$FROM" ]; then bad "시험 메일: SPRING_MAIL_HOST · PORT · SKELETON_NOTIFICATION_MAIL_FROM 이 있어야 보낸다"
  else
    addr="${FROM##*<}"; addr="${addr%%>*}"; scheme=smtp; [ "$PORT" = 465 ] && scheme=smtps
    tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
    printf 'From: %s\r\nTo: %s\r\nSubject: =?UTF-8?B?%s?=\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n이 메일은 scripts/check-real-providers.sh 가 보낸 시험 메일입니다. 받았다면 SMTP 설정이 맞습니다.\r\n' \
      "$FROM" "$TEST_MAIL" "$(printf '시험 메일 — check-real-providers' | base64)" > "$tmp"
    extra=(); [ "$scheme" = smtp ] && [ "$HOST" != localhost ] && [ "$HOST" != 127.0.0.1 ] && extra+=(--ssl-reqd)   # 로컬 mailpit 은 TLS 가 없다
    auth=(); [ -n "${SPRING_MAIL_USERNAME:-}" ] && auth=(-u "${SPRING_MAIL_USERNAME}:${SPRING_MAIL_PASSWORD:-}")
    out="$(curl -sS -m 30 "${extra[@]}" "${auth[@]}" "$scheme://$HOST:$PORT" --mail-from "$addr" --mail-rcpt "$TEST_MAIL" -T "$tmp" 2>&1)"
    if [ $? -eq 0 ]; then ok "시험 메일을 $TEST_MAIL 로 보냈다 (SMTP 가 받아들임) — 받은편지함 · 스팸함을 본다. 안 오면 SPF/DKIM 상태를 서비스 대시보드에서 확인"; else bad "시험 메일 실패: $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"; fi
  fi
fi

echo; if [ "$BAD" = 0 ]; then echo "✓ 막히는 것은 찾지 못했다 (사람이 눌러 봐야 아는 것은 docs/real-provider-setup.md §6)"; else echo "✗ $BAD 개 문제"; exit 1; fi
