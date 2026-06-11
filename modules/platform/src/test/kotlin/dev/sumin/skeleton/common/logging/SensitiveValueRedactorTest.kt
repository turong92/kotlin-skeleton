package dev.sumin.skeleton.common.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SensitiveValueRedactorTest {
    private val redactor = SensitiveValueRedactor()

    @Test
    fun `redacts common secret field names`() {
        assertEquals("[REDACTED]", redactor.redact("Authorization", "Bearer token"))
        assertEquals("[REDACTED]", redactor.redact("accessToken", "token-1"))
        assertEquals("[REDACTED]", redactor.redact("refresh_token", "token-2"))
        assertEquals("[REDACTED]", redactor.redact("password", "secret"))
        assertEquals("[REDACTED]", redactor.redact("apiKey", "key-1"))
        assertEquals("[REDACTED]", redactor.redact("client_secret", "secret-1"))
    }

    @Test
    fun `keeps non-secret fields and nulls`() {
        assertEquals("trace-1", redactor.redact("traceId", "trace-1"))
        assertEquals("order-1", redactor.redact("orderId", "order-1"))
        assertNull(redactor.redact("token", null))
    }

    @Test
    fun `redacts map values by key`() {
        val redacted = redactor.redact(
            mapOf(
                "orderId" to "order-1",
                "password" to "secret",
                "empty" to null,
            ),
        )

        assertEquals("order-1", redacted["orderId"])
        assertEquals("[REDACTED]", redacted["password"])
        assertNull(redacted["empty"])
    }
}
