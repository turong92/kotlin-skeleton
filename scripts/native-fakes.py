#!/usr/bin/env python3
"""네이티브 이미지 `--full` 시험의 가짜 상대 — 소셜 제공자(google · kakao · naver · LINE(OIDC) · X) · 토스 · Paddle 의 API 를 한 서버가 흉내 낸다 (docs/native-image.md).

    python3 scripts/native-fakes.py [--port 19000]

목적은 **앱이 외부 응답을 JSON 으로 읽는가**(역직렬화 · 리플렉션)를 네이티브 바이너리에서 실제로 보는 것이다. 응답 모양은 각 제공자 공식 문서의 모양을 따르고,
일부러 앱이 쓰지 않는 칸도 실어 보낸다(진짜 응답처럼). 시험 전용 비밀 값만 쓰며 외부로 나가지 않는다. 표준 라이브러리만 쓴다.

    GET  /_stats                         호출 횟수 (이름 → 횟수) — 시험이 "정말 이 상대를 불렀는가" 를 본다
    POST /paddle/_deliver                {path 또는 url, secret, body}: Paddle 처럼 서명(Paddle-Signature)해서 앱의 웹훅 주소로 보낸다 → {status}
    GET  /line/authorize                 브라우저 대신: nonce · code_challenge 를 받아 코드를 내준다 (LINE 은 PKCE · nonce 필수)
"""
import argparse
import base64
import hashlib
import hmac
import json
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

LINE_SECRET = "native-line-secret-0123456789abcdef"
LINE_ID = "native-line"
stats = {}
lock = threading.Lock()
line_grants = {}
toss_payments = {}
toss_seq = [0]


