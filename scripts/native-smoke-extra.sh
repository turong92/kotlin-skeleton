#!/usr/bin/env bash
# 네이티브 이미지 시험 2 — 떠 있는 tools/native-check-extra 서버(JVM 이든 네이티브 바이너리든)에 native-check 가 보지 않는 모듈의 흐름을 실제 HTTP 로 돌린다 (docs/native-image.md).
#   scripts/native-smoke-extra.sh --url http://localhost:18080 --mail http://localhost:18025 --s3-host-port localhost:18333 [--wake-secret <값>] [--ffmpeg yes|no]
# 확인하는 것: 기동 · 가입 · 로그인 → 이미지 처리(storage-image: 업로드 주소 → PUT → 완료 → 변환 작업 → 변환본 내려받기; WebP · CMYK JPEG 를 ImageIO/TwelveMonkeys 로 읽어 JPEG 로 변환,
#   움직이는 GIF 는 앱 안 ffmpeg 가 MP4 표시본으로 — --ffmpeg yes 일 때만 변환을 기대한다, no 면 사본이 쓰이는 것을 확인) → 웹 보상형 광고(세션 → 보상 → point 지갑) → AdMob 보상 콜백(ECDSA 서명 · 중복 · 위조).
# 종료 코드: 0 = 전부 통과 · 1 = 어느 단계가 어긋남. 필요한 것: curl · python3. 도우미 함수 틀은 native-smoke.sh 와 같다(새 프로젝트에는 이 파일이 가지 않는다).
set -uo pipefail

URL=""; MAIL_URL=""; S3_HOST_PORT=""; FFMPEG=no; WAKE_SECRET="native-check-wake-secret-0123456789-abcdefgh"
FIXTURES="$(cd "$(dirname "$0")" && pwd)/native-fixtures"
while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --mail) MAIL_URL="$2"; shift 2 ;;
    --s3-host-port) S3_HOST_PORT="$2"; shift 2 ;;
    --wake-secret) WAKE_SECRET="$2"; shift 2 ;;
    --ffmpeg) FFMPEG="$2"; shift 2 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
[ -n "$URL" ] && [ -n "$MAIL_URL" ] && [ -n "$S3_HOST_PORT" ] || { echo "--url --mail --s3-host-port 가 필요하다" >&2; exit 2; }

