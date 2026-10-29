package dev.sumin.skeleton.payment.paddle

import dev.sumin.skeleton.common.http.ExternalHttpErrorContext
import dev.sumin.skeleton.common.http.ExternalHttpErrorMapper
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.ProviderTrace
import tools.jackson.databind.json.JsonMapper

/**
 * Paddle 의 오류 응답(`{"error":{"type","code","detail"}}`)을 [PaymentProviderException] 으로 옮긴다. **본문은 예외에 싣지 않는다** - 꺼내 쓰는 것은 모양이 맞는 오류 코드뿐이다(`detail` 은 버린다).
 *
 * **확정 거절은 허용 목록으로만 정한다**([DEFINITIVE_CODES]): 코드가 목록에 있고 4xx 일 때만 `definitive=true`, 그 밖(새로 생긴 코드 · 5xx · 해석 불가)은 모름이다.
 * [definitiveAllowed] 가 false 면 어떤 오류도 확정으로 표시하지 않는다 - 조회의 오류는 돈에 대해 아무것도 말하지 않는다.
 */
class PaddleErrorMapper(
    private val providerId: String,
    private val definitiveAllowed: Boolean = true,
    /** 확정 거절로 읽는 오류 코드(허용 목록) - 호출마다 문서가 「아무 일도 일어나지 않았다」를 말하는 코드가 다르다. 기본은 환불용. */
    private val definitiveCodes: Set<String> = DEFINITIVE_CODES,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) : ExternalHttpErrorMapper {
    override fun map(context: ExternalHttpErrorContext): PaymentProviderException {
        val code = codeOf(context.upstreamBody)
        return PaymentProviderException(
            providerError = PaymentProviderError(
                provider = providerId,
                code = code,
                message = null,
                trace = ProviderTrace(provider = providerId, providerRequestId = requestIdOf(context.upstreamBody), rawCode = code, rawStatus = context.upstreamStatus.toString()),
                upstreamStatus = context.upstreamStatus,
                retryable = context.upstreamStatus == 429 || context.upstreamStatus >= 500,
                definitive = definitiveAllowed && context.upstreamStatus in 400..499 && code in definitiveCodes,
                rawBody = null,
            ),
            clientName = context.clientName,
            method = context.method,
            uri = context.uri,
        )
    }

    private fun codeOf(body: String): String? =
        try {
            mapper.readTree(body)?.get("error")?.get("code")?.takeIf { it.isString }?.asString()?.takeIf(SAFE_CODE::matches)
        } catch (_: Exception) {
            null
        }

    /** Paddle 응답의 요청 id(`meta.request_id`) - 모양이 맞을 때만 쓰고, 없으면 null(우리 traceId 를 대신 싣지 않는다). */
    private fun requestIdOf(body: String): String? =
        try {
            mapper.readTree(body)?.get("meta")?.get("request_id")?.takeIf { it.isString }?.asString()?.takeIf(SAFE_REQUEST_ID::matches)
        } catch (_: Exception) {
            null
        }

    companion object {
        /**
         * 플랜 변경(구독 PATCH)에서 확정인 코드 - 인증 · 권한, 그리고 Paddle 이 변경을 **적용하지 않았다**고 문서가 말하는 막힘(잠금 · 연체 · 한도 · 결제 거절 · 최소 금액).
         * 출처: 같은 오류 코드 목록(2026-10-29 에 읽었다). `subscription_payment_declined` 는 `on_payment_failure` 기본(`prevent_change`)이라 바뀌지 않는다.
         */
        val PLAN_CHANGE_DEFINITIVE_CODES: Set<String> = setOf(
            "invalid_token",
            "forbidden",
            "subscription_payment_declined",
            "subscription_update_transaction_balance_less_than_charge_limit",
        ) + PaddlePlanChangeBlocks.CODES

        private val SAFE_CODE = Regex("[a-z0-9_]{1,80}")
        private val SAFE_REQUEST_ID = Regex("[A-Za-z0-9_\\-]{1,100}")

        /**
         * **돈이 움직이지 않았음이 문서로 분명한 거절 코드만** 확정이다(허용 목록). 출처: Paddle 오류 코드 목록(https://developer.paddle.com/llms/errors.txt, 2026-10-20 에 읽었다)의
         * 환불(adjustment 만들기) 항목 - 요청을 받아들이지 않은 인증 · 권한과, 환불할 수 없는 거래 · 품목 · 조합 · 금액 0.
         *
         * **일부러 뺀 것**(앞선 요청이 적용됐을 수 있다 - 모름): `adjustment_pending_refund_request`(앞선 환불이 검토 중이다) · `adjustment_*_above_remaining_allowed` ·
         * `adjustment_transaction_item_has_already_been_fully_adjusted` · `adjustment_transaction_item_over_adjustment`(남은 금액을 앞선 환불이 이미 썼을 수 있다) · 5xx 전부.
         * 조회가 가린다 - 이중 환불은 막고 모름은 조회 한 번이 더 들 뿐이다. 코드를 더할 때는 문서가 「아무 일도 일어나지 않았다」를 말하는지 먼저 본다.
         */
        val DEFINITIVE_CODES: Set<String> = setOf(
            "invalid_token",
            "forbidden",
            "adjustment_amount_cannot_be_zero",
            "adjustment_payout_earnings_cannot_be_zero",
            "adjustment_transaction_invalid_status_for_refund",
            "adjustment_transaction_without_captured_payment",
            "adjustment_transaction_item_invalid",
            "adjustment_invalid_combination_of_types",
            "adjustment_tax_mode_not_allowed",
            "adjustment_transaction_missing_customer_id",
        )
    }
}

/** 플랜 바꾸기가 막히는 때의 오류 코드 - 우리 쪽에서는 「지금은 바꿀 수 없다」(409)로 읽는다. */
internal object PaddlePlanChangeBlocks {
    val CODES: Set<String> = setOf(
        "subscription_update_when_past_due",
        "subscription_locked_renewal",
        "subscription_locked_processing",
        "subscription_locked_pending_changes",
        "subscription_outstanding_transaction",
        "subscription_outstanding_pending_refund",
        "subscription_immediate_charge_hour_limit_exceeded",
        "subscription_immediate_charge_24_hour_limit_exceeded",
        "subscription_invalid_billing_mode_for_scheduled_change",
    )
}
