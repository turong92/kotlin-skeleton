#!/usr/bin/env bash
# 네이티브 이미지 시험 — 떠 있는 tools/native-check 서버(JVM 이든 네이티브 바이너리든)에 핵심 흐름을 실제 HTTP 로 돌린다 (docs/native-image.md).
#   scripts/native-smoke.sh --url http://localhost:18080 --mail http://localhost:18025 [--webhook-token <값>] [--wake-secret <값>]
#   (메일은 실제 SMTP 로 나가 mailpit 이 받는다 — --mail 은 mailpit 의 HTTP 주소. scripts/native-test.sh 가 mailpit 을 올린다)
# 확인하는 것: 기동(/health) · 가입(메일 확인 코드) · 로그인 · 토큰 갱신(재사용 거절) · 단건 결제(가짜 결제사) · 구독 시작 · 작업 큐 일감 처리(요청 구동: 깨우기 주소) · 웹훅 수신(재전송 중복) · OpenAPI 문서.
# 종료 코드: 0 = 전부 통과 · 1 = 어느 단계가 어긋남(단계 이름을 출력). 비밀 값은 출력하지 않는다. 필요한 것: curl · python3.
set -uo pipefail

URL=""; MAIL_URL=""; WH_TOKEN="native-check-webhook-token-0123456789"; WAKE_SECRET="native-check-wake-secret-0123456789-abcdefgh"
while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --webhook-token) WH_TOKEN="$2"; shift 2 ;;
    --wake-secret) WAKE_SECRET="$2"; shift 2 ;;
    --mail) MAIL_URL="$2"; shift 2 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
[ -n "$URL" ] || { echo "--url 이 필요하다" >&2; exit 2; }
[ -n "$MAIL_URL" ] || { echo "--mail(mailpit 주소)이 필요하다" >&2; exit 2; }

