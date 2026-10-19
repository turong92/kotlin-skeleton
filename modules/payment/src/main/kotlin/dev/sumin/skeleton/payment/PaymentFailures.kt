package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.http.ExternalHttpException
import dev.sumin.skeleton.common.logging.LogMasker
import dev.sumin.skeleton.common.http.ExternalHttpStatusException
import tools.jackson.databind.json.JsonMapper

/** 결제사 호출이 실패했을 때 돈이 움직였는지 아는가. */
enum class PaymentFailureOutcome {
    /** 결제사 모듈이 "돈이 움직이지 않았다고 결제사가 말했다"고 **명시한** 거절([PaymentProviderError.definitive])이다 — 돈이 움직이지 않았다고 믿어도 된다. */
    DEFINITIVE,

    /** 모른다 — 다시 보내지 말고 결제사에 조회한다. */
    UNKNOWN,
}

object PaymentFailures {
    private const val MAX_CAUSE_DEPTH = 10

    /**
     * 허용 목록 방식이다: 결제사 모듈이 [PaymentProviderError.definitive] 로 표시한 4xx 거절만 [PaymentFailureOutcome.DEFINITIVE],
     * 나머지(타임아웃 · 네트워크 · 5xx · 표시 없는 4xx · 해석 불가 · 모르는 예외)는 전부 [PaymentFailureOutcome.UNKNOWN] 이다.
     * 헷갈리면 모름이다: 모름은 조회 한 번이 더 들 뿐이고, 확정으로 잘못 읽으면 돈이 어긋난다.
     * 표시를 빠뜨린 결제사 모듈은 안전한 쪽(모름)으로 기운다.
     */
    fun outcomeOf(exception: Throwable): PaymentFailureOutcome {
        var current: Throwable? = exception
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            when (current) {
                is PaymentProviderException -> return classify(current.providerError)
                is ExternalHttpException -> return PaymentFailureOutcome.UNKNOWN
            }
            current = current.cause
            depth++
        }
        return PaymentFailureOutcome.UNKNOWN
    }

    private fun classify(error: PaymentProviderError): PaymentFailureOutcome {
        val status = error.upstreamStatus
        val definitive = error.definitive &&
            status in 400..499 &&
            status != 408 &&
            status != 429 &&
            !error.retryable &&
            !error.code.isNullOrBlank()
        return if (definitive) PaymentFailureOutcome.DEFINITIVE else PaymentFailureOutcome.UNKNOWN
    }

    /**
     * 실패 안에 든 결제사의 거절 코드(없으면 null) — 장부의 `failure_code` 와 이력에 **코드만** 남기려고 꺼낸다. 메시지 · 본문은 읽지 않는다.
     * 코드 모양 검사는 쓰는 쪽(장부)이 한다.
     */
    fun providerCodeOf(exception: Throwable): String? {
        var current: Throwable? = exception
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is PaymentProviderException) return current.providerError.code
            current = current.cause
            depth++
        }
        return null
    }

    /** 실패 안에 든 결제사 오류가 「걸렸던 돈을 결제사 모듈이 되돌렸다」([PaymentProviderError.moneyReturned])고 말하는가. */
    fun moneyReturnedOf(exception: Throwable): Boolean {
        var current: Throwable? = exception
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is PaymentProviderException) return current.providerError.moneyReturned
            current = current.cause
            depth++
        }
        return false
    }

    private val SAFE_CODE = Regex("[A-Za-z0-9_.-]{1,40}")

    /** 실패에서 읽을 수 있는 (HTTP 상태, 결제사 오류 코드). 본문 · 메시지는 코드 한 칸만 꺼내고 버린다 - 비밀이나 긴 설명이 로그 · 이력에 새지 않게. */
    private fun statusAndCode(exception: Throwable): Pair<Int?, String?> {
        var current: Throwable? = exception
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            when (current) {
                is PaymentProviderException -> return current.providerError.upstreamStatus to current.providerError.code?.takeIf(SAFE_CODE::matches)
                is ExternalHttpException -> return current.upstreamStatus to ((current as? ExternalHttpStatusException)?.let { codeInBody(it.upstreamBody) })
            }
            current = current.cause
            depth++
        }
        return null to null
    }

    /** Paddle · Stripe 처럼 `{"error":{"code":"…"}}` 모양일 때만. 읽을 수 없거나 모양이 이상하면 null. */
    private fun codeInBody(body: String): String? = try {
        JsonMapper.builder().build().readTree(body)?.get("error")?.get("code")?.takeIf { it.isString }?.asString()?.takeIf(SAFE_CODE::matches)
    } catch (_: Exception) {
        null
    }

    private val REASON_MASKER = LogMasker(maskEmails = true)
    // `LogMasker` 는 이름이 token · secret · password 로 끝나는 쌍만 가린다 - `secret_key=…` · `Authorization: KakaoAK …` 같은 키 쌍은 여기서 더 가린다
    private val KEY_PAIR = Regex("(?i)([a-z0-9_-]{0,32}(?:key|auth|credential)[a-z0-9_-]{0,16}\\s*[=:]\\s*)\\S+")
    private val LONG_DIGITS = Regex("[0-9]{6,}")
    private val CONTROL = Regex("\\p{Cntrl}+")
    private const val REASON_MAX = 200

    /**
     * 결제사가 준 이유 글을 로그에 남길 만큼만 남긴다: 토큰 · 비밀 · 이메일 모양은 가리고 6자리 이상 숫자(카드 · 전화 · 계정 번호)는 `***`, 제어 문자는 공백, 길이는 [REASON_MAX]자.
     * 가릴 게 없는 글이 아니면 쓰지 않는 쪽이 낫다 - 부르는 쪽은 개인정보가 섞일 수 없는 오류(공통 오류 코드)에만 쓴다.
     */
    fun safeReason(text: String?): String? {
        val raw = text?.take(REASON_MAX * 3)?.replace(CONTROL, " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return LONG_DIGITS.replace(KEY_PAIR.replace(REASON_MASKER.mask(raw)) { "${it.groupValues[1]}[REDACTED]" }, "***").take(REASON_MAX)
    }

    private fun reasonOf(exception: Throwable): String? {
        var current: Throwable? = exception
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is PaymentProviderException) return current.providerError.reason
            current = current.cause
            depth++
        }
        return null
    }

    /** 로그용 한 줄 - `status=400 code=transaction_default_checkout_url_not_set` (없는 칸은 `-`). 결제사가 이유를 줬으면 ` reason="…"` 이 붙는다([PaymentProviderError.reason]). */
    fun describe(exception: Throwable): String {
        val (status, code) = statusAndCode(exception)
        val reason = reasonOf(exception)?.let { " reason=\"$it\"" }.orEmpty()
        return "status=${status ?: "-"} code=${code ?: "-"}$reason"
    }

    /** 이력(`payment_events.provider_code`, 64자)에 남길 한 칸 - `400:코드` · 코드가 없으면 `HTTP_상태` · HTTP 실패가 아니면 예외 종류 이름. */
    fun eventCodeOf(exception: Throwable): String {
        val (status, code) = statusAndCode(exception)
        return when {
            status != null && code != null -> "$status:$code"
            status != null -> "HTTP_$status"
            else -> generateSequence(exception) { it.cause }.take(MAX_CAUSE_DEPTH).last().javaClass.simpleName.take(40).ifBlank { "ERROR" }
        }
    }
}
