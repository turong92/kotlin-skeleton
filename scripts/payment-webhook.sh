#!/usr/bin/env bash
# 결제 웹훅을 손으로 넣어 본다 — 토스 `PAYMENT_STATUS_CHANGED` 모양의 본문을 떠 있는 서버의 수신함 주소로 보낸다 (docs/real-provider-setup.md §11.5).
# 확인하는 것: ① 주소의 비밀 조각(틀리면 404) ② 같은 전달의 재전송 = 중복 ③ 모르는 주문 번호는 저장하지 않고 200 ④ **본문을 믿지 않는다** — 본문의 status 가 거짓이어도
# 서버는 읽지 않고 결제사(토스)에 다시 조회해 사실을 정한다(--decoy-status). 서버에 닿는 것은 이 한 줄 호출뿐이고, 처리 작업이 부르는 토스 조회는 서버가 한다.
#
#   scripts/payment-webhook.sh --url http://localhost:<포트> --payment-key <결제 키> --order-id <주문 번호> [옵션]
#
#   --url <주소>                 (필수) 서버 주소. 기본값이 없다 — 어느 서버에 넣는지는 늘 명시한다 (포트를 박아 두지 않는다)
#   --payment-key <결제 키>      (필수) 토스가 내준 결제 키 — 영문 · 숫자 · - · _
#   --order-id <주문 번호>       (필수) 우리 주문 id. 모르는 주문 번호를 넣으면 서버는 저장하지 않고 200 으로만 답한다
#   --env-file <파일>            앱이 읽는 이름(SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN)의 줄에서 비밀 조각을 읽는다 (권한다). 파일을 셸로 실행하지 않고 그 한 줄만 꺼낸다. 접두사를 바꾼 프로젝트는 --token 이나 환경 변수
#   --token <비밀 조각>          웹훅 주소의 비밀 조각(skeleton.payment.webhook.path-token). 인자는 프로세스 목록 · 셸 이력에 남으므로 --env-file 을 권한다.
#                                (순서: --token > --env-file > 환경 변수 PAYMENT_WEBHOOK_TOKEN > 환경 변수 SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN)
#   --no-token                   비밀 조각 없이 보낸다 — 조각을 요구하는 서버는 404 이고 그것이 기대다. 조각이 없는데 이것도 없으면 멈춘다 (기대를 몰래 404 로 바꾸지 않는다)
#   --wrong-token                비밀 조각을 틀리게 보낸다 — 404 가 나오면 통과
#   --transmission-id <id>       헤더 tosspayments-webhook-transmission-id (기본: 이번 실행에서 새로 만든 값. --repeat 의 재전송은 같은 값을 쓴다)
#   --repeat <N>                 같은 전달을 N 번 보낸다 (재전송 · 중복 시험)
#   --event <종류>               본문의 eventType (기본 PAYMENT_STATUS_CHANGED). 다른 값은 서버가 저장하지 않고 200
#   --decoy-status <상태>        본문 data.status 에 넣는 값(예 CANCELED) — 서버가 읽지 않는다는 것을 보기 위한 거짓말
#   --provider <이름>            기본 toss
#   --base-path <경로>           기본 /api/v1/payments (skeleton.payment.http.base-path)
#
# 종료 코드: 0 = 기대한 응답(보통 200, --wrong-token 이면 404) · 1 = 다른 응답 · 2 = 인자 오류. 비밀 조각은 출력하지 않는다 (curl 의 URL 은 stdin 설정으로 넘겨 인자에 남지 않는다).
set -uo pipefail