FAILS=0
# mail_find <받는 주소> <정규식(첫 그룹)> — mailpit 에서 그 주소로 온 가장 최근 메일 본문(text)의 첫 일치. 최대 15초 기다린다
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
# json <path> — stdin 의 JSON 에서 점 경로 값을 꺼낸다 (없으면 빈 문자열)
json() { python3 -c 'import sys,json
try:
    v=json.load(sys.stdin)
    for k in sys.argv[1].split("."):
        v=v[int(k)] if isinstance(v,list) else v[k]
    print("" if v is None else v)
except Exception:
    print("")' "$1"; }
# call <METHOD> <path> [bearer] [body] [extra header…] → 본문 + 마지막 줄에 상태 코드
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
expect() { # expect <단계> <기대 상태> <실제 응답>
  local got; got=$(printf '%s' "$3" | status)
  if [ "$got" = "$2" ]; then ok "$1 ($got)"; else bad "$1 — 기대 $2, 실제 $got: $(printf '%s' "$3" | body | head -c 200)"; fi
}

echo "== 기동"
for _ in $(seq 1 60); do [ "$(curl -s -o /dev/null -w '%{http_code}' "$URL/health")" = 200 ] && break; sleep 1; done
[ "$(curl -s -o /dev/null -w '%{http_code}' "$URL/health")" = 200 ] && ok "/health 200" || { bad "/health 가 200 이 아니다"; exit 1; }

EMAIL="smoke-$(date +%s)-$RANDOM@example.com"; PASS="tangerine-42-moon"
echo "== 가입 · 로그인 · 갱신"
R=$(call POST /api/v1/account/sign-up "" "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"consents\":[{\"type\":\"terms\",\"version\":\"template-1\"},{\"type\":\"privacy\",\"version\":\"template-1\"}]}")
expect "가입 요청" 202 "$R"
SIGNUP=$(printf '%s' "$R" | body | json value.signUpId)
CODE=$(mail_find "$EMAIL" '\b([0-9]{6})\b'); [ -n "$CODE" ] && ok "확인 메일(SMTP → mailpit)을 받았다" || bad "확인 메일이 없다"
R=$(call POST /api/v1/auth/verify-email "" "{\"signUpId\":\"$SIGNUP\",\"code\":\"$CODE\"}"); expect "이메일 확인" 200 "$R"
R=$(call POST /api/v1/auth/login "" "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}"); expect "로그인" 200 "$R"
ACCESS=$(printf '%s' "$R" | body | json value.accessToken); REFRESH=$(printf '%s' "$R" | body | json value.refreshToken)
R=$(call GET /api/v1/auth/me "$ACCESS"); expect "내 정보" 200 "$R"
R=$(call POST /api/v1/auth/refresh "" "{\"refreshToken\":\"$REFRESH\"}"); expect "토큰 갱신" 200 "$R"
NEXT=$(printf '%s' "$R" | body | json value.refreshToken)
[ -n "$NEXT" ] && [ "$NEXT" != "$REFRESH" ] && ok "새 토큰이 다르다" || bad "갱신이 같은 토큰을 돌려줬다"
R=$(call POST /api/v1/auth/refresh "" "{\"refreshToken\":\"$REFRESH\"}"); expect "옛 토큰 재사용 거절" 401 "$R"
R=$(call POST /api/v1/auth/login "" "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}"); ACCESS=$(printf '%s' "$R" | body | json value.accessToken)
R=$(call POST /api/v1/auth/login "" '{"email":"","password":""}'); expect "빈 로그인은 검증 오류" 400 "$R"

echo "== 단건 결제 (가짜 결제사)"
R=$(call POST /api/v1/payments/orders "$ACCESS" '{"itemType":"check","itemRef":"check-1000","quantity":1}' "Idempotency-Key: smoke-order-$RANDOM-$$")
expect "주문 만들기" 201 "$R"
ORDER=$(printf '%s' "$R" | body | json value.order.id)
PKEY="pay_smoke_$RANDOM$$"
R=$(call POST /api/v1/native-check/authenticate "" "{\"paymentKey\":\"$PKEY\",\"orderId\":\"$ORDER\",\"amount\":1000}"); expect "결제창 인증 흉내" 200 "$R"
R=$(call POST "/api/v1/payments/orders/$ORDER/confirm" "$ACCESS" "{\"providerPaymentId\":\"$PKEY\",\"amount\":1000}"); expect "승인" 200 "$R"
[ "$(printf '%s' "$R" | body | json value.status)" = PAID ] && ok "주문이 PAID" || bad "주문이 PAID 가 아니다"

echo "== 구독 시작"
R=$(call GET /api/v1/payments/subscriptions/plans "$ACCESS"); expect "플랜 목록" 200 "$R"
R=$(call POST /api/v1/payments/subscriptions "$ACCESS" '{"planCode":"basic"}' "Idempotency-Key: smoke-sub-$RANDOM-$$"); expect "구독 만들기" 201 "$R"
SUB=$(printf '%s' "$R" | body | json value.subscription.id)
R=$(call POST "/api/v1/payments/subscriptions/$SUB/activate" "$ACCESS" '{"providerPayload":{"authKey":"auth_smoke"}}'); expect "구독 활성화(첫 결제)" 200 "$R"
[ "$(printf '%s' "$R" | body | json value.status)" = ACTIVE ] && ok "구독이 ACTIVE" || bad "구독이 ACTIVE 가 아니다"

echo "== 작업 큐 (요청 구동 — 폴링 스레드 없이 깨우기 주소가 부를 때만 처리한다)"
WAKE=/internal/jobs/drain
R=$(curl -sS -m 30 -X POST "$URL$WAKE" -w '\n%{http_code}'); expect "깨우기: 비밀값 없으면 401" 401 "$R"
R=$(curl -sS -m 30 -X POST "$URL$WAKE" -H 'X-Wake-Secret: wrong' -w '\n%{http_code}'); expect "깨우기: 틀린 비밀값은 401" 401 "$R"
NOTE="smoke-$RANDOM"
R=$(call POST /api/v1/native-check/jobs "" "{\"note\":\"$NOTE\"}"); expect "일감 넣기" 200 "$R"
sleep 2
[ "$(curl -s "$URL/api/v1/native-check/jobs/handled" | json lastNote)" != "$NOTE" ] && ok "깨우기 전에는 처리되지 않았다(폴링 없음)" || bad "깨우기 전에 처리됐다 — 폴링 스레드가 돈다"
R=$(curl -sS -m 60 -X POST "$URL$WAKE" -H "X-Wake-Secret: $WAKE_SECRET" -w '\n%{http_code}'); expect "깨우기" 200 "$R"
[ "$(printf '%s' "$R" | body | json processed)" -ge 1 ] 2>/dev/null && ok "깨우기가 일감을 처리했다" || bad "깨우기가 일감을 처리하지 못했다: $(printf '%s' "$R" | body | head -c 200)"
[ "$(printf '%s' "$R" | body | json tasks.0.ok)" = True ] && ok "주기 작업(계정 삭제 정리)을 깨웠다" || bad "주기 작업 결과가 이상하다"
GOT=$(curl -s "$URL/api/v1/native-check/jobs/handled" | json lastNote)
[ "$GOT" = "$NOTE" ] && ok "일감 본문(JSON)이 처리기까지 닿았다" || bad "처리기가 본문을 읽지 못했다 (lastNote=$GOT)"

echo "== 웹훅 수신"
WH="/api/v1/payments/webhooks/scripted/$WH_TOKEN"
HOOK_BODY=$(printf 'eventType=PAYMENT_STATUS_CHANGED\npaymentKey=%s\norderId=%s\nstatus=DONE\n' "$PKEY" "$ORDER")
EVT="evt-smoke-$RANDOM$$"
R=$(curl -sS -m 30 -X POST "$URL$WH" -H "x-scripted-delivery-id: $EVT" -H 'Content-Type: text/plain' --data-binary "$HOOK_BODY" -w '\n%{http_code}'); expect "웹훅 수신" 200 "$R"
R=$(curl -sS -m 30 -X POST "$URL$WH" -H "x-scripted-delivery-id: $EVT" -H 'Content-Type: text/plain' --data-binary "$HOOK_BODY" -w '\n%{http_code}'); expect "같은 전달 재전송(중복)" 200 "$R"
R=$(curl -sS -m 30 -X POST "$URL/api/v1/payments/webhooks/scripted/wrong-token" -H 'Content-Type: text/plain' --data-binary "$HOOK_BODY" -w '\n%{http_code}'); expect "틀린 비밀 조각은 404" 404 "$R"

echo "== 문서"
R=$(call GET /api/v1/docs); expect "OpenAPI JSON" 200 "$R"

echo
if [ "$FAILS" = 0 ]; then echo "PASS — 전부 통과"; exit 0; else echo "FAIL — $FAILS 단계가 어긋났다" >&2; exit 1; fi
