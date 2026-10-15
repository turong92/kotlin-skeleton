#!/usr/bin/env bash
# 네이티브 이미지 `--full` 시험 — 기본 흐름(scripts/native-smoke.sh)이 못 보는 것을 가짜 상대를 붙여 실제 HTTP 로 돌린다 (docs/native-image.md):
#   메일(SMTP → mailpit: 비밀번호 재설정) · 파일 저장(로컬 S3: 업로드 주소 → PUT → 내려받기) · 소셜 로그인(google · kakao · naver · LINE(OIDC) · X: 응답 JSON 읽기)
#   · 토스(승인 · 웹훅 · 빌링) · Paddle(서명한 웹훅) · SSE 알림.
#   scripts/native-smoke-full.sh --url <앱> --mail <mailpit HTTP> --fakes <scripts/native-fakes.py> --s3-host-port <호스트:포트> [--webhook-token <값>] [--wake-secret <값>] [--paddle-secret <값>]
# 종료 코드: 0 = 전부 통과 · 1 = 어긋남. 필요한 것: curl · python3. 시험 전용 비밀 값만 쓴다(출력하지 않는다).
set -uo pipefail

URL=""; MAIL_URL=""; FAKES=""; S3_HOST_PORT=""
WH_TOKEN="native-check-webhook-token-0123456789"; WAKE_SECRET="native-check-wake-secret-0123456789-abcdefgh"; PADDLE_SECRET="native-paddle-webhook-secret-0123456789"
while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --mail) MAIL_URL="$2"; shift 2 ;;
    --fakes) FAKES="$2"; shift 2 ;;
    --s3-host-port) S3_HOST_PORT="$2"; shift 2 ;;
    --webhook-token) WH_TOKEN="$2"; shift 2 ;;
    --wake-secret) WAKE_SECRET="$2"; shift 2 ;;
    --paddle-secret) PADDLE_SECRET="$2"; shift 2 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
[ -n "$URL" ] && [ -n "$MAIL_URL" ] && [ -n "$FAKES" ] && [ -n "$S3_HOST_PORT" ] || { echo "--url --mail --fakes --s3-host-port 가 모두 필요하다" >&2; exit 2; }

FAILS=0
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
call() { # METHOD path bearer body [header…] → 본문 + 마지막 줄 상태 코드
  local method=$1 path=$2 bearer=${3:-} body=${4:-}; shift 4 2>/dev/null || shift $#
  local args=(-sS -m 30 -X "$method" "$URL$path" -H 'Content-Type: application/json' -w '\n%{http_code}')
  [ -z "$bearer" ] || args+=(-H "Authorization: Bearer $bearer")
  [ -z "$body" ] || args+=(-d "$body")
  for h in "$@"; do args+=(-H "$h"); done
  curl "${args[@]}"
}
status() { tail -n1; }
body() { sed '$d'; }
expect() { local got; got=$(printf '%s' "$3" | status); if [ "$got" = "$2" ]; then ok "$1 ($got)"; else bad "$1 — 기대 $2, 실제 $got: $(printf '%s' "$3" | body | head -c 200)"; fi; }
stat() { curl -s "$FAKES/_stats" | python3 -c 'import sys,json;print(json.load(sys.stdin).get(sys.argv[1],0))' "$1"; }
mail_find() { # <받는 주소> <정규식> [몇 번째로 새 메일인가를 가리는 제목 조각]
  python3 - "$MAIL_URL" "$1" "$2" "${3:-}" <<'PY'
import json, re, sys, time, urllib.request, urllib.parse
base, to, pattern, subject = sys.argv[1:5]
for _ in range(40):
    try:
        msgs = json.load(urllib.request.urlopen(f"{base}/api/v1/search?query={urllib.parse.quote('to:' + to)}", timeout=5)).get("messages") or []
        for m in msgs:
            if subject and subject not in (m.get("Subject") or ""):
                continue
            msg = json.load(urllib.request.urlopen(f"{base}/api/v1/message/{m['ID']}", timeout=5))
            hit = re.search(pattern, msg.get("Text") or "")
            if hit:
                print(hit.group(1)); sys.exit(0)
    except Exception:
        pass
    time.sleep(0.5)
PY
}
wake() { curl -sS -m 60 -X POST "$URL/internal/jobs/drain" -H "X-Wake-Secret: $WAKE_SECRET" -o /dev/null -w '%{http_code}'; }

