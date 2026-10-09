# scripts/check-real-providers.sh 가 읽어 들이는(source) 조각 — 토스페이먼츠 구역 (결제 장부가 토스에 기대는 가정을 재 본다). 혼자 실행하지 않는다.
# 쓰는 것: 부모의 ok · bad · note · BAD · TOSS_ONLY. 정하는 것: TOSS_SKIPPED(=1 이면 사람의 결제창이 있어야 재는 단계를 건너뛰었다 — --toss-only 일 때만 부모가 종료 코드 3 으로 말한다).
#
# 보내는 것은 **돈이 움직이지 않는 호출뿐**이고 모두 합쳐 여섯 번이다: 없는 주문 번호 조회 · 없는 결제 키 조회 · 없는 결제 취소(같은 멱등 키로 두 번) · 틀린 키로 조회 한 번 ·
# 테스트 코드 헤더(TossPayments-Test-Code)를 붙인 없는 결제 취소 한 번. 결제 승인(/confirm)은 보내지 않는다.
# 비밀 키는 출력 · 기록 · 프로세스 인자(curl -u)에 나가지 않는다 — 인증 헤더는 0600 임시 파일에 두고 `-H @파일` 로 읽히며, 응답 원문을 찍을 때는 키와 그 base64 를 ***로 가린다. `set -x` 를 쓰지 않는다.
# 기대하는 응답은 장부(modules/payment-toss 의 TossPaymentErrorMapper · TossPaymentProvider)가 "결제사가 모름 / 확정 거절"을 가르는 근거와 같다 — 한쪽을 바꾸면 이 파일도 본다.

# 테스트 코드 헤더로 재현해 보는 거절 코드 — 장부의 확정 거절 목록(TossPaymentErrorMapper.DEFINITIVE_CODES)에 있고, 취소 호출이 돌려주는 종류다. self-test 가 그 목록에 있는지 대 본다.
TOSS_TEST_CODE=NOT_ALLOWED_PARTIAL_REFUND

