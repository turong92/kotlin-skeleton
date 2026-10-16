package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.common.http.ExternalHttpErrorContext
import dev.sumin.skeleton.common.http.ExternalHttpErrorMapper
import dev.sumin.skeleton.payment.ConfirmDeclineCategory
import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.ProviderTrace
import java.net.URI
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * 네이버페이의 오류를 [PaymentProviderException] 으로 옮긴다. 오류는 두 곳에서 온다 - HTTP 오류 상태(이 클래스의 [map], 본문 `{"code": "...", "message": "..."}`)와
 * HTTP 200 안의 `code` 가 `Success` 가 아닌 업무 오류([exceptionFor] 를 모듈이 직접 부른다).
 *
 * **응답 본문은 예외에 싣지 않는다** - `message` 는 사람 글이라 버리고, 꺼내 쓰는 것은 **결과 코드뿐**이며 모양이 맞을 때만 쓴다.
 *
 * **확정 거절은 허용 목록으로만 정한다**([DEFINITIVE_CODES]): 요청 자체를 받아들이지 않았거나 결제를 만들지 않았다고 문서가 말하는 코드이고 **상태가 4xx 일 때만** `definitive=true`.
 * HTTP 200 안의 업무 오류는 확정으로 쓰지 않는다 - 업무 오류가 200 으로 오는지 4xx 로 오는지 문서가 밝히지 않았다(**못 잰 가정**). 앞선 요청이 적용됐을 수 있는 코드(`AlreadyComplete` · `AlreadyOnGoing` ·
 * `AlreadyCanceled` · `OverRemainAmount` · `PreCancelNotComplete` · 멱등 키 재요청의 409)와 확신하지 못한 코드(`Fail`)는 모름이고, 조회 정산이 가린다.
 * **키 거절**(401)은 분류(확정)를 그대로 두고 [credentialAlert] 로 주인에게 알린다 - 키가 폐기된 배포는 모든 결제가 「거절」로만 보이기 때문이다.
 */
class NaverPayErrorMapper(
    private val providerId: String,
    /** false 면 어떤 오류도 확정으로 표시하지 않는다 - 조회의 오류는 돈에 대해 아무것도 말하지 않는다. */
    private val definitiveAllowed: Boolean = true,
    private val mapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build(),
    private val credentialAlert: PaymentProviderCredentialAlert = PaymentProviderCredentialAlert.NONE,
) : ExternalHttpErrorMapper {
    override fun map(context: ExternalHttpErrorContext): PaymentProviderException =
        exceptionFor(
            code = parse(context.upstreamBody)?.code,
            upstreamStatus = context.upstreamStatus,
            traceId = context.trace.traceId,
            clientName = context.clientName,
            method = context.method,
            uri = context.uri,
        )

    fun exceptionFor(code: String?, upstreamStatus: Int, traceId: String?, clientName: String, method: String, uri: URI): PaymentProviderException {
        // 상태가 먼저다: 401 은 본문이 없을 수 있고, 409 는 처음 응답(`Success`)을 되돌려 줄 수 있다 - 둘 다 이 요청은 적용되지 않았다는 말이 아니다
        val resolved = when (upstreamStatus) {
            401 -> UNAUTHORIZED
            409 -> IDEMPOTENT_REPLAY
            else -> code?.takeIf { SAFE_CODE.matches(it) }
        }
        if (resolved == UNAUTHORIZED) {
            // 경보 수단이 던져도 오류 변환은 끝까지 간다 - 승인 · 환불의 결과 판정이 경보 채널에 기대지 않는다
            runCatching { credentialAlert.rejected(providerId) }
        }
        return PaymentProviderException(
            providerError = PaymentProviderError(
                provider = providerId,
                code = resolved,
                message = null,
                trace = ProviderTrace(provider = providerId, providerRequestId = traceId, rawCode = resolved, rawStatus = upstreamStatus.toString()),
                upstreamStatus = upstreamStatus,
                retryable = upstreamStatus == 429 || upstreamStatus >= 500,
                definitive = definitiveAllowed && upstreamStatus in 400..499 && resolved in DEFINITIVE_CODES,
                rawBody = null,
            ),
            clientName = clientName,
            method = method,
            uri = uri,
        )
    }

    private fun parse(body: String): NaverPayErrorBody? = runCatching { mapper.readValue(body, NaverPayErrorBody::class.java) }.getOrNull()

    /** 네이버페이 오류 본문 중 읽는 칸만 - 선언하지 않은 칸(`message` · `body`)은 버려진다. */
    internal data class NaverPayErrorBody(val code: String? = null)

    companion object {
        private val SAFE_CODE = Regex("[A-Za-z0-9_]{1,40}")
        const val UNAUTHORIZED = "Unauthorized"
        const val IDEMPOTENT_REPLAY = "IdempotentReplay"

        /** 문서가 요청 자체를 받아들이지 않았다고 말하는 코드(결제 승인 · 취소의 결과 코드 표, 2026-10-17 읽음). */
        val DEFINITIVE_CODES: Set<String> = setOf(
            UNAUTHORIZED, "RequireParameter", "InvalidParameter", "InvalidMerchant", "InvalidPaymentId",
            "TimeExpired", "OwnerAuthFail", "NotEnoughAccountBalance",
            "CancelDeadlineExpired", "TaxScopeAmtGreaterThanRemainError", "TaxScopeAmountError", "InvalidDiscountCancelCondition",
        )

        /** 결제를 지지 못한 사유 → 화면이 문구를 고르는 분류. 코드는 결제사 모듈만 안다. */
        fun declineCategory(providerCode: String?): ConfirmDeclineCategory =
            when (providerCode) {
                "NotEnoughAccountBalance" -> ConfirmDeclineCategory.LIMIT_OR_BALANCE
                "OwnerAuthFail" -> ConfirmDeclineCategory.CARD_DECLINED
                else -> ConfirmDeclineCategory.OTHER
            }
    }
}