FAILS=0
mail_find() {
  python3 - "$MAIL_URL" "$1" "$2" <<'PY'
import json, re, sys, time, urllib.request, urllib.parse
base, to, pattern = sys.argv[1:4]
for _ in range(30):
    try:
        q = urllib.parse.quote(f"to:{to}")
        msgs = json.load(urllib.request.urlopen(f"{base}/api/v1/search?query={q}", timeout=5)).get("messages") or []
        if msgs:
            msg = json.load(urllib.request.urlopen(f"{base}/api/v1/message/{msgs[0]['ID']}", timeout=5))
            m = re.search(pattern, msg.get("Text") or "")
            if m:
                print(m.group(1)); sys.exit(0)
    except Exception:
        pass
    time.sleep(0.5)
PY
}
ok() { printf '  ✓ %s\n' "$1"; }
bad() { printf '  ✗ %s\n' "$1" >&2; FAILS=$((FAILS + 1)); }
json() { python3 -c 'import sys,json
try:
    v=json.load(sys.stdin)
    for k in sys.argv[1].split("."):
        v=v[int(k)] if isinstance(v,list) else v[k]
    print("" if v is None else v)
except Exception:
    print("")' "$1"; }
call() {
  local method=$1 path=$2 bearer=${3:-} body=${4:-}; shift 4 2>/dev/null || shift $#
  local args=(-sS -m 30 -X "$method" "$URL$path" -H 'Content-Type: application/json' -w '\n%{http_code}')
  [ -z "$bearer" ] || args+=(-H "Authorization: Bearer $bearer")
  [ -z "$body" ] || args+=(-d "$body")
  for h in "$@"; do args+=(-H "$h"); done
  curl "${args[@]}"
}
status() { tail -n1; }
body() { sed '$d'; }
expect() {
  local got; got=$(printf '%s' "$3" | status)
  if [ "$got" = "$2" ]; then ok "$1 ($got)"; else bad "$1 — 기대 $2, 실제 $got: $(printf '%s' "$3" | body | head -c 300)"; fi
}
drain() { curl -sS -m 120 -X POST "$URL/internal/jobs/drain" -H "X-Wake-Secret: $WAKE_SECRET" | json processed; }

echo "== 기동"
for _ in $(seq 1 60); do [ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$URL/health")" = 200 ] && break; sleep 1; done
[ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$URL/health")" = 200 ] && ok "/health 200" || { bad "/health 가 200 이 아니다"; exit 1; }

EMAIL="extra-$(date +%s)-$RANDOM@example.com"; PASS="tangerine-42-moon"
echo "== 준비: 가입 · 로그인"
R=$(call POST /api/v1/account/sign-up "" "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"consents\":[{\"type\":\"terms\",\"version\":\"template-1\"},{\"type\":\"privacy\",\"version\":\"template-1\"}]}")
expect "가입 요청" 202 "$R"; SIGNUP=$(printf '%s' "$R" | body | json value.signUpId)
CODE=$(mail_find "$EMAIL" '\b([0-9]{6})\b'); R=$(call POST /api/v1/auth/verify-email "" "{\"signUpId\":\"$SIGNUP\",\"code\":\"$CODE\"}"); expect "이메일 확인" 200 "$R"
R=$(call POST /api/v1/auth/login "" "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}"); expect "로그인" 200 "$R"
ACCESS=$(printf '%s' "$R" | body | json value.accessToken)
ACCOUNT=$(call GET /api/v1/auth/me "$ACCESS" | body | json value.accountId)

# upload_image <파일> <Content-Type> → 키(stdout). 업로드 주소 발급 → S3 에 PUT → 완료 호출
upload_image() {
  local file=$1 ctype=$2 size r put key code
  size=$(wc -c < "$file" | tr -d ' ')
  r=$(call POST /api/v1/storage/presign "$ACCESS" "{\"fileName\":\"$(basename "$file")\",\"contentType\":\"$ctype\",\"sizeBytes\":$size}")
  [ "$(printf '%s' "$r" | status)" = 200 ] || { echo "presign 실패: $(printf '%s' "$r" | body | head -c 200)" >&2; return 1; }
  put=$(printf '%s' "$r" | body | json value.url); key=$(printf '%s' "$r" | body | json value.key)
  local host="${put#*://}"; host="${host%%/*}"
  code=$(curl -sS -m 30 --connect-to "${host%%:*}:${host##*:}:${S3_HOST_PORT}" -X PUT "$put" -H "Content-Type: $ctype" --data-binary "@$file" -o /dev/null -w '%{http_code}')
  [ "$code" = 200 ] || { echo "S3 PUT $code" >&2; return 1; }
  r=$(call POST /api/v1/storage/complete "$ACCESS" "{\"key\":\"$key\"}")
  [ "$(printf '%s' "$r" | status)" = 200 ] || { echo "complete 실패: $(printf '%s' "$r" | body | head -c 300)" >&2; return 1; }
  echo "$key"
}
# image_item <키> → /storage/images 응답의 그 키 항목(JSON 한 줄)
image_item() { call POST /api/v1/storage/images "$ACCESS" "{\"keys\":[\"$1\"]}" | body | python3 -c 'import sys,json; print(json.dumps(json.load(sys.stdin)["value"]["items"][0]))'; }
# fetch_signed <서명 주소> <출력 파일> → 상태 코드. 서명된 호스트(host.docker.internal 등)는 그대로 두고 접속만 호스트의 S3 포트로 돌린다
fetch_signed() {
  local host="${1#*://}"; host="${host%%/*}"
  curl -sS -m 30 --connect-to "${host%%:*}:${host##*:}:${S3_HOST_PORT}" -o "$2" -w '%{http_code}' "$1"
}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

echo "== 이미지 처리 (storage-image + ImageIO/TwelveMonkeys)"
for spec in "photo.webp:image/webp:WebP" "cmyk.jpg:image/jpeg:CMYK JPEG"; do
  F=${spec%%:*}; REST=${spec#*:}; CT=${REST%%:*}; LABEL=${REST#*:}
  KEY=$(upload_image "$FIXTURES/$F" "$CT") && ok "$LABEL 업로드 · 완료" || { bad "$LABEL 업로드 실패: $KEY"; continue; }
  drain >/dev/null
  ITEM=$(image_item "$KEY")
  STATE=$(printf '%s' "$ITEM" | json status)
  [ "$STATE" = READY ] && ok "$LABEL 변환 READY" || { bad "$LABEL 변환이 READY 가 아니다($STATE): $ITEM"; continue; }
  W=$(printf '%s' "$ITEM" | json variants.display.width); H=$(printf '%s' "$ITEM" | json variants.display.height)
  [ "$W" = 320 ] && [ "$H" = 200 ] && ok "$LABEL 표시본 크기 ${W}x${H}" || bad "$LABEL 표시본 크기가 다르다(${W}x${H})"
  [ "$(printf '%s' "$ITEM" | json variants.display.contentType)" = image/jpeg ] && ok "$LABEL 표시본이 JPEG" || bad "$LABEL 표시본 형식이 JPEG 가 아니다"
  for V in thumb display; do
    CODE=$(fetch_signed "$(printf '%s' "$ITEM" | json variants.$V.url)" "$TMP/$F.$V")
    MAGIC=$(head -c 2 "$TMP/$F.$V" | od -An -tx1 | tr -d ' \n')
    [ "$CODE" = 200 ] && [ "$MAGIC" = ffd8 ] && ok "$LABEL $V 내려받기 · JPEG 머리" || bad "$LABEL $V 내려받기 실패($CODE, $MAGIC)"
  done
done

echo "== 움직이는 GIF (앱 안 ffmpeg: ${FFMPEG})"
GKEY=$(upload_image "$FIXTURES/anim.gif" image/gif) && ok "GIF 업로드 · 완료" || bad "GIF 업로드 실패: $GKEY"
drain >/dev/null; drain >/dev/null; drain >/dev/null   # 변환 작업 → 끝나면 ffmpeg 작업이 큐에 들어간다
GITEM=$(image_item "$GKEY"); GSTATE=$(printf '%s' "$GITEM" | json status)
[ "$GSTATE" = READY ] && ok "GIF 사본(표시본) READY" || bad "GIF 가 READY 가 아니다($GSTATE): $GITEM"
ALT=$(printf '%s' "$GITEM" | json variants.display.alternates.0.contentType)
if [ "$FFMPEG" = yes ]; then
  [ "$ALT" = video/mp4 ] && ok "표시본에 video/mp4 가 더해졌다(ffmpeg 가 돌았다)" || bad "video/mp4 대체 형식이 없다(alt='$ALT'): $GITEM"
  if [ "$ALT" = video/mp4 ]; then
    CODE=$(fetch_signed "$(printf '%s' "$GITEM" | json variants.display.alternates.0.url)" "$TMP/anim.mp4")
    [ "$CODE" = 200 ] && [ "$(dd if="$TMP/anim.mp4" bs=1 skip=4 count=4 2>/dev/null)" = ftyp ] && ok "MP4 를 내려받았다(ftyp 머리)" || bad "MP4 내려받기가 이상하다($CODE)"
  fi
else
  [ -z "$ALT" ] && ok "ffmpeg 가 없는 이미지: 변환 없이 사본이 쓰인다" || bad "ffmpeg 없이 대체 형식이 생겼다($ALT)"
fi

echo "== 웹 보상형 광고 (ads-web-rewarded + point)"
BAL0=$(call GET /api/v1/points "$ACCESS" | body | json value.balance)
R=$(call POST /api/v1/ads/web-rewarded/sessions "$ACCESS" '{"purpose":"extra-check"}'); expect "광고 세션 열기" 200 "$R"
SESSION=$(printf '%s' "$R" | body | json value.sessionId)
R=$(call POST "/api/v1/ads/web-rewarded/sessions/$SESSION/grant" "$ACCESS" ""); expect "너무 빠른 보상 요청은 거절" 409 "$R" || true
sleep 2
R=$(call POST "/api/v1/ads/web-rewarded/sessions/$SESSION/grant" "$ACCESS" ""); expect "보상 요청" 200 "$R"
[ "$(printf '%s' "$R" | body | json value.reward.type)" = point ] && ok "보상 종류가 point" || bad "보상 종류가 point 가 아니다: $(printf '%s' "$R" | body | head -c 200)"
BAL1=$(call GET /api/v1/points "$ACCESS" | body | json value.balance)
[ "$BAL1" = "$((BAL0 + 7))" ] && ok "포인트 잔액 +7 ($BAL0 → $BAL1)" || bad "잔액이 맞지 않는다($BAL0 → $BAL1)"
R=$(call POST "/api/v1/ads/web-rewarded/sessions/$SESSION/grant" "$ACCESS" ""); expect "같은 세션 두 번째 보상은 거절" 409 "$R"

echo "== AdMob 보상 콜백 (ads-admob, ECDSA 서명)"
TX="tx-$RANDOM$$"
Q=$(curl -sS "$URL/api/v1/native-check/admob-signed?user=$ACCOUNT&tx=$TX&amount=5" | json query)
CODE=$(curl -sS -m 30 -o /dev/null -w '%{http_code}' "$URL/api/v1/ads/admob/ssv?$Q"); [ "$CODE" = 200 ] && ok "서명한 콜백 수락 (200)" || bad "서명한 콜백이 $CODE"
BAL2=$(call GET /api/v1/points "$ACCESS" | body | json value.balance)
[ "$BAL2" = "$((BAL1 + 5))" ] && ok "포인트 잔액 +5 ($BAL1 → $BAL2)" || bad "AdMob 적립이 맞지 않는다($BAL1 → $BAL2)"
CODE=$(curl -sS -m 30 -o /dev/null -w '%{http_code}' "$URL/api/v1/ads/admob/ssv?$Q"); [ "$CODE" = 200 ] && ok "같은 transaction_id 재전송은 200" || bad "재전송이 $CODE"
[ "$(call GET /api/v1/points "$ACCESS" | body | json value.balance)" = "$BAL2" ] && ok "재전송은 한 번만 적립" || bad "재전송이 두 번 적립됐다"
FORGED=$(printf '%s' "$Q" | sed 's/reward_amount=5/reward_amount=500/')
CODE=$(curl -sS -m 30 -o /dev/null -w '%{http_code}' "$URL/api/v1/ads/admob/ssv?$FORGED"); [ "$CODE" = 400 ] && ok "위조 콜백 거절 (400)" || bad "위조 콜백이 $CODE"

echo
if [ "$FAILS" = 0 ]; then echo "PASS — 전부 통과"; exit 0; else echo "FAIL — $FAILS 단계가 어긋났다" >&2; exit 1; fi