URL=""; PKEY=""; ORDER=""; TOKEN=""; TOKEN_ARG=""; ENV_FILE=""; NO_TOKEN=0; WRONG=0; TID=""; REPEAT=1; EVENT=PAYMENT_STATUS_CHANGED; DECOY=""; PROVIDER=toss; BASE_PATH=/api/v1/payments
usage() { sed -n 2,23p "$0" >&2; exit 2; }
# 값을 받는 옵션: 값이 빠지면 안내하고 2 (문서의 종료 코드) — 셸의 값 검사 확장은 2 가 아닌 코드로 끝난다
need() { [ $# -ge 2 ] && [ -n "$2" ] || { echo "$1 에 값이 필요하다" >&2; exit 2; }; }
while [ $# -gt 0 ]; do
  case "$1" in
    --url) need "$@"; URL="$2"; shift 2 ;;
    --payment-key) need "$@"; PKEY="$2"; shift 2 ;;
    --order-id) need "$@"; ORDER="$2"; shift 2 ;;
    --token) need "$@"; TOKEN_ARG="$2"; shift 2 ;;
    --env-file) need "$@"; ENV_FILE="$2"; shift 2 ;;
    --no-token) NO_TOKEN=1; shift ;;
    --wrong-token) WRONG=1; shift ;;
    --transmission-id) need "$@"; TID="$2"; shift 2 ;;
    --repeat) need "$@"; REPEAT="$2"; shift 2 ;;
    --event) need "$@"; EVENT="$2"; shift 2 ;;
    --decoy-status) need "$@"; DECOY="$2"; shift 2 ;;
    --provider) need "$@"; PROVIDER="$2"; shift 2 ;;
    --base-path) need "$@"; BASE_PATH="$2"; shift 2 ;;
    -h|--help) sed -n 2,23p "$0"; exit 0 ;;
    *) echo "모르는 인자: $1" >&2; usage ;;
  esac
