package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.logging.LogMasker
import dev.sumin.skeleton.common.logging.RegexBlowupGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebhookLogMaskingTest {
    private fun masker(basePath: String) = webhookPathSecretPattern(basePath).let { LogMasker(listOf(it), pathPatterns = listOf(it)) }

    @Test
    fun `everything after the provider piece is hidden, the provider piece and the rest of the line stay`() {
        val m = masker("/api/v1/payments")

        assertEquals("→ POST /api/v1/payments/webhooks/toss/[REDACTED]", m.mask("→ POST /api/v1/payments/webhooks/toss/Abc.def_123~x"))
        assertEquals("← POST /api/v1/payments/webhooks/toss/[REDACTED] 200 (3ms)", m.mask("← POST /api/v1/payments/webhooks/toss/Abc.def_123~x 200 (3ms)"))
        assertEquals("POST /api/v1/payments/webhooks/toss/[REDACTED]", m.mask("POST /api/v1/payments/webhooks/toss/tok/extra/more"), "a three piece address is hidden to its end")
        assertEquals("POST /api/v1/payments/webhooks/toss/[REDACTED]", m.mask("POST /api/v1/payments/webhooks/toss/ab&cd\"ef'gh"), "& quote and apostrophe are hidden to the end too")
        assertEquals("GET /api/v1/payments/orders/ord_1", m.mask("GET /api/v1/payments/orders/ord_1"))
    }

    @Test
    fun `the webhook rule on a very long hostile path finishes in time, with and without the cut`() {
        val m = masker("/api/v1/payments")
        val raw = LogMasker(listOf(webhookPathSecretPattern("/api/v1/payments")))
        val paths = listOf<(Int) -> String>(
            { n -> "/api/v1/payments/webhooks/x/" + "a/".repeat(n / 2) },
            { n -> "/api/v1/payments/webhooks/" + "a".repeat(n) },
            { n -> "/api/v1/payments/webhooks/x".repeat(n / 27) },
            { n -> "/api/v1/payments/webhooks/x/".repeat(n / 28) },
            { n -> "/api/v1/payments/webhooks/" + "/".repeat(n) },
        )
        paths.forEachIndexed { i, path ->
            listOf<(String) -> String>(m::maskRequestPath, raw::mask).forEachIndexed { r, run -> RegexBlowupGuard.assertFinishesInTime("path #$i rule #$r") { run(path(it)) } }
        }
    }
}
