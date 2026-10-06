#!/usr/bin/env bash
# scripts/check-real-providers.sh --self-test 가 부른다 — scripts/check-real-providers.sh 의 시험 — 가짜 제공자 서버(python)로 ✓ · ✗ 해석과 "빈 환경 = 문제 없음" 을 확인한다. 네트워크 · 도커 없이 돈다.
set -uo pipefail
cd "$(dirname "$0")/../.."
T="$(mktemp -d)"; trap 'kill "$SRV" 2>/dev/null; rm -rf "$T"' EXIT
cat > "$T/srv.py" <<'PY'
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def _send(self, code, body):
        b = body.encode(); self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); data = self.rfile.read(n).decode()
        if self.path.startswith("/line"):
            # 실측한 LINE 의 모양: 틀린 키에도 가짜 코드면 invalid_grant (client_secret=wrong 만 invalid_client 로 흉내 낸다)
            if "client_secret=wrong" in data: self._send(400, '{"error":"invalid_client"}')
            else: self._send(400, '{"error":"invalid_grant","error_description":"invalid authorization code"}')
        elif self.path.startswith("/x"):
            import base64
            auth = self.headers.get("Authorization", "")
            if auth == "Basic " + base64.b64encode(b"xid:good").decode(): self._send(400, '{"error":"invalid_request","error_description":"Value passed for the authorization code was invalid."}')
            else: self._send(400, '{"error":"invalid_client","error_description":"Value passed for the client id was invalid."}')
        elif "client_secret=good" in data: self._send(400, '{"error":"invalid_grant","error_description":"Bad Request"}')
        elif "client_secret=mismatch" in data: self._send(400, '{"error":"redirect_uri_mismatch"}')
        else: self._send(401, '{"error":"invalid_client","error_description":"Unauthorized"}')
    def do_GET(self): self._send(200, '{"token_endpoint":"https://oauth2.googleapis.com/token","userinfo_endpoint":"https://openidconnect.googleapis.com/v1/userinfo"}')
    def log_message(self, *a): pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
PORT=$((20000 + RANDOM % 10000)); python3 "$T/srv.py" "$PORT" & SRV=$!; sleep 1
FAIL=0
expect() { if ! printf '%s' "$OUT" | grep -qF -- "$1"; then echo "FAIL: expected '$1' in:"; printf '%s\n' "$OUT"; FAIL=1; fi; }
run() { OUT="$(env -i PATH="$PATH" HOME="$HOME" ENV_FILE="$T/$1" CHECK_GOOGLE_TOKEN_URL="http://127.0.0.1:$PORT/t" CHECK_KAKAO_TOKEN_URL="http://127.0.0.1:$PORT/t" CHECK_LINE_TOKEN_URL="http://127.0.0.1:$PORT/line" CHECK_X_TOKEN_URL="http://127.0.0.1:$PORT/x" CHECK_LINE_DISCOVERY_URL="http://127.0.0.1:$PORT/d" CHECK_GOOGLE_DISCOVERY_URL="http://127.0.0.1:$PORT/d" BASE_URL=http://127.0.0.1:1 scripts/check-real-providers.sh 2>&1)"; RC=$?; }
G=SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE
printf '' > "$T/empty.env"; run empty.env
expect "켜진 제공자가 없다"; [ "$RC" = 0 ] || { echo "FAIL: empty env must exit 0 (got $RC)"; FAIL=1; }
printf '%s_ENABLED=true\n%s_CLIENT_ID=1-a.apps.googleusercontent.com\n%s_CLIENT_SECRET=good\n%s_REDIRECT_URI=https://app.example.com/auth/callback\n' $G $G $G $G > "$T/good.env"; run good.env
expect "제공자가 client id/secret 을 받아들였다"; [ "$RC" = 0 ] || { echo "FAIL: good env must exit 0 (got $RC)"; FAIL=1; }
printf '%s_ENABLED=true\n%s_CLIENT_ID=1-a.apps.googleusercontent.com\n%s_CLIENT_SECRET=wrong\n' $G $G $G > "$T/bad.env"; run bad.env
expect "client id 또는 client secret 이 틀렸다"; [ "$RC" = 1 ] || { echo "FAIL: wrong secret must exit 1 (got $RC)"; FAIL=1; }
printf '%s_ENABLED=true\n%s_CLIENT_ID=1-a.apps.googleusercontent.com\n%s_CLIENT_SECRET=mismatch\n%s_REDIRECT_URI=https://app.example.com/auth/callback/\n' $G $G $G $G > "$T/mm.env"; run mm.env
expect "REDIRECT_URI 를 콘솔 등록값과 비교한다"; expect "끝의 /"
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
[ "$FAIL" = 0 ] && echo "OK check-real-providers" || exit 1