done
[ -n "$URL" ] || { echo "--url 이 필요하다 (기본 서버 주소를 두지 않는다)" >&2; usage; }
[ -n "$PKEY" ] && [ -n "$ORDER" ] || { echo "--payment-key 와 --order-id 가 필요하다" >&2; usage; }
safe() { printf '%s' "$2" | grep -qE '^[A-Za-z0-9_-]{1,200}$' || { echo "$1 는 영문 · 숫자 · - · _ 만 (최대 200자)" >&2; exit 2; }; }
safe --payment-key "$PKEY"; safe --order-id "$ORDER"; safe --event "$EVENT"; safe --provider "$PROVIDER"
[ -z "$DECOY" ] || safe --decoy-status "$DECOY"
[ -z "$TID" ] || safe --transmission-id "$TID"
case "$REPEAT" in ''|*[!0-9]*|0) echo "--repeat 는 1 이상의 수" >&2; exit 2 ;; esac
case "$BASE_PATH" in /*) ;; *) echo "--base-path 는 / 로 시작한다" >&2; exit 2 ;; esac
[ -n "$TID" ] || TID="chkhook-$(date +%s)-$RANDOM"

# 비밀 조각 고르기 (값은 출력하지 않는다). --env-file 은 셸로 실행하지 않고 그 이름의 마지막 줄만 꺼낸다 — 따옴표 한 겹은 벗긴다
if [ -n "$ENV_FILE" ]; then
  [ -f "$ENV_FILE" ] || { echo "--env-file $ENV_FILE 이 없다" >&2; exit 2; }
  # 마지막 줄만 꺼내고(CR 제거), 값은 따옴표로 시작하면 닫는 따옴표까지(그 안의 # 은 값), 아니면 공백 뒤 # 이전까지 — .env.example 의 줄 끝 주석을 값에 넣지 않는다
  TOKEN="$(grep -E '^(export[[:space:]]+)?SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN=' "$ENV_FILE" | tail -1 | tr -d '\r' | sed -E 's/^(export[[:space:]]+)?SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN=//')"
  case "$TOKEN" in
    \"*) TOKEN="${TOKEN#\"}"; TOKEN="${TOKEN%%\"*}" ;;
    \'*) TOKEN="${TOKEN#\'}"; TOKEN="${TOKEN%%\'*}" ;;
    *) TOKEN="$(printf '%s' "$TOKEN" | sed -E 's/[[:space:]]+#.*$//; s/[[:space:]]+$//')" ;;
  esac
  # 줄을 못 찾으면 환경 변수의 조각으로 넘어간다 — 말없이 넘어가지 않고 한 줄 알린다 (값은 찍지 않는다)
  [ -n "$TOKEN" ] || [ -n "$TOKEN_ARG" ] || echo "· $ENV_FILE 에 SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN 줄이 없다(또는 값이 비었다) — 환경 변수의 비밀 조각을 찾는다" >&2
fi
[ -z "$TOKEN_ARG" ] || TOKEN="$TOKEN_ARG"
[ -n "$TOKEN" ] || TOKEN="${PAYMENT_WEBHOOK_TOKEN:-${SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN:-}}"
if [ "$NO_TOKEN" = 1 ]; then TOKEN=""
elif [ -z "$TOKEN" ] && [ "$WRONG" = 0 ]; then
  echo "비밀 조각이 없다 — --env-file <파일>(SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN) · --token · 환경 변수 중 하나를 준다. 조각 없이 보내 404 를 보려는 것이면 --no-token" >&2; exit 2
fi
SENT_TOKEN="$TOKEN"
if [ "$WRONG" = 1 ]; then SENT_TOKEN="wrong-$RANDOM-$RANDOM"; fi
EXPECT=200; [ "$WRONG" = 0 ] || EXPECT=404
[ -n "$SENT_TOKEN" ] || EXPECT=404    # --no-token: 조각을 요구하는 서버는 404 다
case "$SENT_TOKEN" in *[!A-Za-z0-9._~-]*) echo "비밀 조각에 주소에 쓸 수 없는 글자가 있다" >&2; exit 2 ;; esac

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
STATUS_PART=""; [ -z "$DECOY" ] || STATUS_PART=",\"status\":\"$DECOY\""
printf '{"eventType":"%s","createdAt":"2026-01-01T00:00:00.000000","data":{"paymentKey":"%s","orderId":"%s"%s}}' "$EVENT" "$PKEY" "$ORDER" "$STATUS_PART" > "$TMP/body.json"
FULL="${URL%/}${BASE_PATH%/}/webhooks/$PROVIDER"; SHOWN="$FULL"
if [ -n "$SENT_TOKEN" ]; then FULL="$FULL/$SENT_TOKEN"; SHOWN="$SHOWN/<비밀 조각>"; fi

echo "대상: POST $SHOWN"
echo "본문: $(cat "$TMP/body.json")"
echo "전달 id(tosspayments-webhook-transmission-id): $TID   ·   기대 응답: HTTP $EXPECT"
BAD=0
for i in $(seq 1 "$REPEAT"); do
  # URL 은 stdin 의 curl 설정으로 넘긴다 — 비밀 조각이 프로세스 인자에 남지 않는다
  out="$(printf 'url = "%s"\n' "$FULL" | curl -sS -m 15 -K - -X POST -H 'Content-Type: application/json' -H "tosspayments-webhook-transmission-id: $TID" \
        --data-binary "@$TMP/body.json" -o "$TMP/resp" -w '%{http_code}' 2>"$TMP/err")"
  body="$(head -c 300 "$TMP/resp" 2>/dev/null | tr '\n' ' ')"
  [ -n "$out" ] || { out=000; body="$(head -c 200 "$TMP/err" | tr '\n' ' ')"; }
  body="${body//"$SENT_TOKEN"/<비밀 조각>}"
  if [ "$out" = "$EXPECT" ]; then echo "  [$i/$REPEAT] HTTP $out ✓ $body"; else echo "  [$i/$REPEAT] HTTP $out ✗ (기대 $EXPECT) $body"; BAD=1; fi
done
case "$BAD:$EXPECT" in
  1:*) case "$out" in
         404) echo "  · 404: 비밀 조각이 틀렸거나, 서버에 path-token 이 비어 있거나(서명 없는 결제사는 늘 404), 웹훅이 꺼졌거나, 결제사 이름이 틀렸다 — 서버는 어느 쪽인지 알리지 않는다" ;;
         429) echo "  · 429: 호출자 IP 한도(webhook.rate-limit) — Retry-After 만큼 기다린다" ;;
         401) echo "  · 401: 본문을 읽을 수 없다(결제 키 모양 · eventType) — 이 도구의 본문 모양 가정이 서버와 다르다" ;;
         000) echo "  · 서버에 닿지 못했다 — --url 을 확인한다" ;;
       esac ;;
  0:200) echo "  · 서버 로그에서 'payment webhook result=' 한 줄을 본다: STORED(처음) · DUPLICATE(같은 전달 id 의 재전송) · REQUEUED(끝나지 않은 줄의 재전송) · UNKNOWN_ORDER(우리 주문이 아니다 — 저장하지 않았다) · UNSUPPORTED(다루지 않는 이벤트) · LIMITED(주문 단위 상한)"
         echo "  · 본문의 status(--decoy-status)는 서버가 읽지 않는다 — 처리 작업이 토스에 조회해서 사실을 정한다. 운영자 수신함(GET {운영자 경로}/webhook-events)에서 줄이 PROCESSED 가 되는지 본다" ;;
  0:404) echo "  · 404 가 맞다 — 틀린(또는 없는) 비밀 조각은 수신함에 닿지 못한다. 서버 로그에도 줄이 남지 않아야 한다" ;;
esac
exit "$BAD"
