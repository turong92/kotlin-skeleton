package dev.sumin.skeleton.payment

import java.time.Instant

data class PaymentAmount(
    val amount: Long,
    val currency: String,
) {
    init {
        require(amount >= 0) { "Payment amount must not be negative" }
        require(currency.isNotBlank()) { "Payment currency must not be blank" }
    }

    val normalizedCurrency: String = currency.trim().uppercase()

    /** 금액과 **정규화한 통화**가 같은가. `==` 는 통화 문자열(대소문자 · 공백)까지 비교하니 결제사 응답과 줄을 비교할 때는 이것을 쓴다. */
    fun sameAs(other: PaymentAmount): Boolean =
        amount == other.amount && normalizedCurrency == other.normalizedCurrency
}

data class IdempotencyKey(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Idempotency key must not be blank" }
    }
}

data class ProviderTrace(
    val provider: String,
    val providerRequestId: String? = null,
    val providerOperationId: String? = null,
    val rawCode: String? = null,
    val rawStatus: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class PaymentConfirmRequest(
    val providerPaymentId: String,
    val amount: PaymentAmount,
    val merchantReferenceId: String? = null,
    val provider: String? = null,
    val country: String? = null,
    val idempotencyKey: IdempotencyKey? = null,
    val providerPayload: Map<String, Any?> = emptyMap(),
    /** 돌아온 결제창이 승인을 허락한 증거(카카오페이 `pg_token`). 없는 결제창(토스)은 null. 값이라 [toString] 에 찍지 않는다. */
    val approvalToken: String? = null,
    /** 이 주문의 주인 계정(결제사에 「가맹점 회원 id」를 보내는 결제사가 쓴다). 장부 밖에서 부르면 null. */
    val accountId: String? = null,
) {
    init {
        require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
    }

    override fun toString(): String =
        "PaymentConfirmRequest(providerPaymentId=$providerPaymentId, amount=$amount, merchantReferenceId=$merchantReferenceId, provider=$provider, country=$country, " +
            "idempotencyKey=$idempotencyKey, providerPayload=$providerPayload, approvalToken=${if (approvalToken == null) "null" else "<redacted>"}, accountId=${if (accountId == null) "null" else "<set>"})"
}

data class PaymentCancelRequest(
    val providerPaymentId: String,
    val amount: PaymentAmount? = null,
    val reason: String? = null,
    val provider: String? = null,
    val country: String? = null,
    val idempotencyKey: IdempotencyKey? = null,
    val providerPayload: Map<String, Any?> = emptyMap(),
) {
    init {
        require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
    }
}

data class PaymentRefundRequest(
    val providerPaymentId: String,
    val amount: PaymentAmount,
    val reason: String? = null,
    val provider: String? = null,
    val country: String? = null,
    val idempotencyKey: IdempotencyKey? = null,
    val providerPayload: Map<String, Any?> = emptyMap(),
) {
    init {
        require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
    }
}

enum class PaymentOperationStatus {
    CONFIRMED,
    CANCELED,
    REFUNDED,
    PENDING,
    FAILED,
    UNKNOWN,
}

data class PaymentOperationResult(
    val provider: String,
    val providerPaymentId: String,
    val status: PaymentOperationStatus,
    val amount: PaymentAmount? = null,
    val trace: ProviderTrace,
    val providerOperationId: String? = trace.providerOperationId,
    val rawProviderStatus: String? = trace.rawStatus,
    val attributes: Map<String, String> = emptyMap(),
    /**
     * 환불 뒤 결제사에 남은 금액. 결제사가 알려 주지 않으면 null.
     * **부분 환불은 [PaymentOperationStatus] 에 값을 더하지 않고 이 값으로 읽는다**(앱의 exhaustive `when` 을 깨지 않으려고):
     * 상태가 [PaymentOperationStatus.REFUNDED] 여도 `remaining > 0` 이면 일부만 환불된 것이다 — 장부는 `remaining` 으로 판단한다.
     */
    val remaining: PaymentAmount? = null,
    /**
     * 승인 응답이 알려 준 카드(카드 결제일 때만). 있으면 장부는 카드 정보를 채우려고 결제사에 한 번 더 묻지 않는다.
     * 결제사 모듈이 코드로 옮기고 끝 네 글자만 남겨 넘긴다([PaymentCard]).
     */
    val card: PaymentCard? = null,
    /** 승인 응답이 알려 준 승인 시각. 있으면 [card] 와 같이 추가 조회를 건너뛰게 한다. */
    val paidAt: Instant? = null,
)

/**
 * 기존 계약: 승인 · 취소 · 환불을 한 인터페이스로. 능력 인터페이스([PaymentConfirmGateway] · [PaymentRefundGateway])의
 * 합이고 멤버 시그니처는 그대로라서 기존 구현은 고치지 않고 컴파일된다.
 * 단, 구현 클래스에 이미 `mode`(다른 타입) 멤버가 있으면 [PaymentGateway.mode] 와 충돌한다.
 */
interface PaymentProvider : PaymentConfirmGateway, PaymentRefundGateway {
    fun cancel(request: PaymentCancelRequest): PaymentOperationResult
}