# 가입한 계정 하나 — 이후 단계가 같이 쓴다
EMAIL="full-$(date +%s)-$RANDOM@example.com"; PASS="tangerine-42-moon"; NEWPASS="a-brand-new-pass-7"
echo "== 준비: 가입 · 로그인"
R=$(call POST /api/v1/account/sign-up "" "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"consents\":[{\"type\":\"terms\",\"version\":\"template-1\"},{\"type\":\"privacy\",\"version\":\"template-1\"}]}")
expect "가입 요청" 202 "$R"; SIGNUP=$(printf '%s' "$R" | body | json value.signUpId)
CODE=$(mail_find "$EMAIL" '\b([0-9]{6})\b'); R=$(call POST /api/v1/auth/verify-email "" "{\"signUpId\":\"$SIGNUP\",\"code\":\"$CODE\"}"); expect "이메일 확인(메일로 받은 코드)" 200 "$R"
R=$(call POST /api/v1/auth/login "" "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}"); expect "로그인" 200 "$R"
ACCESS=$(printf '%s' "$R" | body | json value.accessToken)
ACCOUNT=$(call GET /api/v1/auth/me "$ACCESS" | body | json value.accountId)

echo "== (a) 메일 — 비밀번호 재설정 (SMTP → mailpit)"
R=$(call POST /api/v1/account/password/forgot "" "{\"email\":\"$EMAIL\"}"); expect "재설정 요청" 202 "$R"
TOKEN=$(mail_find "$EMAIL" 'token=([A-Za-z0-9_.~-]+)')
[ -n "$TOKEN" ] && ok "재설정 메일의 링크 토큰을 받았다" || bad "재설정 메일이 없다"
R=$(call POST /api/v1/account/password/reset "" "{\"token\":\"$TOKEN\",\"newPassword\":\"$NEWPASS\"}"); expect "새 비밀번호로 재설정" 204 "$R"
R=$(call POST /api/v1/auth/login "" "{\"email\":\"$EMAIL\",\"password\":\"$NEWPASS\"}"); expect "새 비밀번호로 로그인" 200 "$R"
ACCESS=$(printf '%s' "$R" | body | json value.accessToken)

echo "== (b) 파일 저장 — 업로드 주소 발급 → PUT → 내려받기"
CONTENT="native-file-$RANDOM"
R=$(call POST /api/v1/storage/presign "$ACCESS" "{\"fileName\":\"hello.txt\",\"contentType\":\"text/plain\",\"sizeBytes\":${#CONTENT}}"); expect "업로드 주소 발급" 200 "$R"
PUT_URL=$(printf '%s' "$R" | body | json value.url); KEY=$(printf '%s' "$R" | body | json value.key)
S3_HOST="${PUT_URL#*://}"; S3_HOST="${S3_HOST%%/*}"
# 서명된 주소의 호스트(앱이 아는 S3 주소, 보통 host.docker.internal:포트)를 그대로 두고(서명에 들어간다) 접속만 --s3-host-port(호스트에서 보이는 주소)로 돌린다
S3_NAME="${S3_HOST%%:*}"; S3_PORT_IN_URL="${S3_HOST##*:}"
CONNECT=(--connect-to "${S3_NAME}:${S3_PORT_IN_URL}:${S3_HOST_PORT}")
CODE_PUT=$(curl -sS -m 30 "${CONNECT[@]}" -X PUT "$PUT_URL" -H 'Content-Type: text/plain' --data-binary "$CONTENT" -o /dev/null -w '%{http_code}')
[ "$CODE_PUT" = 200 ] && ok "S3 에 PUT ($CODE_PUT)" || bad "S3 PUT 이 $CODE_PUT"
R=$(call POST /api/v1/storage/presign-download "$ACCESS" "{\"key\":\"$KEY\"}"); expect "내려받기 주소 발급" 200 "$R"
GET_URL=$(printf '%s' "$R" | body | json value.url)
GOT=$(curl -sS -m 30 "${CONNECT[@]}" "$GET_URL")
[ "$GOT" = "$CONTENT" ] && ok "내려받은 내용이 올린 것과 같다" || bad "내려받은 내용이 다르다"

echo "== (c) 소셜 로그인 — 가짜 제공자의 토큰 · 사용자정보 응답을 읽는다"
REDIR="https://native-check.invalid/callback"
for P in google kakao naver; do
  R=$(call POST "/api/v1/auth/social/$P/login" "" "{\"authorizationCode\":\"code-$P\",\"redirectUri\":\"$REDIR\",\"state\":\"st\"}"); expect "$P 로그인" 200 "$R"
  T=$(printf '%s' "$R" | body | json value.accessToken); [ -n "$T" ] && [ "$(call GET /api/v1/auth/me "$T" | status)" = 200 ] && ok "$P 토큰으로 /auth/me" || bad "$P 토큰이 /auth/me 를 못 연다"
