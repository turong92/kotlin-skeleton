#!/usr/bin/env bash
# scripts/check-real-providers.sh --self-test 가 부른다 — scripts/check-real-providers.sh 의 시험 — 가짜 제공자 서버(python)로 ✓ · ✗ 해석과 "빈 환경 = 문제 없음" 을 확인한다. 네트워크 · 도커 없이 돈다.
set -uo pipefail
cd "$(dirname "$0")/../.."
T="$(mktemp -d)"; trap 'kill "$SRV" 2>/dev/null; rm -rf "$T"' EXIT
cat > "$T/srv.py" <<'PY'
import http.server, sys, os, time
import base64, json
HOOKLOG = sys.argv[2]
MODEFILE = sys.argv[3]   # 한 줄짜리 모드 파일 — 시험이 가짜 토스의 행동을 바꾼다
CALLS = sys.argv[4]      # 가짜 토스가 받은 호출의 기록 (메서드 + 경로)
SEEN_IDEM = set()
class H(http.server.BaseHTTPRequestHandler):
    def _send(self, code, body):
        b = body.encode(); self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def _toss(self, data):
        # 가짜 토스: 시크릿 키가 test_sk_good… 이면 "없는 결제" 404 (취소에 TossPayments-Test-Code 가 있으면 그 코드를 403 으로), test_sk_odd… 이면 모양이 다른 400,
        # test_sk_found… 는 200 으로 없는 결제를 있다고 하고, test_sk_five… 는 500, test_sk_hang… 는 4초 뒤에야 답하고(test_sk_hangcancel… 는 취소만 늦다), test_sk_cancelodd… 는 조회만 맞고 취소가 400,
        # test_sk_replay… 는 같은 멱등 키의 두 번째 취소에 409, test_sk_notestcode… 는 테스트 코드 헤더를 무시한다. 틀린 키(test_sk_checkrealprovidersbogus)는 모드 파일이 bogus200 이면 200, 아니면 401. 그 밖은 401
        with open(CALLS, "a") as f: f.write(self.command + " " + self.path + "\n")
        try: sk = base64.b64decode(self.headers.get("Authorization", "Basic ")[6:]).decode().rstrip(":")
        except Exception: sk = ""
        mode = open(MODEFILE).read().strip() if os.path.exists(MODEFILE) else ""
        is_cancel = self.path.endswith("/cancel"); idem = self.headers.get("Idempotency-Key"); code = self.headers.get("TossPayments-Test-Code")
        gone = '{"code":"NOT_FOUND_PAYMENT","message":"존재하지 않는 결제 입니다."}'
        if sk == "test_sk_checkrealprovidersbogus" and mode == "bogus200": self._send(200, '{"ok":true}')
        elif sk.startswith("test_sk_hangcancel"):
            if is_cancel: time.sleep(4)
            self._send(404, gone)
        elif sk.startswith("test_sk_hang"): time.sleep(4); self._send(404, gone)
        elif sk.startswith("test_sk_five"): self._send(500, '{"code":"FAILED_INTERNAL_SYSTEM_PROCESSING","message":"x"}')
        elif sk.startswith("test_sk_cancelodd"):
            if is_cancel: self._send(400, '{"code":"INVALID_REQUEST","message":"odd cancel"}')
            else: self._send(404, gone)
        elif sk.startswith("test_sk_replay"):
            if is_cancel and idem in SEEN_IDEM: self._send(409, '{"code":"IDEMPOTENT_REQUEST_PROCESSING","message":"again"}')
            else:
                if is_cancel: SEEN_IDEM.add(idem)
                self._send(404, gone)
        elif sk.startswith("test_sk_good") or sk.startswith("test_sk_notestcode"):
            if is_cancel and not idem: self._send(400, '{"code":"INVALID_REQUEST","message":"Idempotency-Key is required"}')
            elif is_cancel and code and sk.startswith("test_sk_good"):
                # 진짜 토스처럼 테스트 코드가 결제 존재 확인보다 먼저 적용된다. 본문에는 결제 정보가 있는 중첩 객체도 둔다 — 최상위 code 만 읽는지 본다
                self._send(403, '{"detail":{"code":"NESTED_NOISE"},"code":"' + code + '","message":"test code"}')
            else: self._send(404, gone)
        elif sk.startswith("test_sk_odd"): self._send(400, '{"code":"INVALID_REQUEST","message":"odd"}')
        elif sk.startswith("test_sk_found"): self._send(200, '{"paymentKey":"x","status":"DONE"}')
        else: self._send(401, '{"code":"UNAUTHORIZED_KEY","message":"인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."}')
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); data = self.rfile.read(n).decode()
        if self.path.startswith("/toss/"): return self._toss(data)
        if self.path.startswith("/api/v1/payments/webhooks/"):
            with open(HOOKLOG, "a") as f: f.write(json.dumps({"path": self.path, "tid": self.headers.get("tosspayments-webhook-transmission-id"), "body": data}) + "\n")
            if self.path.endswith("/toss/tok123"): return self._send(200, '{"meta":{}}')
            return self._send(404, '{"code":"PAYMENT.WEBHOOK_NOT_FOUND"}')
        if self.path.startswith("/line"):
            # 실측한 LINE 의 모양: 틀린 키에도 가짜 코드면 invalid_grant (client_secret=wrong 만 invalid_client 로 흉내 낸다)
            if "client_secret=wrong" in data: self._send(400, '{"error":"invalid_client"}')
            else: self._send(400, '{"error":"invalid_grant","error_description":"invalid authorization code"}')
        elif self.path.startswith("/x"):
            import base64
            auth = self.headers.get("Authorization", "")
            if auth == "Basic " + base64.b64encode(b"xid:good").decode(): self._send(400, '{"error":"invalid_request","error_description":"Value passed for the authorization code was invalid."}')
            else: self._send(400, '{"error":"invalid_client","error_description":"Value passed for the client id was invalid."}')
        elif self.path.startswith("/naver"):
            # Naver 는 토큰 요청에 state 를 받는다 — 빠졌으면 state_missing 으로 돌려 스크립트가 state 를 싣는지 본다
            if "state=" not in data: self._send(400, '{"error":"state_missing","error_description":"no state"}')
            elif "client_secret=wrong" in data: self._send(401, '{"error":"invalid_client","error_description":"Unauthorized"}')
            else: self._send(200, '{"error":"invalid_request","error_description":"fake-naver says: no valid data in session"}')
        elif "client_secret=koe004" in data: self._send(400, '{"error":"invalid_client","error_code":"KOE004","error_description":"To use Kakao account login, user management has to be configured."}')
        elif "client_secret=koe006" in data: self._send(400, '{"error":"invalid_request","error_code":"KOE006","error_description":"Redirect URI mismatch"}')
        elif "client_secret=koe010" in data: self._send(401, '{"error":"invalid_client","error_code":"KOE010","error_description":"Bad client credentials"}')
        elif "client_secret=koe101" in data: self._send(401, '{"error":"invalid_client","error_code":"KOE101","error_description":"Not exist client_id"}')
        elif "client_secret=koe320" in data: self._send(400, '{"error":"invalid_grant","error_code":"KOE320","error_description":"authorization code not found"}')
        elif "client_secret=koe999" in data: self._send(400, '{"error":"server_error","error_code":"KOE999","error_description":"something new"}')
        elif "client_secret=echoid" in data:
            import re as _re
            cid = _re.search(r"client_id=([^&]+)", data).group(1)
            self._send(401, '{"error":"invalid_client","error_description":"unknown client ' + cid + '"}')
        elif "client_secret=good" in data: self._send(400, '{"error":"invalid_grant","error_description":"Bad Request"}')
        elif "client_secret=mismatch" in data: self._send(400, '{"error":"redirect_uri_mismatch"}')
        else: self._send(401, '{"error":"invalid_client","error_description":"Unauthorized"}')
    def do_GET(self):
        if self.path.startswith("/toss/"): return self._toss("")
        self._send(200, '{"token_endpoint":"https://oauth2.googleapis.com/token","userinfo_endpoint":"https://openidconnect.googleapis.com/v1/userinfo"}')
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
PORT=$((20000 + RANDOM % 10000)); : > "$T/hook.log"; : > "$T/calls.log"; : > "$T/mode"; python3 "$T/srv.py" "$PORT" "$T/hook.log" "$T/mode" "$T/calls.log" & SRV=$!; sleep 1
FAIL=0
expect() { if ! printf '%s' "$OUT" | grep -qF -- "$1"; then echo "FAIL: expected '$1' in:"; printf '%s\n' "$OUT"; FAIL=1; fi; }
run() { OUT="$(env -i PATH="$PATH" HOME="$HOME" ENV_FILE="$T/$1" CHECK_GOOGLE_TOKEN_URL="http://127.0.0.1:$PORT/t" CHECK_KAKAO_TOKEN_URL="http://127.0.0.1:$PORT/t" CHECK_NAVER_TOKEN_URL="http://127.0.0.1:$PORT/naver" CHECK_LINE_TOKEN_URL="http://127.0.0.1:$PORT/line" CHECK_X_TOKEN_URL="http://127.0.0.1:$PORT/x" CHECK_LINE_DISCOVERY_URL="http://127.0.0.1:$PORT/d" CHECK_GOOGLE_DISCOVERY_URL="http://127.0.0.1:$PORT/d" BASE_URL=http://127.0.0.1:1 "${SELF_TEST_BASH:-/bin/bash}" scripts/check-real-providers.sh 2>&1)"; RC=$?; }
G=SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE
printf '' > "$T/empty.env"; run empty.env
expect "켜진 제공자가 없다"; expect "토스페이먼츠: 꺼짐"; [ "$RC" = 0 ] || { echo "FAIL: empty env must exit 0 (got $RC)"; FAIL=1; }
printf '%s_ENABLED=true\n%s_CLIENT_ID=1-a.apps.googleusercontent.com\n%s_CLIENT_SECRET=good\n%s_REDIRECT_URI=https://app.example.com/auth/callback\n' $G $G $G $G > "$T/good.env"; run good.env
expect "제공자가 client id/secret 을 받아들였다"; [ "$RC" = 0 ] || { echo "FAIL: good env must exit 0 (got $RC)"; FAIL=1; }
printf '%s_ENABLED=true\n%s_CLIENT_ID=1-a.apps.googleusercontent.com\n%s_CLIENT_SECRET=wrong\n' $G $G $G > "$T/bad.env"; run bad.env
expect "client id 또는 client secret 이 틀렸다"; [ "$RC" = 1 ] || { echo "FAIL: wrong secret must exit 1 (got $RC)"; FAIL=1; }
printf '%s_ENABLED=true\n%s_CLIENT_ID=1-a.apps.googleusercontent.com\n%s_CLIENT_SECRET=mismatch\n%s_REDIRECT_URI=https://app.example.com/auth/callback/\n' $G $G $G $G > "$T/mm.env"; run mm.env
expect "REDIRECT_URI 를 콘솔 등록값과 비교한다"; expect "끝의 /"
printf '%s_ENABLED=true\n%s_CLIENT_ID=1234567890-secretclientidtail.apps.googleusercontent.com\n%s_CLIENT_SECRET=echoid\n' $G $G $G > "$T/echo.env"; run echo.env
expect "제공자 원문"; expect "unknown client 123456"
if printf '%s' "$OUT" | grep -qF "secretclientidtail"; then echo "FAIL: the client id the provider echoed back was printed in full"; FAIL=1; fi
printf '%s_CLIENT_ID=x\n' $G > "$T/off.env"; run off.env
expect "ENABLED=true 가 없다"
printf '%s_ENABLED=true\n%s_CLIENT_ID=\n%s_CLIENT_SECRET=\n' $G $G $G > "$T/blank.env"; run blank.env
expect "CLIENT_ID 가 비었다"
L=SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE; XX=SKELETON_AUTH_SOCIAL_X
printf '%s_CLIENT_ID=2001234567\n%s_CLIENT_SECRET=0123456789abcdef0123456789abcdef\n%s_REDIRECT_URI=https://app.example.com/auth/callback\n' $L $L $L > "$T/line.env"; run line.env
expect "Channel ID 모양"; expect "토큰 서버에 닿는다"; [ "$RC" = 0 ] || { echo "FAIL: line env must exit 0 (got $RC)"; FAIL=1; }
printf '%s_CLIENT_ID=2001234567\n%s_CLIENT_SECRET=wrong\n' $L $L > "$T/linebad.env"; run linebad.env
expect "client id 또는 secret 이 틀렸다"; [ "$RC" = 1 ] || { echo "FAIL: line wrong secret must exit 1 (got $RC)"; FAIL=1; }
printf '%s_CLIENT_ID=12345\n%s_CLIENT_SECRET=0123456789abcdef0123456789abcdef\n' $L $L > "$T/lineid.env"; run lineid.env
expect "Channel ID(숫자 10자리)"
printf '%s_CLIENT_ID=xid\n%s_CLIENT_SECRET=good\n%s_REDIRECT_URI=http://127.0.0.1:5173/auth/callback\n' $XX $XX $XX > "$T/x.env"; run x.env
expect "제공자가 client id/secret 을 받아들인 것으로 보인다"; [ "$RC" = 0 ] || { echo "FAIL: x env must exit 0 (got $RC)"; FAIL=1; }
printf '%s_CLIENT_ID=xid\n%s_CLIENT_SECRET=nope\n%s_REDIRECT_URI=http://localhost:5173/auth/callback\n' $XX $XX $XX > "$T/xbad.env"; run xbad.env
expect "client id 또는 secret 이 틀렸다"; expect "localhost 를 받지 않는다"; [ "$RC" = 1 ] || { echo "FAIL: x wrong secret must exit 1 (got $RC)"; FAIL=1; }
printf '%s_CLIENT_ID=xid\n' $XX > "$T/xnosecret.env"; run xnosecret.env
expect "CLIENT_SECRET 이 비었다"
K=SKELETON_AUTH_SOCIAL_PROVIDERS_KAKAO; KID=0123456789abcdef0123456789abcdef
kakao() { printf '%s_ENABLED=true\n%s_CLIENT_ID=%s\n%s_CLIENT_SECRET=%s\n%s_REDIRECT_URI=https://app.example.com/auth/callback\n' $K $K $KID $K "$1" $K > "$T/kk.env"; run kk.env; }
kakao koe004; expect "KOE004"; expect "카카오 로그인 사용 설정이 꺼져 있다"; expect "To use Kakao account login, user management has to be configured."; expect "제공자 원문:"; [ "$RC" = 1 ] || { echo "FAIL: KOE004 must exit 1 (got $RC)"; FAIL=1; }
if printf '%s' "$OUT" | grep -qF "client id 또는 client secret 이 틀렸다"; then echo "FAIL: KOE004 must not be reported as a wrong key"; FAIL=1; fi
kakao koe006; expect "KOE006"; expect "Redirect URI 가 등록되지 않았다"
kakao koe010; expect "KOE010"; expect "Client Secret"
kakao koe101; expect "KOE101"; expect "REST API 키"
kakao koe320; expect "KOE320"; expect "정상"; [ "$RC" = 0 ] || { echo "FAIL: KOE320 (fake code) must exit 0 (got $RC)"; FAIL=1; }
kakao koe999; expect "KOE999"; expect "something new"; expect "의미를 모르겠다"
if printf '%s' "$OUT" | grep -qF "koe999"; then echo "FAIL: the secret leaked into the output"; FAIL=1; fi
N=SKELETON_AUTH_SOCIAL_PROVIDERS_NAVER
printf '%s_ENABLED=true\n%s_CLIENT_ID=naverid\n%s_CLIENT_SECRET=nsecret\n%s_REDIRECT_URI=https://app.example.com/auth/callback\n' $N $N $N $N > "$T/naver.env"; run naver.env
expect "fake-naver says: no valid data in session"; expect "서버가 가짜 코드에 답했다"
if printf '%s' "$OUT" | grep -qF "state_missing"; then echo "FAIL: the Naver probe must send state"; FAIL=1; fi
printf '%s_ENABLED=true\n%s_CLIENT_ID=naverid\n%s_CLIENT_SECRET=wrong\n' $N $N $N > "$T/navebad.env"; run navebad.env
expect "naver: client id 또는 client secret 이 틀렸다"; [ "$RC" = 1 ] || { echo "FAIL: naver wrong secret must exit 1 (got $RC)"; FAIL=1; }

# --- 토스페이먼츠 (결제 장부가 기대는 가정) ---------------------------------------------------------------------------------------------
runt() { : > "$T/calls.log"; OUT="$(env -i PATH="$PATH" HOME="$HOME" ENV_FILE="$T/$1" CHECK_TOSS_BASE_URL="http://127.0.0.1:$PORT/toss" CHECK_TOSS_TIMEOUT_SEC="${TT:-30}" "${SELF_TEST_BASH:-/bin/bash}" scripts/check-real-providers.sh --toss-only 2>&1)"; RC=$?; }
# 평소 점검(--toss-only 없이)에서 토스 구역이 딸려 도는 경우 — 소셜 · 메일이 비어 있어도 토스 구역은 돈다
runn() { : > "$T/calls.log"; OUT="$(env -i PATH="$PATH" HOME="$HOME" ENV_FILE="$T/$1" CHECK_TOSS_BASE_URL="http://127.0.0.1:$PORT/toss" BASE_URL=http://127.0.0.1:1 "${SELF_TEST_BASH:-/bin/bash}" scripts/check-real-providers.sh 2>&1)"; RC=$?; }
# 가짜 토스가 받은 호출의 기록 — 경로의 무작위 부분만 가리고 "메서드 + 경로" 그대로 비교한다
calls_normalized() { sed -E 's#orders/chkreal-[0-9]+-[0-9]+#orders/<id>#; s#payments/chkreal_nonexistent_[0-9]+_[0-9]+#payments/<key>#' "$T/calls.log"; }
no_leak() { for needle in "$@"; do if printf '%s' "$OUT" | grep -qF -- "$needle"; then echo "FAIL: '$needle' leaked into the output"; FAIL=1; fi; done; }
TS=TOSS_PAYMENTS_SECRET_KEY; TC=TOSS_PAYMENTS_CLIENT_KEY
SECRET=test_sk_goodSECRETTAIL123; SECRET_B64="$(printf '%s:' "$SECRET" | base64)"
printf '%s=%s\n' $TS "$SECRET" > "$T/toss-noclient.env"; runt toss-noclient.env
expect "시크릿 키 모양 (test_sk_"; expect "NOT_FOUND_PAYMENT"; expect "결제사가 모른다"; expect "확정 거절"; expect "멱등 키"
expect "건너뜀"; expect "클라이언트 키"; expect "가짜 서버 대상"
[ "$RC" = 3 ] || { echo "FAIL: no client key with --toss-only must exit 3 (got $RC)"; FAIL=1; }
no_leak "SECRETTAIL123" "goodSECRET" "$SECRET_B64"
# 호출은 이 여섯뿐이다(메서드 + 경로 — /confirm 같은 돈이 움직이는 호출은 없다): 없는 주문 조회 · 없는 결제 키 조회 · 없는 결제 취소 두 번(같은 멱등 키) · 틀린 키로 조회 · 테스트 코드를 붙인 취소
EXPECTED_CALLS='GET /toss/v1/payments/orders/<id>
GET /toss/v1/payments/<key>
POST /toss/v1/payments/<key>/cancel
POST /toss/v1/payments/<key>/cancel
GET /toss/v1/payments/orders/<id>
POST /toss/v1/payments/<key>/cancel'
[ "$(calls_normalized)" = "$EXPECTED_CALLS" ] || { echo "FAIL: the calls sent to Toss are not exactly the six expected ones:"; calls_normalized; FAIL=1; }
grep -q '/confirm' "$T/calls.log" && { echo "FAIL: a /confirm call (money moves) was sent"; FAIL=1; }
expect "TossPayments-Test-Code"; expect "NOT_ALLOWED_PARTIAL_REFUND"; expect "호출 6번"
if printf '%s' "$OUT" | grep -qF "NESTED_NOISE"; then echo "FAIL: a nested code was read as the response code"; FAIL=1; fi
# 평소 점검에서 클라이언트 키가 없다는 이유만으로는 0 으로 끝난다 (건너뜀 줄은 찍는다) — 종료 코드 3 은 --toss-only 일 때만
runn toss-noclient.env
expect "건너뜀"; [ "$RC" = 0 ] || { echo "FAIL: a normal check without a Toss client key must exit 0 (got $RC)"; FAIL=1; }
printf '%s=%s\n%s=test_ck_goodclientTAIL456\n' $TS "$SECRET" $TC > "$T/toss-client.env"; runt toss-client.env
expect "클라이언트 키 모양 (test_ck_"; expect "사람이 결제창"; [ "$RC" = 0 ] || { echo "FAIL: client key present must exit 0 (got $RC)"; FAIL=1; }
no_leak "SECRETTAIL123" "TAIL456" "$SECRET_B64"
printf 'SKELETON_PAYMENT_TOSS_SECRET_KEY=%s\n' "$SECRET" > "$T/toss-alias.env"; runt toss-alias.env
expect "NOT_FOUND_PAYMENT"   # 모듈 이름의 환경 변수만 있어도 읽는다
expect "꺼진 채 시작한다"; expect "SKELETON_PAYMENT_TOSS_ENABLED=true"   # 키는 있는데 켜는 스위치가 없다
# (printf 서식의 \n 바로 뒤에 SKELETON_ 이름을 붙이지 않는다 — n 이 단어 글자라 찍을 때 접두사 바꾸기(\bSKELETON_)가 그 이름을 못 바꾼다. 이름은 %s 인자로 넘긴다. 한 줄에 둘 이상 있어도 공백 · 줄 처음 뒤의 이름은 모두 바뀐다)
printf 'SKELETON_PAYMENT_TOSS_SECRET_KEY=%s\n' "$SECRET" > "$T/toss-enabled.env"
printf 'SKELETON_PAYMENT_TOSS_ENABLED=true\n' >> "$T/toss-enabled.env"; runt toss-enabled.env
if printf '%s' "$OUT" | grep -qF "꺼진 채 시작한다"; then echo "FAIL: ENABLED=true was set, yet the start-disabled notice was printed"; FAIL=1; fi
# 둘 다 있고 값이 다르면 앱이 모듈 이름을 쓰므로 별칭 쪽을 검사해 초록을 말하면 거짓이다 — ✗ 로 멈추고 토스를 부르지 않는다
printf 'SKELETON_PAYMENT_TOSS_SECRET_KEY=test_sk_rejectedTAIL789\n%s=%s\n' $TS "$SECRET" > "$T/toss-conflict.env"; runt toss-conflict.env
expect "서로 다르게"; expect "SKELETON_PAYMENT_TOSS_SECRET_KEY"; [ "$RC" = 1 ] || { echo "FAIL: two different secret keys must exit 1 (got $RC)"; FAIL=1; }
no_leak "TAIL789" "SECRETTAIL123"
[ ! -s "$T/calls.log" ] || { echo "FAIL: Toss was called although the two key names disagree"; FAIL=1; }
# 둘 다 있고 값이 같으면 문제 없다 / 모듈 이름이 이긴다: 모듈 이름은 좋은 키, 별칭은 비었다
printf 'SKELETON_PAYMENT_TOSS_SECRET_KEY=%s\n%s=%s\n' "$SECRET" $TS "$SECRET" > "$T/toss-same.env"; runt toss-same.env
expect "NOT_FOUND_PAYMENT"; if printf '%s' "$OUT" | grep -qF "서로 다르게"; then echo "FAIL: identical values under both names are not a conflict"; FAIL=1; fi
printf 'SKELETON_PAYMENT_TOSS_CLIENT_KEY=test_ck_aaaa\nTOSS_PAYMENTS_CLIENT_KEY=test_ck_bbbb\n%s=%s\n' SKELETON_PAYMENT_TOSS_SECRET_KEY "$SECRET" > "$T/toss-clientconflict.env"; runt toss-clientconflict.env
expect "서로 다르게"; expect "CLIENT_KEY"; [ "$RC" = 1 ] || { echo "FAIL: two different client keys must exit 1 (got $RC)"; FAIL=1; }
printf '%s=test_sk_rejectedTAIL789\n' $TS > "$T/toss-rejected.env"; runt toss-rejected.env
expect "UNAUTHORIZED_KEY"; expect "시크릿 키를 거절"; [ "$RC" = 1 ] || { echo "FAIL: a rejected key must exit 1 (got $RC)"; FAIL=1; }
no_leak "TAIL789"
printf '%s=test_sk_oddTAIL000\n' $TS > "$T/toss-odd.env"; runt toss-odd.env
expect "INVALID_REQUEST"; expect "장부의"; [ "$RC" = 1 ] || { echo "FAIL: an unexpected lookup answer must exit 1 (got $RC)"; FAIL=1; }
printf '%s=test_sk_foundTAIL111\n' $TS > "$T/toss-found.env"; runt toss-found.env
expect "HTTP 200"; [ "$RC" = 1 ] || { echo "FAIL: a 200 for a payment that does not exist must exit 1 (got $RC)"; FAIL=1; }
# 실패 경로 — 5xx 는 장부의 '결제사가 모른다' 분류와 어긋난다
printf '%s=test_sk_fiveTAIL555\n' $TS > "$T/toss-five.env"; runt toss-five.env
expect "HTTP 500"; expect "FAILED_INTERNAL_SYSTEM_PROCESSING"; [ "$RC" = 1 ] || { echo "FAIL: a 5xx must exit 1 (got $RC)"; FAIL=1; }
# 두 취소가 같은 5xx 여도 「같은 응답」 ✓ 를 말하지 않는다 (첫 취소가 기대한 응답일 때만 재응답을 판정한다)
if printf '%s' "$OUT" | grep -qF -- "같은 멱등 키로 다시 보내도 같은 응답"; then echo "FAIL: two identical 5xx cancels must not be called a good replay"; FAIL=1; fi
# 닿지 못함(연결 거절): ✗ 이고 나머지 호출은 보내지 않는다
printf '%s=%s\n' $TS "$SECRET" > "$T/toss-down.env"
OUT="$(env -i PATH="$PATH" HOME="$HOME" ENV_FILE="$T/toss-down.env" CHECK_TOSS_BASE_URL="http://127.0.0.1:1/toss" CHECK_TOSS_TIMEOUT_SEC=5 "${SELF_TEST_BASH:-/bin/bash}" scripts/check-real-providers.sh --toss-only 2>&1)"; RC=$?
expect "토스 서버에 닿지 못했다"; expect "나머지 호출은 보내지 않았다"; [ "$RC" = 1 ] || { echo "FAIL: an unreachable Toss must exit 1 (got $RC)"; FAIL=1; }
# 응답이 늦다(시간 제한): ✗ 이고 그 뒤 호출을 보내지 않는다 — 가짜 서버가 받은 호출은 첫 번째 하나뿐
printf '%s=test_sk_hangTAIL666\n' $TS > "$T/toss-hang.env"; TT=1 runt toss-hang.env
expect "토스 서버에 닿지 못했다"; expect "나머지 호출은 보내지 않았다"; [ "$RC" = 1 ] || { echo "FAIL: a timed-out Toss must exit 1 (got $RC)"; FAIL=1; }
# 잰 호출이 하나도 없으면 「응답 시간」 ✓ 를 찍지 않는다
if printf '%s' "$OUT" | grep -qF -- "응답 시간"; then echo "FAIL: no call was measured, yet a response-time line was printed"; FAIL=1; fi
[ "$(wc -l < "$T/calls.log" | tr -d ' ')" = 1 ] || { echo "FAIL: after a timeout no further call may be sent (the fake server saw: $(tr '\n' ';' < "$T/calls.log"))"; FAIL=1; }
# 취소만 늦다(2~6번째 호출에서 시간 초과): 그 호출에서 멈추고 뒤 호출을 보내지 않는다 — 가짜 서버가 받은 것은 조회 둘 + 취소 하나
printf '%s=test_sk_hangcancelTAIL999\n' $TS > "$T/toss-hangcancel.env"; TT=1 runt toss-hangcancel.env
expect "토스 서버에 닿지 못했다"; expect "나머지 호출은 보내지 않았다"; [ "$RC" = 1 ] || { echo "FAIL: a timed-out cancel must exit 1 (got $RC)"; FAIL=1; }
[ "$(wc -l < "$T/calls.log" | tr -d ' ')" = 3 ] || { echo "FAIL: after a timeout on the third call no further call may be sent (the fake server saw: $(tr '\n' ';' < "$T/calls.log"))"; FAIL=1; }
# 조회는 기대대로인데 취소만 다르다
printf '%s=test_sk_canceloddTAIL777\n' $TS > "$T/toss-cancelodd.env"; runt toss-cancelodd.env
expect "없는 결제의 취소 → HTTP 400 INVALID_REQUEST"; [ "$RC" = 1 ] || { echo "FAIL: a cancel answer that differs must exit 1 (got $RC)"; FAIL=1; }
# 같은 멱등 키의 재응답이 다르다
printf '%s=test_sk_replayTAIL888\n' $TS > "$T/toss-replay.env"; runt toss-replay.env
expect "응답이 달랐다"; expect "IDEMPOTENT_REQUEST_PROCESSING"; [ "$RC" = 1 ] || { echo "FAIL: a differing replay must exit 1 (got $RC)"; FAIL=1; }
# --toss-only 에서 ✗ 가 있으면 종료 코드는 1 이다 — 「종료 코드 3」 문구를 찍지 않는다 (클라이언트 키가 없어 건너뛴 줄이 있어도)
if printf '%s' "$OUT" | grep -qF -- "종료 코드 3"; then echo "FAIL: a run with a ✗ printed the exit-code-3 wording"; FAIL=1; fi
# 틀린 키로 조회했는데 기대한 401 이 아니다
echo bogus200 > "$T/mode"; runt toss-noclient.env
expect "틀린 키로 조회 → HTTP 200"; [ "$RC" = 1 ] || { echo "FAIL: an unexpected answer to the wrong key must exit 1 (got $RC)"; FAIL=1; }
: > "$T/mode"
# 테스트 코드 헤더를 무시하면(없는 결제 404 가 먼저) 스크립트의 항목이 ✗ 로 말한다
printf '%s=test_sk_notestcodeTAIL999\n' $TS > "$T/toss-notestcode.env"; runt toss-notestcode.env
expect "TossPayments-Test-Code"; expect "적용되지 않았다"; [ "$RC" = 1 ] || { echo "FAIL: an ignored test-code header must exit 1 (got $RC)"; FAIL=1; }
# 시험에 쓰는 테스트 코드는 장부의 확정 거절 목록에 실제로 있어야 한다 (스크립트의 기대와 TossPaymentErrorMapper.DEFINITIVE_CODES 가 어긋나지 않게)
TEST_CODE="$(sed -nE 's/^TOSS_TEST_CODE=([A-Z0-9_]+).*/\1/p' scripts/check-real-providers.d/toss.sh | head -1)"
MAPPER=modules/payment-toss/src/main/kotlin/dev/sumin/skeleton/payment/toss/TossPaymentErrorMapper.kt
[ -n "$TEST_CODE" ] || { echo "FAIL: toss.sh names no TOSS_TEST_CODE"; FAIL=1; }
if [ -d modules/payment-toss ]; then   # 모듈이 있는데 매퍼 파일이 없으면 파일이 옮겨진 것이다 — 대조를 건너뛰지 않고 알린다
  [ -f "$MAPPER" ] || { echo "FAIL: modules/payment-toss exists but $MAPPER does not (the DEFINITIVE_CODES cross-check cannot run)"; FAIL=1; }
fi
if [ -f "$MAPPER" ] && [ -n "$TEST_CODE" ]; then   # 코드는 DEFINITIVE_CODES 블록 안에서만 찾는다 (다른 목록 · 주석의 언급은 확정 거절이 아니다)
  awk '/val DEFINITIVE_CODES/ { on = 1 } on { print } on && /^[[:space:]]*\)/ { exit }' "$MAPPER" | grep -qF "\"$TEST_CODE\"" || { echo "FAIL: $TEST_CODE is not in TossPaymentErrorMapper.DEFINITIVE_CODES"; FAIL=1; }
fi
printf '%s=live_sk_abcdefTAIL222\n' $TS > "$T/toss-live.env"; OUT="$(env -i PATH="$PATH" HOME="$HOME" ENV_FILE="$T/toss-live.env" CHECK_TOSS_BASE_URL="http://127.0.0.1:1/toss" "${SELF_TEST_BASH:-/bin/bash}" scripts/check-real-providers.sh --toss-only 2>&1)"; RC=$?
expect "live 키"; expect "부르지 않는다"; [ "$RC" = 1 ] || { echo "FAIL: a live key must exit 1 (got $RC)"; FAIL=1; }
if printf '%s' "$OUT" | grep -qF "닿지 못"; then echo "FAIL: a live key must not make a network call"; FAIL=1; fi
no_leak "TAIL222"
printf '%s=test_gsk_widgetTAIL333\n' $TS > "$T/toss-widget.env"; runt toss-widget.env
expect "결제위젯"; [ "$RC" = 1 ] || { echo "FAIL: a widget key must exit 1 (got $RC)"; FAIL=1; }
printf '%s=%s\n%s=live_ck_otherTAIL444\n' $TS "$SECRET" $TC > "$T/toss-mixed.env"; runt toss-mixed.env
expect "모드가 다르다"; [ "$RC" = 1 ] || { echo "FAIL: mixed modes must exit 1 (got $RC)"; FAIL=1; }
printf 'SKELETON_PAYMENT_TOSS_ENABLED=true\n' > "$T/toss-nokey.env"; runt toss-nokey.env
expect "시크릿 키가 비었다"; [ "$RC" = 1 ] || { echo "FAIL: enabled without a key must exit 1 (got $RC)"; FAIL=1; }
printf '%s=%s\n' $TS "$SECRET" > "$T/toss-env-file-arg.env"
OUT="$(env -i PATH="$PATH" HOME="$HOME" CHECK_TOSS_BASE_URL="http://127.0.0.1:$PORT/toss" "${SELF_TEST_BASH:-/bin/bash}" scripts/check-real-providers.sh --toss-only --env-file "$T/toss-env-file-arg.env" 2>&1)"; RC=$?
expect "NOT_FOUND_PAYMENT"
if grep -nE '^[^#]*(set -x|curl[^|]* -u )' scripts/check-real-providers.d/toss.sh >/dev/null 2>&1; then echo "FAIL: toss.sh must not use set -x or curl -u (the key would show in traces / argv)"; FAIL=1; fi

# --- scripts/payment-webhook.sh (웹훅을 손으로 넣어 보는 도구) ----------------------------------------------------------------------------
if [ -d modules/payment-toss ] && [ ! -f scripts/payment-webhook.sh ]; then   # 모듈이 있는데 도구가 없으면 구역이 말없이 빠진 것이다 — 매퍼 대조와 같이 알린다
  echo "FAIL: modules/payment-toss exists but scripts/payment-webhook.sh does not (the webhook tool checks cannot run)"; FAIL=1
fi
if [ -f scripts/payment-webhook.sh ]; then   # payment-toss 를 고르지 않고 찍은 프로젝트에는 이 도구가 없다 — 매퍼 대조와 같이 없는 모듈을 견딘다
hook() { OUT="$(env -i PATH="$PATH" HOME="$HOME" PAYMENT_WEBHOOK_TOKEN=tok123 "${SELF_TEST_BASH:-/bin/bash}" scripts/payment-webhook.sh "$@" 2>&1)"; RC=$?; }
: > "$T/hook.log"
hook --url "http://127.0.0.1:$PORT" --payment-key pk_test-1 --order-id ord-1 --transmission-id tr-1
expect "200"; [ "$RC" = 0 ] || { echo "FAIL: webhook happy path must exit 0 (got $RC)"; FAIL=1; }
no_leak "tok123"
grep -qF '"eventType": "PAYMENT_STATUS_CHANGED"' "$T/hook.log" || grep -qF 'PAYMENT_STATUS_CHANGED' "$T/hook.log" || { echo "FAIL: webhook body has no PAYMENT_STATUS_CHANGED"; FAIL=1; }
grep -qF 'pk_test-1' "$T/hook.log" && grep -qF 'ord-1' "$T/hook.log" || { echo "FAIL: webhook body lacks data.paymentKey / data.orderId"; FAIL=1; }
grep -qF '"tid": "tr-1"' "$T/hook.log" || { echo "FAIL: webhook missing the transmission-id header"; FAIL=1; }
: > "$T/hook.log"; hook --url "http://127.0.0.1:$PORT" --payment-key pk_test-1 --order-id ord-1 --repeat 2 --decoy-status CANCELED
[ "$(wc -l < "$T/hook.log" | tr -d ' ')" = 2 ] || { echo "FAIL: --repeat 2 must send two deliveries"; FAIL=1; }
grep -qF 'CANCELED' "$T/hook.log" || { echo "FAIL: --decoy-status must put the status into the body"; FAIL=1; }
expect "본문"; expect "result="
: > "$T/hook.log"; hook --url "http://127.0.0.1:$PORT" --payment-key pk_test-1 --order-id ord-1 --wrong-token
expect "404"; [ "$RC" = 0 ] || { echo "FAIL: --wrong-token expects a 404 and that is the pass (got $RC)"; FAIL=1; }
no_leak "tok123"
# 비밀 조각이 없으면 (--no-token 없이) 기대를 몰래 바꾸지 않고 멈춘다
hooknt() { OUT="$(env -i PATH="$PATH" HOME="$HOME" "${SELF_TEST_BASH:-/bin/bash}" scripts/payment-webhook.sh "$@" 2>&1)"; RC=$?; }
: > "$T/hook.log"; hooknt --url "http://127.0.0.1:$PORT" --payment-key pk_test-1 --order-id ord-1
[ "$RC" = 2 ] || { echo "FAIL: no token and no --no-token must exit 2 (got $RC)"; FAIL=1; }; expect "--no-token"
[ ! -s "$T/hook.log" ] || { echo "FAIL: nothing may be sent when the token is missing and --no-token was not given"; FAIL=1; }
hooknt --url "http://127.0.0.1:$PORT" --payment-key pk_test-1 --order-id ord-1 --no-token
expect "404"; [ "$RC" = 0 ] || { echo "FAIL: --no-token expects a 404 and that is the pass (got $RC)"; FAIL=1; }
# 환경 파일에서 읽는다 — 앱이 읽는 이름(SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN)을 셸로 실행하지 않고 그 줄만 꺼낸다
NAME=SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN
printf 'MARKER=$(touch %s/executed)\n%s="tok123"\n' "$T" "$NAME" > "$T/hook.env"
: > "$T/hook.log"; hooknt --url "http://127.0.0.1:$PORT" --payment-key pk_test-1 --order-id ord-1 --env-file "$T/hook.env"
expect "200"; [ "$RC" = 0 ] || { echo "FAIL: --env-file token must give the happy path (got $RC)"; FAIL=1; }
no_leak "tok123"
[ ! -e "$T/executed" ] || { echo "FAIL: --env-file must not execute the file's shell code"; FAIL=1; }
# .env.example 모양의 줄(줄 끝 주석 · 따옴표 뒤 주석 · export · 작은따옴표 · CRLF)에서도 같은 조각을 읽는다
envcase() {   # <설명> <파일 내용 printf 서식> — 같은 조각(tok123)을 읽어 200 이어야 한다
  printf "$2" "$NAME" > "$T/hook-case.env"; : > "$T/hook.log"
  hooknt --url "http://127.0.0.1:$PORT" --payment-key pk_test-1 --order-id ord-1 --env-file "$T/hook-case.env"
  [ "$RC" = 0 ] || { echo "FAIL: --env-file $1 must give the happy path (got $RC): $OUT"; FAIL=1; }
  grep -qF '/toss/tok123"' "$T/hook.log" || { echo "FAIL: --env-file $1 did not read the token tok123"; FAIL=1; }
}
envcase "trailing comment" '%s=tok123   # [payment] 웹훅 주소 끝의 비밀 조각\n'
envcase "quoted value then comment" '%s="tok123"   # 주석\n'
envcase "single-quoted value then comment" "%s='tok123' # 주석\n"
envcase "export prefix" 'export %s=tok123\n'
envcase "single quotes" "%s='tok123'\n"
envcase "CRLF line ending" '%s=tok123\r\n'
envcase "tab before the comment" '%s=tok123\t# 주석\n'
envcase "last of two lines wins" 'SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN=old999\n%s=tok123\n'
# 따옴표 안의 # 은 값이다 — 주소에 쓸 수 없는 글자라 2 로 멈춘다. 공백 없는 # 도 값의 일부다
printf '%s="tok#123"\n' "$NAME" > "$T/hook-case.env"; hooknt --url "http://127.0.0.1:$PORT" --payment-key pk_test-1 --order-id ord-1 --env-file "$T/hook-case.env"
[ "$RC" = 2 ] || { echo "FAIL: a # inside quotes is part of the value and must be refused (got $RC)"; FAIL=1; }
printf '%s=tok123#x\n' "$NAME" > "$T/hook-case.env"; hooknt --url "http://127.0.0.1:$PORT" --payment-key pk_test-1 --order-id ord-1 --env-file "$T/hook-case.env"
[ "$RC" = 2 ] || { echo "FAIL: a # without a space before it is part of the value and must be refused (got $RC)"; FAIL=1; }
# 줄이 없는 환경 파일: 환경 변수의 토큰으로 넘어가며 그 사실을 한 줄 알린다 (값은 찍지 않는다). 환경 변수도 없으면 2
printf '# %s=commented\nOTHER=1\n' "$NAME" > "$T/hook-case.env"
: > "$T/hook.log"; OUT="$(env -i PATH="$PATH" HOME="$HOME" PAYMENT_WEBHOOK_TOKEN=tok123 "${SELF_TEST_BASH:-/bin/bash}" scripts/payment-webhook.sh --url "http://127.0.0.1:$PORT" --payment-key pk_test-1 --order-id ord-1 --env-file "$T/hook-case.env" 2>&1)"; RC=$?
[ "$RC" = 0 ] || { echo "FAIL: an env file without the line falls back to the environment (got $RC)"; FAIL=1; }
expect "에 SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN 줄이 없다"; no_leak "tok123"
hooknt --url "http://127.0.0.1:$PORT" --payment-key pk_test-1 --order-id ord-1 --env-file "$T/hook-case.env"
[ "$RC" = 2 ] || { echo "FAIL: no line in the env file and no environment token must exit 2 (got $RC)"; FAIL=1; }
expect "줄이 없다"
# 옵션의 값이 빠지면 종료 코드 2 (문서의 약속)
for opt in --url --payment-key --order-id --token --env-file --transmission-id --repeat --event --decoy-status --provider --base-path; do
  hooknt "$opt"; [ "$RC" = 2 ] || { echo "FAIL: $opt without a value must exit 2 (got $RC)"; FAIL=1; }
done
hooknt --url "http://127.0.0.1:$PORT" --payment-key pk_test-1 --order-id ord-1 --env-file "$T/missing.env"
[ "$RC" = 2 ] || { echo "FAIL: a missing --env-file must exit 2 (got $RC)"; FAIL=1; }
# --help 는 사용법만 찍는다 (set 줄까지 찍지 않는다)
hooknt --help
if printf '%s' "$OUT" | grep -qF 'set -uo pipefail'; then echo "FAIL: --help printed past the usage block"; FAIL=1; fi
expect "--no-token"
hook --url "http://127.0.0.1:$PORT" --order-id ord-1
[ "$RC" = 2 ] || { echo "FAIL: a missing --payment-key must exit 2 (got $RC)"; FAIL=1; }
OUT="$(env -i PATH="$PATH" HOME="$HOME" "${SELF_TEST_BASH:-/bin/bash}" scripts/payment-webhook.sh --payment-key pk --order-id o 2>&1)"; RC=$?
[ "$RC" = 2 ] || { echo "FAIL: a missing --url must exit 2 (the target is never defaulted) (got $RC)"; FAIL=1; }
fi
[ "$FAIL" = 0 ] && echo "OK check-real-providers" || exit 1
