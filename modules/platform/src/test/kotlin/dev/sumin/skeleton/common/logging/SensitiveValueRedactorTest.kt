package dev.sumin.skeleton.common.logging

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `redacts configured names headers query strings and json bodies`() {
        val redactor = SensitiveValueRedactor(
            RedactionProperties(
                replacement = "***",
                additionalSensitiveNames = setOf("merchantId"),
            ),
        )

        val headers = redactor.redactHeaders(
            mapOf(
                "Authorization" to listOf("Bearer token-1"),
                "X-Request-Id" to listOf("request-1"),
                "X-Merchant-Id" to listOf("merchant-1"),
            ),
        )
        val query = redactor.redactQueryString("merchantId=merchant-1&status=READY&access_token=token-1")
        val json = redactor.redactJsonString(
            """
            {
              "merchantId": "merchant-1",
              "status": "READY",
              "nested": {
                "password": "secret-1"
              },
              "items": [
                {
                  "apiKey": "api-key-1"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("***"), headers["Authorization"])
        assertEquals(listOf("request-1"), headers["X-Request-Id"])
        assertEquals(listOf("***"), headers["X-Merchant-Id"])
        assertEquals("merchantId=***&status=READY&access_token=***", query)
        assertTrue(json.contains("\"merchantId\":\"***\""))
        assertTrue(json.contains("\"status\":\"READY\""))
        assertFalse(json.contains("merchant-1"))
        assertFalse(json.contains("secret-1"))
        assertFalse(json.contains("api-key-1"))
    }

    @Test
    fun `redacts dto properties annotated as sensitive`() {
        val json = redactor.redactJsonString(
            AnnotatedPaymentPayload(
                orderId = "order-1",
                requestId = UUID.fromString("018f7410-4b5d-7cc3-8c75-980e0b8d36af"),
                createdAt = Instant.parse("2026-06-14T00:00:00Z"),
                paymentKey = "payment-key-1",
                customer = AnnotatedCustomerPayload(
                    email = "user@example.com",
                    externalCredential = "credential-1",
                ),
                history = listOf(
                    AnnotatedPaymentHistory(note = "approved", providerToken = "provider-token-1"),
                ),
            ),
        )

        assertTrue(json.contains("\"orderId\":\"order-1\""))
        assertTrue(json.contains("\"requestId\":\"018f7410-4b5d-7cc3-8c75-980e0b8d36af\""))
        assertTrue(json.contains("\"createdAt\":\"2026-06-14T00:00:00Z\""))
        assertTrue(json.contains("\"email\":\"user@example.com\""))
        assertTrue(json.contains("\"note\":\"approved\""))
        assertTrue(json.contains("\"paymentKey\":\"[REDACTED]\""))
        assertTrue(json.contains("\"externalCredential\":\"[REDACTED]\""))
        assertTrue(json.contains("\"providerToken\":\"[REDACTED]\""))
        assertFalse(json.contains("payment-key-1"))
        assertFalse(json.contains("credential-1"))
        assertFalse(json.contains("provider-token-1"))
    }

    data class AnnotatedPaymentPayload(
        val orderId: String,
        val requestId: UUID,
        val createdAt: Instant,
        @Sensitive val paymentKey: String,
        val customer: AnnotatedCustomerPayload,
        val history: List<AnnotatedPaymentHistory>,
    )

    data class AnnotatedCustomerPayload(
        val email: String,
        @Sensitive val externalCredential: String,
    )

    data class AnnotatedPaymentHistory(
        val note: String,
        @Sensitive val providerToken: String,
    )
}