toss_trim() { local s="$1"; s="${s#"${s%%[![:space:]]*}"}"; printf '%s' "${s%"${s##*[![:space:]]}"}"; }

# 키 모양: <mode>_<kind>_<rest> — TossApiKey 와 같은 규칙. 결과는 TK_MODE · TK_KIND (모양이 틀리면 둘 다 빈 값)
toss_key_shape() {
  TK_MODE=""; TK_KIND=""
  if [[ "$1" =~ ^(test|live)_(gck|gsk|ck|sk)_.+$ ]]; then TK_MODE="${BASH_REMATCH[1]}"; TK_KIND="${BASH_REMATCH[2]}"; fi
}

# 응답 본문(JSON)의 **최상위** code(없으면 문자열 error)만 꺼낸다 — TossPaymentErrorMapper 와 같은 자리. 중첩 객체 안의 code 는 읽지 않는다.
toss_top_code() {   # stdin: 본문
  tr -d '\n\r' | awk 'BEGIN { RS = "\001" } {
    s = $0; n = length(s); d = 0; i = 1; key = ""; code = ""; err = ""
    while (i <= n) {
      c = substr(s, i, 1)
      if (c == "\"") {
        j = i + 1; str = ""
        while (j <= n) { c2 = substr(s, j, 1); if (c2 == "\\") { str = str substr(s, j + 1, 1); j += 2; continue } if (c2 == "\"") break; str = str c2; j++ }
        i = j + 1
        if (d == 1) {
          k = i; while (k <= n && substr(s, k, 1) ~ /[ \t]/) k++
          if (substr(s, k, 1) == ":") key = str
          else { if (key == "code" && code == "") code = str; if (key == "error" && err == "") err = str; key = "" }
        }
        continue
      }
      if (c == "{" || c == "[") d++
      else if (c == "}" || c == "]") d--
      i++
    }
    print (code != "" ? code : err)
  }'
}

# 한 번 부른다. 결과: T_STATUS(000 = 닿지 못함) · T_CODE · T_MS · T_RAW(키를 가린 응답 원문 일부). 응답 헤더는 $TOSS_TMP/headers
toss_call() {   # <인증 헤더 파일> <메서드> <경로> [멱등 키] [json 본문] [테스트 코드]
  local args=(-sS -m "${CHECK_TOSS_TIMEOUT_SEC:-30}" -X "$2" -H "@$1" -H 'Accept: application/json' -o "$TOSS_TMP/body" -D "$TOSS_TMP/headers" -w '%{http_code} %{time_total}')
  [ -z "${4:-}" ] || args+=(-H "Idempotency-Key: $4")
  [ -z "${5:-}" ] || args+=(-H 'Content-Type: application/json' --data "$5")
  [ -z "${6:-}" ] || args+=(-H "TossPayments-Test-Code: $6")
  : > "$TOSS_TMP/body"; : > "$TOSS_TMP/headers"
  local out secs body
  out="$(curl "${args[@]}" "$TOSS_BASE$3" 2>"$TOSS_TMP/err")"
  T_STATUS="${out%% *}"; secs="${out##* }"
  case "$T_STATUS" in [0-9][0-9][0-9]) ;; *) T_STATUS=000; secs=0 ;; esac
  case "$secs" in *[!0-9.]*|"") secs=0 ;; esac
  T_MS="$(awk -v s="$secs" 'BEGIN { printf "%d", s * 1000 }')"
  body="$(head -c 600 "$TOSS_TMP/body" | tr '\n' ' ')"
  [ "$T_STATUS" != 000 ] || body="$(head -c 200 "$TOSS_TMP/err" | tr '\n' ' ')"
  body="${body//"$TOSS_SECRET"/***}"; body="${body//"$TOSS_AUTH_B64"/***}"
  T_CODE=""; [ "$T_STATUS" = 000 ] || T_CODE="$(head -c 4096 "$TOSS_TMP/body" | toss_top_code)"
  T_RAW="토스 원문: $(printf '%s' "$body" | head -c 200)"
  TOSS_MAX_MS=$(( T_MS > TOSS_MAX_MS ? T_MS : TOSS_MAX_MS ))
  TOSS_CALLS=$((TOSS_CALLS + 1))
  [ "$T_STATUS" = 000 ] || TOSS_MEASURED=$((TOSS_MEASURED + 1))   # 서버가 답한 호출만 시간을 잰 것이다
}

# 없는 결제(주문 번호 · 결제 키)를 물었을 때 장부가 "결제사가 모른다"로 읽는 응답은 404 NOT_FOUND_PAYMENT 뿐이다(그 밖은 전부 "모름 — 다시 해 본다")
toss_expect_unknown_payment() {   # <이름> <장부가 읽는 법>
  case "$T_STATUS:$T_CODE" in
    404:NOT_FOUND_PAYMENT) ok "$1 → 404 NOT_FOUND_PAYMENT, ${T_MS}ms — 장부는 이것을 '결제사가 모른다'로 읽는다 ($2)" ;;
    401:*|403:*) bad "$1 → HTTP $T_STATUS ${T_CODE:--} — 토스가 이 시크릿 키를 거절했다 (테스트 시크릿 키를 개발자센터에서 다시 복사한다). $T_RAW" ;;
    000:*) bad "$1: 토스 서버에 닿지 못했다 (네트워크): $T_RAW" ;;
    *) bad "$1 → HTTP $T_STATUS ${T_CODE:--} — 장부의 '결제사가 모른다'(404 NOT_FOUND_PAYMENT) 분류와 어긋난다: 장부는 이 응답을 '모름'으로 읽고 다시 해 본다. 가정을 고쳐야 한다. $T_RAW" ;;
  esac
}

