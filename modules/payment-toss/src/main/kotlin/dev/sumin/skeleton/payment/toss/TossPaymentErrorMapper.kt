package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpErrorContext
import dev.sumin.skeleton.common.http.ExternalHttpErrorMapper
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.ProviderTrace
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * 토스의 오류 응답을 [PaymentProviderException] 으로 옮긴다.
 *
 * **응답 본문은 예외에 싣지 않는다** — 오류 본문에는 구매자 이름 · 카드 정보가 섞일 수 있어서 꺼내 쓰는 것은 **오류 코드와 추적 번호뿐**이고
 * 그마저도 모양이 맞을 때만(`[A-Z0-9_]`) 쓴다. `message` · `rawBody` 는 늘 null 이다(코드만으로 분류하고 사람은 코드를 토스 문서에서 찾는다).
 *
 * **확정 거절은 허용 목록으로만 정한다**([DEFINITIVE_CODES]): 코드가 목록에 있고 상태가 4xx 일 때만 `definitive=true`,
 * 그 밖(목록에 없는 코드 · 새로 생긴 코드 · 5xx · 해석 불가)은 전부 모름이다. 토스가 코드를 더해도 모름으로 기운다.
 * **키 거절**([CREDENTIAL_CODES], 401 계열 두 코드)은 분류(확정)를 그대로 두고 [credentialAlert] 로 주인에게 알린다 — 키가 폐기된 배포는 모든 결제가 「거절」로만 보이기 때문이다(조회의 오류에도 같다).
 * `retryable` 은 원래 뜻 그대로 "일시 오류일 수 있어 다시 해 볼 만한가"(429 · 5xx · [TRANSIENT_CODES])이고 확정/모름의 판단에는 쓰이지 않는다.
 */
