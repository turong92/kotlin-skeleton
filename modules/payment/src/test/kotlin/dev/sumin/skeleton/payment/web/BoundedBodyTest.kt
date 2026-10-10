package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.PaymentWebhookTooLargeException
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundedBodyTest {
    /** 끝없이 흐르는 본문 — 읽은 바이트 수를 센다. */
    private class Endless : InputStream() {
        var served = 0L

        override fun read(): Int {
            served++
            return 'x'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            java.util.Arrays.fill(b, off, off + len, 'x'.code.toByte())
            served += len
            return len
        }
    }

    @Test
    fun `a body up to the limit is read whole, whatever the declared length says`() {
        val bytes = ByteArray(1000) { it.toByte() }

        assertContentEquals(bytes, BoundedBody.read(ByteArrayInputStream(bytes), declared = -1, maxBytes = 1000), "chunked: no declared length")
        assertContentEquals(bytes, BoundedBody.read(ByteArrayInputStream(bytes), declared = 5, maxBytes = 1000), "a short declared length does not cut the body")
    }

    @Test
    fun `a declared length over the limit is refused without reading a byte`() {
        val stream = Endless()

        assertFailsWith<PaymentWebhookTooLargeException> { BoundedBody.read(stream, declared = 1001, maxBytes = 1000) }

        assertEquals(0, stream.served)
    }

    @Test
    fun `a body that does not say its length stops being read one byte past the limit`() {
        val endless = Endless()

        assertFailsWith<PaymentWebhookTooLargeException> { BoundedBody.read(endless, declared = -1, maxBytes = 1000) }
        assertFailsWith<PaymentWebhookTooLargeException> { BoundedBody.read(Endless().also { it.read() }, declared = 3, maxBytes = 1000) } // 길이를 속여도

        assertTrue(endless.served in 1001..1001 + 4096, "read at most a little past the limit, not the whole stream: ${endless.served}")
    }

}
