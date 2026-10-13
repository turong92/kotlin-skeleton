package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.http.ExternalHttpException
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

    /** 로그용 한 줄 - `status=400 code=transaction_default_checkout_url_not_set` (없는 칸은 `-`). */
    fun describe(exception: Throwable): String {
        val (status, code) = statusAndCode(exception)
        return "status=${status ?: "-"} code=${code ?: "-"}"
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