class TossPaymentErrorMapper(
    private val providerId: String,
    /** false 면 어떤 오류도 확정으로 표시하지 않는다 — 조회의 오류는 돈에 대해 아무것도 말하지 않는다. */
    private val definitiveAllowed: Boolean = true,
    private val mapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build(),
    private val credentialAlert: PaymentProviderCredentialAlert = PaymentProviderCredentialAlert.NONE,
) : ExternalHttpErrorMapper {
    override fun map(context: ExternalHttpErrorContext): PaymentProviderException {
        val response = parse(context.upstreamBody)
        val code = (response?.code ?: response?.error)?.takeIf { SAFE_CODE.matches(it) }
        if (context.upstreamStatus in 400..499 && code in CREDENTIAL_CODES) {
            // 경보 수단이 던져도 오류 변환은 끝까지 간다 — 승인 · 환불의 결과 판정이 경보 채널에 기대지 않는다
            runCatching { credentialAlert.rejected(providerId) }
        }
        return PaymentProviderException(
            providerError = PaymentProviderError(
                provider = providerId,
                code = code,
                message = null,
                trace = ProviderTrace(
                    provider = providerId,
                    providerRequestId = response?.traceId?.takeIf { SAFE_TRACE.matches(it) }
                        ?: context.trace.traceId
                        ?: context.upstreamHeaders.tossTraceId(),
                    rawCode = code,
                    rawStatus = context.upstreamStatus.toString(),
                ),
                upstreamStatus = context.upstreamStatus,
                retryable = context.upstreamStatus == 429 || context.upstreamStatus >= 500 || code in TRANSIENT_CODES,
                definitive = definitiveAllowed && context.upstreamStatus in 400..499 && code in DEFINITIVE_CODES,
                rawBody = null,
            ),
            clientName = context.clientName,
            method = context.method,
            uri = context.uri,
        )
    }

    private fun parse(body: String): TossErrorResponse? =
        runCatching {
            mapper.readValue(body, TossErrorResponse::class.java)
        }.getOrNull()

    private data class TossErrorResponse(
        val code: String? = null,
        val message: String? = null,
        val traceId: String? = null,
        val error: String? = null,
    )

    private fun org.springframework.http.HttpHeaders.tossTraceId(): String? =
        TOSS_TRACE_HEADERS.firstNotNullOfOrNull { name ->
            getFirst(name)?.takeIf { it.isNotBlank() }
        }

    companion object {
        private val SAFE_CODE = Regex("[A-Z0-9_]{1,64}")
        private val SAFE_TRACE = Regex("[A-Za-z0-9_.:-]{1,100}")

        /**
         * 우리 키를 받아들이지 않았다는 코드(401 계열 둘) — 분류는 [DEFINITIVE_CODES] 대로 확정이지만 사람이 알아야 한다.
         * **`FORBIDDEN_REQUEST` 는 일부러 뺐다**: 결제 키와 주문 번호가 최초 요청과 다를 때도 올 수 있다는 추정이 있어(실측 전) 로그인 사용자가 만든 요청이 "키를 거절당했다"는 거짓 경보를 낼 수 있다.
         * 분류(확정 거절)는 [DEFINITIVE_CODES] 에 그대로 있다.
         */
        val CREDENTIAL_CODES: Set<String> = setOf("INVALID_API_KEY", "UNAUTHORIZED_KEY")

        /** 일시 오류로 문서화된 코드 — 400 이어도 다시 해 볼 만하다. 확정/모름과는 무관하다(여기 있는 코드는 허용 목록에 없다). */
        val TRANSIENT_CODES: Set<String> = setOf(
            "PROVIDER_ERROR",
            "FAILED_INTERNAL_SYSTEM_PROCESSING",
            "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING",
            "UNKNOWN_PAYMENT_ERROR",
            "IDEMPOTENT_REQUEST_PROCESSING",
        )

        /**
         * **돈이 움직이지 않았음이 문서로 분명한 거절 코드만** 확정이다(허용 목록). 출처: 토스페이먼츠 오류 코드 문서
         * (https://docs.tosspayments.com/reference/error-codes, 2026-10-08 에 「결제 승인」 · 「결제 취소」 표를 읽었다).
         * 근거는 설명 문장이다 — 요청 자체를 받아들이지 않았거나(인증 · 형식 · 없는 결제 · 취소 불가), 카드 · 계좌 승인이 거절됐다.
         *
         * **일부러 뺀 것**(앞선 요청이 적용됐을 수 있거나 확신하지 못한다 — 모름): `ALREADY_PROCESSED_PAYMENT`(승인 호출에서 받으면 돈이 이미 움직였다) ·
         * `ALREADY_PROCESSING_REQUEST` · `IDEMPOTENT_REQUEST_PROCESSING`(앞선 요청이 처리 중) · `ALREADY_CANCELED_PAYMENT` · `ALREADY_REFUND_PAYMENT`
         * (환불 호출에서 받으면 이미 환불됐다) · `FORBIDDEN_CONSECUTIVE_REQUEST`(반복 요청) · `PROVIDER_ERROR` · `CARD_PROCESSING_ERROR`(카드사 오류) ·
         * `NOT_MATCHES_REFUNDABLE_AMOUNT` · `UNAPPROVED_ORDER_ID` · 환불 계좌 · 은행 관련 코드 · 5xx 전부.
         * **환불 쪽에서도 앞선 요청의 결과일 수 있는 코드는 뺐다**: `NOT_CANCELABLE_AMOUNT`(취소 가능 금액 초과 — 앞선 환불이 잔액을 이미 썼을 수 있다) ·
         * `NOT_CANCELABLE_PAYMENT`(취소할 수 없는 결제 — 이미 취소됐을 수 있다) · `EXCEED_CANCEL_LIMIT`(「취소 한도 금액을 초과」 — 앞선 취소가 한도를 이미 썼을 수 있다) ·
         * `REFUND_REJECTED`(공식 설명이 「환불이 거절됐습니다. 결제사에 문의」뿐이라 앞선 환불이 적용됐을 가능성을 배제할 근거가 없다).
         * 남긴 환불 코드는 요청 자체의 성질(일부 환불 불가 · 환불 기간)이라 앞선 요청이 적용됐어도 같은 답이 나오지 않는 것들이다.
         * 조회가 가린다 — 이중 환불은 막고 모름은 조회 한 번이 더 들 뿐이다.
         * 코드를 더할 때는 문서의 설명이 "아무 일도 일어나지 않았다"를 말하는지 먼저 본다.
         */
        val DEFINITIVE_CODES: Set<String> = setOf(
            // 요청 · 인증 — 받아들이지 않았다
            "INVALID_REQUEST",
            "INVALID_API_KEY",
            "UNAUTHORIZED_KEY",
            "INCORRECT_BASIC_AUTH_FORMAT",
            "FORBIDDEN_REQUEST",
            // 없는 결제 · 만료된 결제 세션
            "NOT_FOUND_PAYMENT",
            "NOT_FOUND_PAYMENT_SESSION",
            // 승인 — 카드 · 계좌 거절과 한도
            "REJECT_CARD_PAYMENT",
            "REJECT_CARD_COMPANY",
            "REJECT_ACCOUNT_PAYMENT",
            "INVALID_REJECT_CARD",
            "INVALID_STOPPED_CARD",
            "INVALID_CARD_LOST_OR_STOLEN",
            "INVALID_CARD_NUMBER",
            "INVALID_CARD_EXPIRATION",
            "EXCEED_MAX_AMOUNT",
            "EXCEED_MAX_PAYMENT_AMOUNT",
            "EXCEED_MAX_DAILY_PAYMENT_COUNT",
            "EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT",
            "BELOW_MINIMUM_AMOUNT",
            "FDS_ERROR",
            // 환불 — 취소할 수 없다
            "NOT_ALLOWED_PARTIAL_REFUND",
            "NOT_ALLOWED_PARTIAL_REFUND_WAITING_DEPOSIT",
            "EXCEED_MAX_REFUND_DUE",
        )

        /** 첫 이름이 진짜 토스가 보내는 것이다(2026-10-09 실측 — `scripts/check-real-providers.sh --toss-only`). 나머지는 문서에서 본 이름의 추정이라 남겨 둔다. */
        val TOSS_TRACE_HEADERS = listOf(
            "X-TossPayments-Trace-Id",
            "X-Toss-Trace-Id",
            "Toss-Trace-Id",
            "TossPayments-Trace-Id",
            "X-Request-Id",
        )
    }
}