done
VERIFIER="native-verifier-0123456789-0123456789-0123456789-abcdefgh"
R=$(call POST /api/v1/auth/social/x/login "" "{\"authorizationCode\":\"code-x\",\"redirectUri\":\"$REDIR\",\"codeVerifier\":\"$VERIFIER\"}"); expect "X 로그인(PKCE)" 200 "$R"
CHALLENGE=$(python3 -c 'import sys,hashlib,base64;print(base64.urlsafe_b64encode(hashlib.sha256(sys.argv[1].encode()).digest()).rstrip(b"=").decode())' "$VERIFIER")
LINE_CODE=$(curl -s "$FAKES/line/authorize?code_challenge=$CHALLENGE&code_challenge_method=S256&nonce=native-nonce-1" | json code)
R=$(call POST /api/v1/auth/social/line/login "" "{\"authorizationCode\":\"$LINE_CODE\",\"redirectUri\":\"$REDIR\",\"codeVerifier\":\"$VERIFIER\",\"nonce\":\"native-nonce-1\"}"); expect "LINE(OIDC) 로그인 — HS256 ID 토큰 검증" 200 "$R"
for n in google.userinfo kakao.me naver.me x.me line.token; do [ "$(stat $n)" -ge 1 ] 2>/dev/null && ok "가짜 $n 를 실제로 불렀다" || bad "가짜 $n 를 부르지 않았다"; done

echo "== (d) Paddle — 거래 만들기 · 서명한 웹훅"
R=$(call POST /api/v1/payments/orders "$ACCESS" '{"itemType":"check","itemRef":"check-1000","quantity":1}' "Idempotency-Key: full-paddle-$RANDOM-$$" "X-Dev-Country: US"); expect "Paddle 주문 만들기(거래 생성)" 201 "$R"
PORDER=$(printf '%s' "$R" | body | json value.order.id); PROVIDER=$(printf '%s' "$R" | body | json value.checkout.provider)
[ "$PROVIDER" = paddle ] && ok "결제사가 paddle" || bad "결제사가 paddle 이 아니다($PROVIDER)"
PTXN=$(printf '%s' "$R" | body | json value.checkout.attributes.transactionId)
[ -n "$PTXN" ] && ok "가짜 Paddle 이 만든 거래 id 를 받았다" || bad "거래 id 가 응답에 없다: $(printf '%s' "$R" | body | head -c 300)"
EVENT=$(python3 - "$PTXN" "$PORDER" "$ACCOUNT" <<'PY'
import json, sys, time
txn, order, account = sys.argv[1:4]
print(json.dumps({"event_id": "evt_native_paddle_%d" % int(time.time() * 1000), "event_type": "transaction.completed", "occurred_at": "2026-10-15T12:00:00Z", "notification_id": "ntf_1", "data": {
    "id": txn, "status": "completed", "currency_code": "KRW", "billed_at": "2026-10-15T12:00:00Z", "details": {"totals": {"currency_code": "KRW", "total": "1000", "tax": "0"}},
    "payments": [{"status": "captured", "method_details": {"type": "card", "card": {"type": "visa", "last4": "4242"}}}],
    "items": [{"price": {"id": "pri_native_check"}, "quantity": 1}], "custom_data": {"orderId": order, "accountId": account}}}))
PY
)
DELIVER=$(python3 -c 'import json,sys;print(json.dumps({"path":sys.argv[1],"secret":sys.argv[2],"body":sys.argv[3]}))' "/api/v1/payments/webhooks/paddle" "$PADDLE_SECRET" "$EVENT")
D=$(curl -sS -m 30 -X POST "$FAKES/paddle/_deliver" -d "$DELIVER" | json status); [ "$D" = 200 ] && ok "서명한 Paddle 웹훅 수신 (200)" || bad "Paddle 웹훅이 $D"
wake >/dev/null
for _ in $(seq 1 20); do S=$(call GET "/api/v1/payments/orders/$PORDER" "$ACCESS" | body | json value.order.status); [ "$S" = PAID ] && break; wake >/dev/null; sleep 0.5; done
[ "$S" = PAID ] && ok "웹훅이 주문을 PAID 로 만들었다" || bad "Paddle 주문이 PAID 가 아니다($S)"
BAD_SIG=$(curl -sS -m 30 -X POST "$URL/api/v1/payments/webhooks/paddle" -H 'Content-Type: application/json' -H 'Paddle-Signature: ts=1;h1=00' -d "$EVENT" -o /dev/null -w '%{http_code}')
[ "$BAD_SIG" != 200 ] && ok "틀린 서명은 거절 ($BAD_SIG)" || bad "틀린 서명을 받아 줬다"

