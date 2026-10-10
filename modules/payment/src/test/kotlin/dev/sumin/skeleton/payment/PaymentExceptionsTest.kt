package dev.sumin.skeleton.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 로그(`message`)에는 어느 결제사인지 남고, 응답(`detail`)에는 고정 문장만 나간다. */
class PaymentExceptionsTest {
    @Test
    fun `a missing provider is named in the log message and not in the response detail`() {
        val error = PaymentProviderNotFoundException("toss")

        assertTrue("toss" in error.message.orEmpty(), "the log line names the provider: ${error.message}")
        assertEquals("The requested payment provider is not available.", error.detail)
        assertFalse("toss" in error.detail.orEmpty())
    }

    @Test
    fun `a provider id typed by a client cannot break the log line`() {
        val error = PaymentProviderNotFoundException("x\n[ERROR] forged " + "y".repeat(500))

        assertFalse('\n' in error.message.orEmpty())
        assertTrue(error.message.orEmpty().length < 200)
    }
}