toss_section() {
  local secret client enabled idem id pkey cancel_body first_status first_code a_secret a_client dead
  # 이름 두 개: 모듈의 이름(SKELETON_PAYMENT_TOSS_*)이 먼저, 벤더 별칭(TOSS_PAYMENTS_*)은 그다음 — 앱(샘플 · 워크벤치 yml)과 같은 순서("모듈 이름이 이긴다").
  # 별칭은 샘플 · 워크벤치만 읽는다: 찍은 스타터(apps/api)는 모듈 이름만 안다. 둘 다 있고 다르면 앱이 쓰지 않는 쪽을 검사하게 되므로 ✗.
  secret="$(toss_trim "${SKELETON_PAYMENT_TOSS_SECRET_KEY:-}")"; a_secret="$(toss_trim "${TOSS_PAYMENTS_SECRET_KEY:-}")"
  client="$(toss_trim "${SKELETON_PAYMENT_TOSS_CLIENT_KEY:-}")"; a_client="$(toss_trim "${TOSS_PAYMENTS_CLIENT_KEY:-}")"
  enabled="${SKELETON_PAYMENT_TOSS_ENABLED:-}"
  TOSS_SKIPPED=0; TOSS_MAX_MS=0; TOSS_CALLS=0; TOSS_MEASURED=0
  echo; echo "== 토스페이먼츠 (결제 장부)"
  if [ -n "$secret" ] && [ -n "$a_secret" ] && [ "$secret" != "$a_secret" ]; then
    bad "토스: 시크릿 키가 두 이름에 서로 다르게 들어 있다 (SKELETON_PAYMENT_TOSS_SECRET_KEY 와 TOSS_PAYMENTS_SECRET_KEY) — 앱은 모듈 이름(SKELETON_PAYMENT_TOSS_SECRET_KEY)을 쓴다. 하나만 남긴다"; return
  fi
  if [ -n "$client" ] && [ -n "$a_client" ] && [ "$client" != "$a_client" ]; then
    bad "토스: 클라이언트 키가 두 이름에 서로 다르게 들어 있다 (SKELETON_PAYMENT_TOSS_CLIENT_KEY 와 TOSS_PAYMENTS_CLIENT_KEY) — 앱은 모듈 이름(SKELETON_PAYMENT_TOSS_CLIENT_KEY)을 쓴다. 하나만 남긴다"; return
  fi
  [ -n "$secret" ] || secret="$a_secret"; [ -n "$client" ] || client="$a_client"
  if [ -z "$secret$client" ] && [ "$enabled" != true ]; then note "토스페이먼츠: 꺼짐 (SKELETON_PAYMENT_TOSS_SECRET_KEY 가 비어 있음)"; return; fi
  if [ -z "$secret" ]; then bad "토스: 시크릿 키가 비었다 (SKELETON_PAYMENT_TOSS_SECRET_KEY — 샘플 · 워크벤치는 별칭 TOSS_PAYMENTS_SECRET_KEY 도 읽는다) — 켜져 있어도 비밀이 없으면 결제사를 부를 수 없다"; return; fi
  [ "$enabled" = true ] || note "키는 있지만 SKELETON_PAYMENT_TOSS_ENABLED=true 가 없다 — 앱은 토스가 꺼진 채 시작한다 (결제 화면은 「지금은 결제할 수 없어요」). 이 점검은 키만 본다"

  # --- 키 모양 (서버를 부르기 전에, 비밀은 접두사와 길이만) -------------------------------------------------------------------------
  toss_key_shape "$secret"
  if [ -z "$TK_KIND" ]; then bad "토스: 시크릿 키 모양이 낯설다 (${#secret}자) — test_sk_… 모양이어야 한다 (결제창 + API 개별 연동 키)"; return; fi
  case "$TK_KIND" in
    gsk|gck) bad "토스: 시크릿 키 자리에 결제위젯 키(${TK_MODE}_${TK_KIND}_…)가 있다 — 이 모듈은 결제창 + API 개별 연동 키(test_sk_… / test_ck_…)만 쓴다"; return ;;
    ck) bad "토스: 시크릿 키 자리에 클라이언트 키(${TK_MODE}_ck_…)가 있다 — 서버에는 test_sk_… 를 둔다"; return ;;
  esac
  if [ "$TK_MODE" = live ]; then bad "토스: live 키(live_sk_…, ${#secret}자)다 — 이 점검은 live 키로 토스를 부르지 않는다. 로컬 · 시험에는 test 키를 쓴다 (실제 돈이 움직이는 키)"; return; fi
  ok "토스: 시크릿 키 모양 (${TK_MODE}_${TK_KIND}_…, ${#secret}자)"
  if [ -z "$client" ]; then
    TOSS_SKIPPED=1
  else
    local smode="$TK_MODE"
    toss_key_shape "$client"
    case "$TK_KIND" in
      ck) ok "토스: 클라이언트 키 모양 (${TK_MODE}_ck_…, ${#client}자)"
          [ "$TK_MODE" = "$smode" ] || bad "토스: 클라이언트 키와 시크릿 키의 모드가 다르다 (${TK_MODE} 대 ${smode}) — 같은 묶음으로 발급된 쌍을 쓴다 (기동도 실패한다)" ;;
      gck) bad "토스: 클라이언트 키가 결제위젯 키(${TK_MODE}_gck_…)다 — test_ck_… 를 쓴다" ;;
      sk|gsk) bad "토스: 클라이언트 키 자리에 시크릿 키가 있다 — 이 값은 브라우저로 나간다. test_ck_… 를 쓴다" ;;
      *) bad "토스: 클라이언트 키 모양이 낯설다 (${#client}자) — test_ck_… 모양이어야 한다" ;;
    esac
  fi

  # --- 토스를 부른다 (돈이 움직이지 않는 호출만) ------------------------------------------------------------------------------------
  TOSS_BASE="${CHECK_TOSS_BASE_URL:-https://api.tosspayments.com}"; TOSS_SECRET="$secret"
  TOSS_AUTH_B64="$(printf '%s:' "$secret" | base64 | tr -d '\n')"
  TOSS_TMP="$(mktemp -d)"; chmod 700 "$TOSS_TMP"
  trap 'rm -f "${tmp:-}"; rm -rf "${TOSS_TMP:-}"' EXIT
  (umask 077; printf 'Authorization: Basic %s\n' "$TOSS_AUTH_B64" > "$TOSS_TMP/auth"
              printf 'Authorization: Basic %s\n' "$(printf '%s:' test_sk_checkrealprovidersbogus | base64 | tr -d '\n')" > "$TOSS_TMP/auth-bogus")
  id="chkreal-$(date +%s)-$RANDOM"; pkey="chkreal_nonexistent_$(date +%s)_$RANDOM"
  idem="chkreal-idem-$(date +%s)-$RANDOM"; cancel_body='{"cancelReason":"check-real-providers (nonexistent payment)"}'

  toss_call "$TOSS_TMP/auth" GET "/v1/payments/orders/$id"
  toss_expect_unknown_payment "없는 주문 번호 조회" "lookup 이 null"
  if [ "$T_STATUS" = 401 ] || [ "$T_STATUS" = 403 ] || [ "$T_STATUS" = 000 ]; then
    note "키가 통하지 않아 나머지 호출은 보내지 않았다"
  else
    # 응답 헤더 가운데 추적 번호 후보 이름 (모듈이 찾는 이름: TossPaymentErrorMapper 의 TOSS_TRACE_HEADERS — 2026-10-09 실측으로 x-tosspayments-trace-id 를 첫 이름으로 더했다)
    local th; th="$(grep -iE '^[a-z0-9-]*(trace|request-id)[a-z0-9-]*:' "$TOSS_TMP/headers" 2>/dev/null | cut -d: -f1 | tr -d '\r' | tr '\n' ' ')"
    [ -n "$th" ] && note "토스 응답 헤더 중 추적 번호 후보: $th" || note "토스 응답 헤더에 추적 번호 후보(trace · request-id)가 없다 — 모듈은 본문의 traceId 도 읽는다"

    dead=0   # 서버에 닿지 못한(000: 연결 실패 · 시간 초과) 호출이 나오면 뒤 호출은 보내지 않는다
    toss_call "$TOSS_TMP/auth" GET "/v1/payments/$pkey"
    toss_expect_unknown_payment "없는 결제 키 조회" "lookup 이 null"
    [ "$T_STATUS" != 000 ] || dead=1

    if [ "$dead" = 0 ]; then
      toss_call "$TOSS_TMP/auth" POST "/v1/payments/$pkey/cancel" "$idem" "$cancel_body"
      first_status="$T_STATUS"; first_code="$T_CODE"
      case "$T_STATUS:$T_CODE" in
        404:NOT_FOUND_PAYMENT) ok "없는 결제의 취소 → 404 NOT_FOUND_PAYMENT, ${T_MS}ms — 장부는 이것을 확정 거절로 읽는다(DEFINITIVE_CODES). 멱등 키(Idempotency-Key) 헤더를 붙여도 거절하지 않는다" ;;
        000:*) bad "없는 결제의 취소: 토스 서버에 닿지 못했다 (네트워크): $T_RAW"; dead=1 ;;
        *) bad "없는 결제의 취소 → HTTP $T_STATUS ${T_CODE:--} — 장부가 기대하는 404 NOT_FOUND_PAYMENT(확정 거절)와 어긋난다. 멱등 키 헤더를 받는지도 이 응답으로는 단정하지 못한다. $T_RAW" ;;
      esac
    fi
    # 재응답은 첫 취소가 기대한 응답일 때만 판정한다 — 둘 다 같은 5xx 인 것은 「같은 응답」이 아니라 같은 고장이다
    if [ "$dead" = 0 ]; then
      if [ "$first_status:$first_code" = "404:NOT_FOUND_PAYMENT" ]; then
        toss_call "$TOSS_TMP/auth" POST "/v1/payments/$pkey/cancel" "$idem" "$cancel_body"
        if [ "$T_STATUS" = 000 ]; then
          bad "같은 멱등 키로 다시 보내기: 토스 서버에 닿지 못했다 (네트워크): $T_RAW"; dead=1
        elif [ "$T_STATUS" = "$first_status" ] && [ "$T_CODE" = "$first_code" ]; then
          ok "같은 멱등 키로 다시 보내도 같은 응답 (HTTP $T_STATUS ${T_CODE:--}) — 오류 경로만 본 것이다: 성공한 취소를 같은 키로 다시 보냈을 때의 본문은 이번에 못 쟀다"
        else
          bad "같은 멱등 키로 다시 보내니 응답이 달랐다: 처음 HTTP $first_status ${first_code:--}, 다음 HTTP $T_STATUS ${T_CODE:--} — 같은 키의 재응답이 같다는 가정이 틀렸다. $T_RAW"
        fi
      else
        note "첫 취소가 기대한 응답(404 NOT_FOUND_PAYMENT)이 아니어서 같은 멱등 키의 재응답은 판정하지 않았다"
      fi
    fi

    if [ "$dead" = 0 ]; then
      toss_call "$TOSS_TMP/auth-bogus" GET "/v1/payments/orders/$id"
      case "$T_STATUS:$T_CODE" in
        401:UNAUTHORIZED_KEY|401:INVALID_API_KEY|401:INCORRECT_BASIC_AUTH_FORMAT) ok "틀린 키로 조회 → 401 $T_CODE, ${T_MS}ms — 장부는 이것을 확정 거절로 읽는다 (조회에서는 확정으로 쓰지 않는다)" ;;
        000:*) bad "틀린 키로 조회: 토스 서버에 닿지 못했다 (네트워크): $T_RAW"; dead=1 ;;
        *) bad "틀린 키로 조회 → HTTP $T_STATUS ${T_CODE:--} — 기대한 401 UNAUTHORIZED_KEY 가 아니다: 장부의 인증 거절 분류(DEFINITIVE_CODES · CREDENTIAL_CODES)와 어긋난다. $T_RAW" ;;
      esac
    fi

    # 테스트 코드 헤더: 토스는 테스트 시크릿 키로 부를 때 이 헤더의 오류를 그대로 돌려준다. 결제 존재 확인보다 먼저 적용되는지(= 없는 결제에도 통하는지)를 잰다
    if [ "$dead" = 0 ]; then
      toss_call "$TOSS_TMP/auth" POST "/v1/payments/$pkey/cancel" "chkreal-idem-tc-$(date +%s)-$RANDOM" "$cancel_body" "$TOSS_TEST_CODE"
      case "$T_STATUS:$T_CODE" in
        4??:"$TOSS_TEST_CODE") ok "테스트 코드 헤더(TossPayments-Test-Code: $TOSS_TEST_CODE)를 붙인 취소 → HTTP $T_STATUS $T_CODE, ${T_MS}ms — 헤더가 결제 존재 확인보다 먼저 적용된다. $TOSS_TEST_CODE 는 장부의 확정 거절 목록에 있다(DEFINITIVE_CODES, 4xx 일 때)" ;;
        000:*) bad "테스트 코드 취소: 토스 서버에 닿지 못했다 (네트워크): $T_RAW"; dead=1 ;;
        404:NOT_FOUND_PAYMENT) bad "테스트 코드 헤더(TossPayments-Test-Code: $TOSS_TEST_CODE)가 적용되지 않았다 — 없는 결제의 404 NOT_FOUND_PAYMENT 가 먼저 나왔다. 거절 코드의 재현은 결제가 있어야 한다(결제 승인과 함께 잰다). $T_RAW" ;;
        *) bad "테스트 코드 헤더(TossPayments-Test-Code: $TOSS_TEST_CODE)를 붙인 취소 → HTTP $T_STATUS ${T_CODE:--} — 기대한 4xx $TOSS_TEST_CODE 가 아니다(적용되지 않았거나 다른 코드로 옮겨졌다). $T_RAW" ;;
      esac
    fi
    [ "$dead" = 0 ] || note "토스 서버에 닿지 못해 나머지 호출은 보내지 않았다"
  fi

  if [ "$TOSS_MEASURED" = 0 ]; then
    :   # 서버가 답한 호출이 없다 — 잰 것이 없으니 응답 시간을 말하지 않는다
  elif [ "$TOSS_MAX_MS" -gt 5000 ]; then
    note "가장 느린 응답 ${TOSS_MAX_MS}ms — payment-toss 의 응답 제한 기본값 5초를 넘었다. 샘플의 30초 설정과 reconcile.first-delay 가드를 본다 (조회·취소 ${TOSS_MEASURED}번뿐인 값이라 승인 호출의 지연은 아니다)"
  else
    ok "응답 시간: 가장 느린 것 ${TOSS_MAX_MS}ms (호출 ${TOSS_MEASURED}번 — 승인 호출의 지연은 아니다)"
  fi
  rm -rf "$TOSS_TMP"

  # --- 사람이 결제창을 열어야 재는 것 ---------------------------------------------------------------------------------------------
  if [ "$TOSS_SKIPPED" = 1 ]; then
    note "건너뜀: 클라이언트 키(SKELETON_PAYMENT_TOSS_CLIENT_KEY)가 없다 — 결제창을 열 수 없어 결제 승인 · 결제 웹훅 · 부분 취소 응답 · failUrl 모양은 이번에 못 쟀다 (docs/real-provider-setup.md §11 「검증 기록」의 못 잰 가정)."
  else
    note "클라이언트 키가 있다 — 결제 승인은 이 스크립트가 못 한다: 사람이 결제창에 테스트 카드를 넣어야 한다 (docs/real-provider-setup.md §11.4). 승인 뒤 scripts/payment-webhook.sh 로 웹훅 흐름을 넣어 본다"
  fi
}
