package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode

/**
 * 결제 키 · 주문 번호의 모양을 결제사를 부르기 **전에** 확인한다. 이 값들은 URL 경로(와 본문)에 들어가서, `/` · `..` · `?` · `#` · 공백이 든 값이
 * 다른 경로를 부르거나 요청을 바꿀 수 있다. 토스의 키는 영문 · 숫자 · `-` · `_` 로 이뤄진다(결제 키 최대 200자, 주문 번호 최대 64자).
 */
internal object TossIdentifiers {
    private val PAYMENT_KEY = Regex("[A-Za-z0-9_-]{1,200}")
    private val ORDER_ID = Regex("[A-Za-z0-9_-]{1,64}")

    /** 빌링키 · 카드 등록 인증 값의 문자 집합은 결제 키와 같은 **가정**이다 — 아직 못 쟀다(진짜 발급이 있어야 안다). */
    private val BILLING_KEY = Regex("[A-Za-z0-9_-]{1,200}")
    private val AUTH_KEY = Regex("[A-Za-z0-9_-]{1,200}")
    private val CUSTOMER_KEY = Regex("[A-Za-z0-9_=.@-]{2,50}")

    fun isPaymentKey(value: String): Boolean = PAYMENT_KEY.matches(value)

    /** 모양이 맞으면 그대로, 아니면 [TossIdentifierRejectedException]. */
    fun paymentKey(value: String): String = if (isPaymentKey(value)) value else throw TossIdentifierRejectedException()

    fun orderId(value: String): String = if (ORDER_ID.matches(value)) value else throw TossIdentifierRejectedException()

    fun billingKey(value: String): String = if (BILLING_KEY.matches(value)) value else throw TossIdentifierRejectedException()

    fun authKey(value: String): String = if (AUTH_KEY.matches(value)) value else throw TossIdentifierRejectedException()

    fun customerKey(value: String): String = if (CUSTOMER_KEY.matches(value)) value else throw TossIdentifierRejectedException()
}

/**
 * 결제 키 · 주문 번호가 토스의 모양이 아니어서 결제사를 부르지 않았다 — 호출자 잘못(400)이다.
 * 확정 거절(결제사가 돈이 움직이지 않았다고 말함)로 읽히지 않고, 문장은 고정이다(거절한 값을 싣지 않는다).
 *
 * **장부 경로에서는 사용자에게 닿지 않는다** — 장부가 승인을 선점하기 전에 [TossPaymentProvider.acceptsPaymentKey] 로 먼저 거절하기 때문이다
 * (선점 뒤에 이 예외가 나면 "모름"으로 읽혀 주문이 `CONFIRMING` 에 갇힌다). 이 예외는 장부 없이 공급자를 직접 부르는 경로의 마지막 방어다.
 * 결제 키의 문자 집합(영문 · 숫자 · `-` · `_`)은 문서를 읽고 세운 가정이며 아직 못 쟀다(`docs/real-provider-setup.md` §11.6 「못 잰 가정」) — 틀리면 진짜 토스의 모든 결제가 400 으로 거절된다.
 */
class TossIdentifierRejectedException : ApplicationException(
    message = "The payment identifier is not in a valid form.",
    errorCode = PlatformErrorCode.VALIDATION_FAILED,
)
