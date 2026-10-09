package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.logging.LogMasker
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
        assertEquals("POST /api/v1/payments/webhooks/toss", m.mask("POST /api/v1/payments/webhooks/toss"))
        assertEquals("GET /api/v1/payments/orders/ord_1", m.mask("GET /api/v1/payments/orders/ord_1"))
    }

    @Test
    fun `the rule follows a changed base path and does not touch the old one`() {
        val m = masker("/pay/v2")

        assertEquals("POST /pay/v2/webhooks/toss/[REDACTED]", m.mask("POST /pay/v2/webhooks/toss/secret-piece"))
        assertEquals("POST /api/v1/payments/webhooks/toss/secret-piece", m.mask("POST /api/v1/payments/webhooks/toss/secret-piece"))
    }

    @Test
    fun `a token that holds & quote or apostrophe is hidden to its end, not only up to that character`() {
        val m = masker("/api/v1/payments")

        assertEquals("POST /api/v1/payments/webhooks/toss/[REDACTED]", m.mask("POST /api/v1/payments/webhooks/toss/ab&cd\"ef'gh"))
        assertEquals("→ POST /api/v1/payments/webhooks/toss/[REDACTED] 200 (3ms)", m.mask("→ POST /api/v1/payments/webhooks/toss/ab&cd\"ef'gh 200 (3ms)"))
        assertEquals("/api/v1/payments/webhooks/toss/[REDACTED]", m.maskRequestPath("/api/v1/payments/webhooks/toss/ab&cd\"ef'gh/more?x=1"))
    }

    @Test
    fun `the webhook rule on a hostile path grows about linearly with its length, with and without the cut`() {
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
            listOf<(String) -> String>(m::maskRequestPath, raw::mask).forEachIndexed { r, run -> assertLinear("path #$i rule #$r") { run(path(it)) } }
        }
    }

    /** 입력을 n → 2n 으로 늘렸을 때 시간이 3배 아래(제곱이면 4배)이고 넉넉한 절대 상한(200ms) 안인지 — 기계가 느려도 거짓 실패하지 않게 절대 시간만으로는 가르지 않는다 */
    private fun assertLinear(what: String, run: (Int) -> Unit) {
        fun best(block: () -> Unit) = (1..5).minOf { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }
        run(4000) // 예열
        val small = best { run(4000) }
        val big = best { run(8000) }
        assertTrue(big < 200.0, "$what: 8000 chars took ${big}ms")
        assertTrue(big < 3 * small + 2.0, "$what: doubling the input took ${small}ms -> ${big}ms (quadratic would be 4x)")
    }
}
