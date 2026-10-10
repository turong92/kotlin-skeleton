package dev.sumin.skeleton.payment.toss

import kotlin.test.Test
import kotlin.test.assertEquals

class TossPaymentProviderTest {
    @Test
    fun `provider error exposes the code only - no provider message and no raw body`() {
        val pii = """{"code":"REJECT_CARD_PAYMENT","message":"홍길동 hong@example.test 010-1234-5678 4330123412341234","traceId":"trace-1","customerName":"홍길동"}"""
        val exception = TossPaymentErrorMapper(providerId = "toss").map(
            providerErrorContext(clientName = "payment-toss", upstreamStatus = 403, body = pii),
        )

        assertEquals("REJECT_CARD_PAYMENT", exception.providerError.code)
        assertEquals(null, exception.providerError.message)
        assertEquals(null, exception.providerError.rawBody)
        val everywhere = listOf(exception.message, exception.providerError.toString(), exception.data.toString()).joinToString("|")
        listOf("홍길동", "hong@example.test", "010-1234-5678", "4330123412341234").forEach {
            assertEquals(false, everywhere.contains(it), "leaked $it: $everywhere")
        }
    }
}
