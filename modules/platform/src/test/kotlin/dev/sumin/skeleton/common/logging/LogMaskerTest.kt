package dev.sumin.skeleton.common.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogMaskerTest {
    private val masker = LogMasker()

    @Test
    fun `bearer and authorization and cookie values are masked`() {
        assertEquals("sent Bearer [REDACTED] ok", masker.mask("sent Bearer some-opaque-token-0123456789 ok"))
        assertEquals("Authorization: [REDACTED]", masker.mask("Authorization: Basic dXNlcjpwYXNz"))
        assertEquals("Cookie: [REDACTED]", masker.mask("Cookie: sid=abc123; theme=dark"))
        assertEquals("Set-Cookie: [REDACTED]", masker.mask("Set-Cookie: sid=abc123; HttpOnly"))
    }

    @Test
    fun `a JWT-looking value is masked even without a name in front of it`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhY2NfdXNlciJ9.c2lnbmF0dXJlLXNpZ25hdHVyZQ"
        assertEquals("login ok [REDACTED] for acc", masker.mask("login ok $jwt for acc"))
    }

    @Test
    fun `sensitive key value pairs are masked in text and json`() {
        assertEquals("password=[REDACTED]&page=7", masker.mask("password=hunter2&page=7"))
        assertEquals("""{"token":"[REDACTED]","n":1}""", masker.mask("""{"token":"abcdef","n":1}"""))
        assertEquals("captchaToken=[REDACTED]", masker.mask("captchaToken=XXXX.DUMMY.TOKEN.XXXX"))
        assertEquals("apiKey: [REDACTED]", masker.mask("apiKey: sk_live_123"))
    }

    @Test
    fun `plain text and ids are left alone`() {
        val s = "Job 12 (translate) failed (attempt 1/5), retry at 2026-10-05T00:00:00Z traceId=0123456789abcdef0123456789abcdef user=alice@example.com"
        assertEquals(s, masker.mask(s))
    }

    @Test
    fun `emails are masked only when asked`() {
        assertEquals("mail to a***@d***.com failed", LogMasker(maskEmails = true).mask("mail to alice@domain.com failed"))
        assertEquals("u***@e***.kr", LogMasker.maskEmail("user@example.co.kr"))
    }

    @Test
    fun `app patterns replace the whole match, or only the named value group`() {
        val custom = LogMasker(patterns = listOf("""[A-Za-z0-9_-]{43}""", """(?i)\bcode=(?<value>\d{4,8})\b"""))
        val token43 = "PQElJrMzK0s9hu3hxzy07qdNJr-hxN27QoZT8HY_uaY"
        assertEquals(43, token43.length)
        assertEquals("edit=[REDACTED] ok", custom.mask("edit=$token43 ok"))
        assertEquals("verification code=[REDACTED] sent", custom.mask("verification code=482913 sent"))
    }

    @Test
    fun `a custom replacement is used everywhere`() {
        assertEquals("password=***", LogMasker(replacement = "***").mask("password=hunter2"))
    }

    @Test
    fun `an invalid app pattern fails fast with its text`() {
        val ex = runCatching { LogMasker(patterns = listOf("(unclosed")) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException && ex.message!!.contains("(unclosed"), ex.toString())
    }

    @Test
    fun `install swaps the masker the logback converters use`() {
        val previous = LogMasker.current
        try {
            LogMasker.install(LogMasker(patterns = listOf("INTERNAL-\\d+")))
            assertEquals("see [REDACTED]", LogMasker.current.mask("see INTERNAL-42"))
            assertFalse(LogMasker.current === previous)
        } finally {
            LogMasker.install(previous)
        }
    }
}