def b64u(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def hs256_jwt(claims, secret):
    head = b64u(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    body = b64u(json.dumps(claims).encode())
    sig = hmac.new(secret.encode(), f"{head}.{body}".encode(), hashlib.sha256).digest()
    return f"{head}.{body}.{b64u(sig)}"


def toss_payment(key, order, amount, status="DONE", method="카드"):
    return {
        "version": "2022-11-16", "paymentKey": key, "type": "NORMAL", "orderId": order, "orderName": "native check",
        "mId": "tosspayments", "currency": "KRW", "method": method, "totalAmount": amount, "balanceAmount": amount, "status": status,
        "requestedAt": "2026-10-15T12:00:00+09:00", "approvedAt": "2026-10-15T12:00:05+09:00", "lastTransactionKey": "tx_" + key,
        "transactionKey": "tx_" + key, "traceId": "trace-" + key, "cancels": None, "suppliedAmount": amount, "vat": 0, "isPartialCancelable": True,
        "card": {"issuerCode": "41", "acquirerCode": "41", "number": "123456******1234", "installmentPlanMonths": 0, "isInterestFree": False,
                 "approveNo": "00000000", "cardType": "신용", "ownerType": "개인", "amount": amount},
        "receipt": {"url": "https://example.invalid/receipt"}, "country": "KR",
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _count(self, name):
        with lock:
            stats[name] = stats.get(name, 0) + 1

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def _json(self, status, value, headers=None):
        raw = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def do_DELETE(self):
        self._route("DELETE")

    def _route(self, method):
        url = urlparse(self.path)
        path = url.path
        q = parse_qs(url.query)
        raw = self._body() if method != "GET" else b""
        form = parse_qs(raw.decode()) if raw and "json" not in (self.headers.get("Content-Type") or "") else {}
        auth = self.headers.get("Authorization") or ""

        if path == "/_stats":
            return self._json(200, stats)

        # ---- 소셜: 토큰 교환은 코드를 받고 access_token 을 준다 (실제 응답처럼 안 쓰는 칸도 실어서) ----
        if method == "POST" and path == "/google/token":
            self._count("google.token")
            if not form.get("code"):
                return self._json(400, {"error": "invalid_grant"})
            return self._json(200, {"access_token": "g-at", "expires_in": 3599, "scope": "openid email profile", "token_type": "Bearer", "unknown_extra": {"a": 1}})
        if method == "GET" and path == "/google/userinfo":
            self._count("google.userinfo")
            if auth != "Bearer g-at":
                return self._json(401, {"error": "invalid_token"})
            return self._json(200, {"sub": "g-native-1", "email": "native.google@gmail.com", "email_verified": True, "name": "Google Native", "picture": "https://img.example.invalid/g.png", "locale": "ko"})

        if method == "POST" and path == "/kakao/oauth/token":
            self._count("kakao.token")
            if not form.get("code"):
                return self._json(400, {"error": "invalid_grant"})
            return self._json(200, {"access_token": "k-at", "token_type": "bearer", "refresh_token": "k-rt", "expires_in": 21599, "scope": "profile_nickname", "refresh_token_expires_in": 5183999})
        if method == "GET" and path == "/kakao/v2/user/me":
            self._count("kakao.me")
            if auth != "Bearer k-at":
                return self._json(401, {"msg": "this access token does not exist", "code": -401})
            return self._json(200, {"id": 3000000001, "connected_at": "2026-10-15T00:00:00Z", "kakao_account": {
                "profile_nickname_needs_agreement": False, "profile": {"nickname": "카카오네이티브", "is_default_nickname": False},
                "has_email": True, "email_needs_agreement": False, "is_email_valid": True, "is_email_verified": True, "email": "native.kakao@example.com"}})

        if method == "POST" and path == "/naver/oauth2.0/token":
            self._count("naver.token")
            if not form.get("code"):
                return self._json(200, {"error": "invalid_request", "error_description": "no code"})
            return self._json(200, {"access_token": "n-at", "refresh_token": "n-rt", "token_type": "bearer", "expires_in": "3600"})
        if method == "GET" and path == "/naver/v1/nid/me":
            self._count("naver.me")
            if auth != "Bearer n-at":
                return self._json(401, {"resultcode": "024", "message": "Authentication failed"})
            return self._json(200, {"resultcode": "00", "message": "success", "response": {"id": "naver-native-1", "email": "native.naver@example.com", "nickname": "네이버네이티브", "name": "네이버 이름", "profile_image": "https://img.example.invalid/n.png"}})

        if method == "POST" and path == "/x/2/oauth2/token":
            self._count("x.token")
            if not form.get("code") or not form.get("code_verifier") or not auth.startswith("Basic "):
                return self._json(400, {"error": "invalid_request"})
            return self._json(200, {"token_type": "bearer", "expires_in": 7200, "access_token": "x-at", "scope": "tweet.read users.read"})
        if method == "GET" and path == "/x/2/users/me":
            self._count("x.me")
            if auth != "Bearer x-at":
                return self._json(401, {"title": "Unauthorized"})
            return self._json(200, {"data": {"id": "x-native-1", "name": "X Native", "username": "x_native"}})

        # ---- LINE (OIDC 프리셋): 인가(코드 + PKCE + nonce) → 토큰(HS256 ID 토큰) ----
        if method == "GET" and path == "/line/authorize":
            self._count("line.authorize")
            if q.get("code_challenge_method", [""])[0] != "S256" or not q.get("code_challenge") or not q.get("nonce"):
                return self._json(400, {"error": "invalid_request"})
            code = "line-code-%d" % int(time.time() * 1000)
            line_grants[code] = {"challenge": q["code_challenge"][0], "nonce": q["nonce"][0]}
            return self._json(200, {"code": code})
        if method == "POST" and path == "/line/token":
            self._count("line.token")
            grant = line_grants.pop((form.get("code") or [""])[0], None)
            verifier = (form.get("code_verifier") or [""])[0]
            if not grant or (form.get("client_secret") or [""])[0] != LINE_SECRET or b64u(hashlib.sha256(verifier.encode()).digest()) != grant["challenge"]:
                return self._json(400, {"error": "invalid_grant"})
            now = int(time.time())
            claims = {"iss": self.server.base + "/line", "sub": "U-native-line-1", "aud": LINE_ID, "exp": now + 3600, "iat": now, "nonce": grant["nonce"], "name": "LINE Native", "amr": ["pwd"]}
            return self._json(200, {"access_token": "line-at", "token_type": "Bearer", "refresh_token": "line-rt", "expires_in": 2592000, "scope": "openid profile", "id_token": hs256_jwt(claims, LINE_SECRET)})
        if method == "GET" and path == "/line/certs":
            self._count("line.certs")
            return self._json(200, {"keys": []})
        if method == "GET" and path == "/line/userinfo":
            self._count("line.userinfo")
            return self._json(200, {"sub": "U-native-line-1", "name": "LINE Native"})

        # ---- 토스: 승인 · 조회 · 빌링 (Basic 인증 확인) ----
        if path.startswith("/toss/"):
            if not auth.startswith("Basic "):
                return self._json(401, {"code": "UNAUTHORIZED_KEY", "message": "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."})
            sub = path[len("/toss"):]
            body = json.loads(raw or b"{}") if raw else {}
            if method == "POST" and sub == "/v1/payments/confirm":
                self._count("toss.confirm")
                payment = toss_payment(body["paymentKey"], body["orderId"], int(body["amount"]))
                toss_payments[body["paymentKey"]] = payment
                return self._json(200, payment)
            if method == "GET" and sub.startswith("/v1/payments/orders/"):
                self._count("toss.lookup-order")
                order = sub.rsplit("/", 1)[1]
                found = next((p for p in toss_payments.values() if p["orderId"] == order), None)
                return self._json(200, found) if found else self._json(404, {"code": "NOT_FOUND_PAYMENT", "message": "존재하지 않는 결제 입니다."})
            if method == "GET" and sub.startswith("/v1/payments/"):
                self._count("toss.lookup")
                found = toss_payments.get(sub.rsplit("/", 1)[1])
                return self._json(200, found) if found else self._json(404, {"code": "NOT_FOUND_PAYMENT", "message": "존재하지 않는 결제 입니다."})
            if method == "POST" and sub == "/v1/billing/authorizations/issue":
                self._count("toss.billing-issue")
                return self._json(200, {"mId": "tosspayments", "customerKey": body.get("customerKey"), "authenticatedAt": "2026-10-15T12:00:00+09:00", "method": "카드",
                                        "billingKey": "bk_native_1", "card": {"issuerCode": "41", "acquirerCode": "41", "number": "123456******1234", "cardType": "신용", "ownerType": "개인"}})
            if method == "POST" and sub.startswith("/v1/billing/"):
                self._count("toss.billing-charge")
                with lock:
                    toss_seq[0] += 1
                    key = "tgen_charge_native_%d_%d" % (int(time.time() * 1000), toss_seq[0])
                payment = toss_payment(key, body["orderId"], int(body["amount"]))
                toss_payments[key] = payment
                return self._json(200, payment)
            if method == "DELETE" and sub.startswith("/v1/billing/"):
                self._count("toss.billing-delete")
                return self._json(200, {})
            return self._json(404, {"code": "NOT_FOUND", "message": sub})

        # ---- Paddle: 거래 만들기 + 서명한 웹훅 보내기 ----
        if method == "POST" and path == "/paddle/transactions":
            self._count("paddle.transactions")
            if auth != "Bearer native-paddle-api-key":
                return self._json(403, {"error": {"type": "request_error", "code": "forbidden", "detail": "bad key"}})
            return self._json(201, {"data": {"id": "txn_native_%d" % int(time.time() * 1000), "status": "draft", "currency_code": "KRW", "origin": "api", "items": []}, "meta": {"request_id": "req-native"}})
        if method == "POST" and path == "/paddle/_deliver":
            self._count("paddle.deliver")
            req = json.loads(raw)
            target = req.get("url") or (self.server.app_base + req["path"])
            body = req["body"].encode()
            ts = str(int(time.time()))
            mac = hmac.new(req["secret"].encode(), f"{ts}:".encode() + body, hashlib.sha256).hexdigest()
            r = urllib.request.Request(target, data=body, method="POST", headers={"Content-Type": "application/json", "Paddle-Signature": f"ts={ts};h1={mac}"})
            try:
                with urllib.request.urlopen(r, timeout=20) as resp:
                    return self._json(200, {"status": resp.status})
            except urllib.error.HTTPError as e:
                return self._json(200, {"status": e.code})

        return self._json(404, {"error": "not found", "path": path})


class Server(ThreadingHTTPServer):
    base = ""
    app_base = "http://localhost:8080"


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=19000)
    ap.add_argument("--base", default=None, help="앱이 이 서버를 부르는 주소 (LINE ID 토큰의 iss) — 기본 http://localhost:<port>")
    ap.add_argument("--app-base", default="http://localhost:8080", help="웹훅을 보낼 앱 주소 (같은 네트워크 이름공간에서 띄우면 localhost)")
    args = ap.parse_args()
    srv = Server(("0.0.0.0", args.port), Handler)
    srv.base = args.base or f"http://localhost:{args.port}"
    srv.app_base = args.app_base
    print(f"native-fakes listening on {args.port}", flush=True)
    srv.serve_forever()
