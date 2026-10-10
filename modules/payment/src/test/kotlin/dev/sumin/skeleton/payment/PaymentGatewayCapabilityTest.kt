package dev.sumin.skeleton.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 게이트웨이 계약 값 객체 — 카드 · 웹훅 봉투가 비밀을 찍지 않는다, 금액 비교, 응답 문구에 결제사 id 가 새지 않는다. 라우팅은 [PaymentProviderRouterTest]. */
class PaymentGatewayCapabilityTest {
    @Test
    fun `card keeps a valid last four, drops anything else, and does not print it`() {
        assertEquals("12**", PaymentCard("HYUNDAI", "12**").last4)
        assertEquals("1234", PaymentCard("HYUNDAI", "1234").last4)
        assertNull(PaymentCard("HYUNDAI", "12345").last4)
        assertNull(PaymentCard("HYUNDAI", "12ab").last4)

        val printed = PaymentCard("HYUNDAI", "9876").toString()
        assertFalse(printed.contains("9876"), printed)
    }

    @Test
    fun `webhook envelope does not print the stored payload`() {
        val envelope = WebhookEnvelope("e1", "T", "payment", "pay_1", storedPayload = "SECRET-RAW-BODY")

        assertFalse(envelope.toString().contains("SECRET-RAW-BODY"), envelope.toString())
        assertEquals("SECRET-RAW-BODY", envelope.storedPayload)
    }

    @Test
    fun `amounts compare by normalized currency with sameAs`() {
        assertTrue(PaymentAmount(1_000, " krw ").sameAs(PaymentAmount(1_000, "KRW")))
        assertFalse(PaymentAmount(1_000, "KRW").sameAs(PaymentAmount(999, "KRW")))
        assertFalse(PaymentAmount(1_000, "KRW").sameAs(PaymentAmount(1_000, "USD")))
    }

    @Test
    fun `exception details - the response text - do not name provider ids or internal interfaces`() {
        val unsupported = PaymentCapabilityUnsupportedException("secret-provider", "PaymentLookupGateway")
        val notFound = PaymentProviderNotFoundException("secret-provider")

        assertFalse(unsupported.detail.orEmpty().contains("secret-provider"))
        assertFalse(unsupported.detail.orEmpty().contains("Gateway"))
        assertFalse(notFound.detail.orEmpty().contains("secret-provider"))
    }
}