echo "== (e) 토스 — 승인 · 웹훅 · 빌링"
R=$(call POST /api/v1/payments/orders "$ACCESS" '{"itemType":"check","itemRef":"check-1000","quantity":1}' "Idempotency-Key: full-toss-$RANDOM-$$" "X-Dev-Country: JP"); expect "토스 주문 만들기" 201 "$R"
TORDER=$(printf '%s' "$R" | body | json value.order.id); TKEY="tgen_native_pay_$RANDOM"
R=$(call POST "/api/v1/payments/orders/$TORDER/confirm" "$ACCESS" "{\"providerPaymentId\":\"$TKEY\",\"amount\":1000}"); expect "토스 승인(가짜 토스 응답 읽기)" 200 "$R"
[ "$(printf '%s' "$R" | body | json value.status)" = PAID ] && ok "주문이 PAID" || bad "토스 주문이 PAID 가 아니다"
HOOK=$(printf '{"eventType":"PAYMENT_STATUS_CHANGED","createdAt":"2026-10-15T12:00:00.000000","data":{"paymentKey":"%s","orderId":"%s","status":"DONE"}}' "$TKEY" "$TORDER")
C=$(curl -sS -m 30 -X POST "$URL/api/v1/payments/webhooks/toss/$WH_TOKEN" -H 'Content-Type: application/json' -H "tosspayments-webhook-transmission-id: tx-native-$RANDOM" -d "$HOOK" -o /dev/null -w '%{http_code}')
[ "$C" = 200 ] && ok "토스 웹훅 수신 (200)" || bad "토스 웹훅이 $C"
wake >/dev/null
for _ in $(seq 1 20); do [ "$(stat toss.lookup)" -ge 1 ] 2>/dev/null && break; wake >/dev/null; sleep 0.5; done
[ "$(stat toss.lookup)" -ge 1 ] 2>/dev/null && ok "웹훅 뒤 처리 작업이 토스를 조회했다(응답 JSON 읽기)" || bad "토스 조회가 일어나지 않았다"
R=$(call POST /api/v1/payments/subscriptions "$ACCESS" '{"planCode":"basic"}' "Idempotency-Key: full-sub-$RANDOM-$$" "X-Dev-Country: JP"); expect "토스 구독 만들기" 201 "$R"
SUB=$(printf '%s' "$R" | body | json value.subscription.id)
R=$(call POST "/api/v1/payments/subscriptions/$SUB/activate" "$ACCESS" '{"providerPayload":{"authKey":"auth_native_toss"}}'); expect "토스 구독 활성화(빌링키 발급 + 첫 과금)" 200 "$R"
[ "$(printf '%s' "$R" | body | json value.status)" = ACTIVE ] && ok "구독이 ACTIVE" || bad "토스 구독이 ACTIVE 가 아니다: $(printf '%s' "$R" | body | head -c 200)"
for n in toss.confirm toss.billing-issue toss.billing-charge; do [ "$(stat $n)" -ge 1 ] 2>/dev/null && ok "가짜 $n 를 불렀다" || bad "가짜 $n 를 부르지 않았다"; done

echo "== (f) SSE 알림"
SSE_OUT=$(mktemp); NOTE="sse-note-$RANDOM"
curl -sN -m 10 -H "Authorization: Bearer $ACCESS" "$URL/api/v1/notifications/sse?topic=native-check" > "$SSE_OUT" 2>/dev/null &
SSE_PID=$!
sleep 1.5
R=$(call POST /api/v1/native-check/notify "" "{\"note\":\"$NOTE\"}"); expect "알림 발행" 200 "$R"
for _ in $(seq 1 20); do grep -q "$NOTE" "$SSE_OUT" && break; sleep 0.5; done
kill "$SSE_PID" 2>/dev/null; wait "$SSE_PID" 2>/dev/null
grep -q "$NOTE" "$SSE_OUT" && ok "구독 중인 SSE 연결로 알림이 도착했다" || bad "SSE 로 알림이 오지 않았다: $(head -c 200 "$SSE_OUT")"
rm -f "$SSE_OUT"

echo
if [ "$FAILS" = 0 ]; then echo "PASS(full) — 전부 통과"; exit 0; else echo "FAIL(full) — $FAILS 단계가 어긋났다" >&2; exit 1; fi
