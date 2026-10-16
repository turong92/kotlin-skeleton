package dev.sumin.skeleton.payment.kakaopay

import com.fasterxml.jackson.annotation.JsonProperty
import dev.sumin.skeleton.common.http.ExternalHttpErrorContext
import dev.sumin.skeleton.common.http.ExternalHttpErrorMapper
import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.ProviderTrace
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * 카카오페이의 오류 응답(`{"error_code": -780, "error_message": "…", "extras": {"method_result_code": "…"}}`)을 [PaymentProviderException] 으로 옮긴다.
 *
 * **응답 본문은 예외에 싣지 않는다** - `error_message` · `extras.method_result_message` 는 사람 글이라 버리고, 꺼내 쓰는 것은 **오류 코드와 결제 수단의 결과 코드뿐**이며 모양이 맞을 때만 쓴다.
 *
 * **확정 거절은 허용 목록으로만 정한다**([DEFINITIVE_CODES]): 요청 자체를 받아들이지 않은 공통 오류(-400 잘못된 요청 · -401 키 오류 · -403 사용 API 없음)이고 상태가 4xx 일 때만 `definitive=true`.
 * **승인 실패(-780) · 취소 실패(-781)는 일부러 뺐다** - 문서의 예시(`USER_LOCKED` 「진행중인 거래가 있습니다」 · `6666` 「원거래없음」)가 앞선 요청이 처리 중이거나 이미 처리됐다는 뜻일 수 있어, 돈이 움직이지 않았다고 말할 근거가 없다.
 * 모름은 조회 정산이 가린다. 진짜 키로 `extras.method_result_code` 별 의미를 재면 카드 거절 코드를 여기에 더할 수 있다(`docs/real-provider-setup.md` 의 「못 잰 가정」).
 * **키 거절**(-401 · -403)은 분류(확정)를 그대로 두고 [credentialAlert] 로 주인에게 알린다 - 키가 폐기된 배포는 모든 결제가 「거절」로만 보이기 때문이다.
 */
class KakaoPayErrorMapper(
    private val providerId: String,
    /** false 면 어떤 오류도 확정으로 표시하지 않는다 - 조회의 오류는 돈에 대해 아무것도 말하지 않는다. */
    private val definitiveAllowed: Boolean = true,
    private val mapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build(),
    private val credentialAlert: PaymentProviderCredentialAlert = PaymentProviderCredentialAlert.NONE,
) : ExternalHttpErrorMapper {
    override fun map(context: ExternalHttpErrorContext): PaymentProviderException {
        val response = parse(context.upstreamBody)
        val code = response?.errorCode?.toString()?.takeIf { SAFE_CODE.matches(it) }
        val methodResult = response?.extras?.methodResultCode?.takeIf { SAFE_RESULT.matches(it) }
        if (context.upstreamStatus in 400..499 && code in CREDENTIAL_CODES) {
            // 경보 수단이 던져도 오류 변환은 끝까지 간다 - 승인 · 환불의 결과 판정이 경보 채널에 기대지 않는다
            runCatching { credentialAlert.rejected(providerId) }
        }
        return PaymentProviderException(
            providerError = PaymentProviderError(
                provider = providerId,
                code = code,
                message = null,
                trace = ProviderTrace(
                    provider = providerId,
                    providerRequestId = context.trace.traceId,
                    rawCode = code,
                    rawStatus = context.upstreamStatus.toString(),
                    metadata = listOfNotNull(methodResult?.let { "methodResult" to it }).toMap(),
                ),
                upstreamStatus = context.upstreamStatus,
                retryable = context.upstreamStatus == 429 || context.upstreamStatus >= 500,
                definitive = definitiveAllowed && context.upstreamStatus in 400..499 && code in DEFINITIVE_CODES,
                rawBody = null,
            ),
            clientName = context.clientName,
            method = context.method,
            uri = context.uri,
        )
    }

    private fun parse(body: String): KakaoPayErrorBody? = runCatching { mapper.readValue(body, KakaoPayErrorBody::class.java) }.getOrNull()

    /** 카카오페이 오류 본문 중 읽는 칸만 - 선언하지 않은 칸(`error_message` · `extras.method_result_message`)은 버려진다. */
    internal data class KakaoPayErrorBody(
        @JsonProperty("error_code") val errorCode: Int? = null,
        val extras: Extras? = null,
    ) {
        data class Extras(@JsonProperty("method_result_code") val methodResultCode: String? = null)
    }

    companion object {
        private val SAFE_CODE = Regex("-?[0-9]{1,6}")
        private val SAFE_RESULT = Regex("[A-Za-z0-9_]{1,40}")

        /** 우리 키를 받아들이지 않았다는 공통 오류(-401 키 오류 · -403 이 앱에 결제 API 권한 없음) - 사람이 알아야 한다. */
        val CREDENTIAL_CODES: Set<String> = setOf("-401", "-403")

        /** 공통 오류 중 요청 자체를 받아들이지 않은 것(문서: https://developers.kakaopay.com/docs/getting-started/api-common-guide/error-code, 2026-10-16 읽음). */
        val DEFINITIVE_CODES: Set<String> = setOf("-400", "-401", "-403")
    }
}
