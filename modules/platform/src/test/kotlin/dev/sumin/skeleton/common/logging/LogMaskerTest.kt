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

    @Test
    fun `the default rules still hide what they hid before the repeats were bounded, inside and outside the bounds`() {
        val longKey = "a".repeat(100) + "Token"
        assertEquals("$longKey=[REDACTED]&x=1", masker.mask("$longKey=hunter2&x=1"), "a key name longer than the 64 character prefix is still hidden")
        assertEquals("""{"my-long_api-key_token":"[REDACTED]"}""", masker.mask("""{"my-long_api-key_token":"abc"}"""))
        val jwt = "eyJ" + "A".repeat(300) + "." + "B".repeat(3000) + "." + "C".repeat(600)
        assertEquals("login [REDACTED] ok", masker.mask("login $jwt ok"), "a long header, payload and signature are one JWT")
        assertEquals("x=[REDACTED]", masker.mask("x=eyJhbGciOiJI.eyJzdWIiOiIx."))
    }

    @Test
    fun `a JWT larger than the bounded repeats is hidden whole, not left in the clear`() {
        fun part(n: Int) = "A".repeat(n)
        mapOf(
            "header 600" to "eyJ${part(600)}.${part(100)}.${part(43)}",
            "header 4000 (x5c chain)" to "eyJ${part(4000)}.${part(100)}.${part(43)}",
            "payload 9000" to "eyJ${part(36)}.${part(9000)}.${part(43)}",
            "signature 3000" to "eyJ${part(36)}.${part(100)}.${part(3000)}",
        ).forEach { (shape, jwt) ->
            assertEquals("login [REDACTED] ok", masker.mask("login $jwt ok"), shape)
        }
    }

    @Test
    fun `a quoted key name longer than the 64 character prefix is hidden like an unquoted one`() {
        val key = "k".repeat(70) + "_token"
        assertEquals("""{"$key":"[REDACTED]"}""", masker.mask("""{"$key":"abc"}"""), "quoted")
        assertEquals("$key=[REDACTED]", masker.mask("$key=abc"), "unquoted")
        assertEquals("""{'$key': '[REDACTED]'}""", masker.mask("""{'$key': 'abc'}"""), "single quoted")
    }

    @Test
    fun `masking a very long hostile line finishes in time for every default rule - no quadratic or worse backtracking`() {
        val lines = listOf<(Int) -> String>(
            { n -> "/" + "a-".repeat(n / 2) },
            { n -> "/" + "eyJ-".repeat(n / 4) },
            { n -> "eyJaaaaa.".repeat(n / 9) },
            { n -> "eyJaaaaa." + "eyJ".repeat((n - 9) / 3) },
            { n -> "token ".repeat(n / 6) },
            { n -> "cookie ".repeat(n / 7) },
            { n -> "Bearer ".repeat(n / 7) },
            { n -> "a.".repeat(n / 2) },
        )
        listOf(LogMasker(), LogMasker(maskEmails = true)).forEach { m ->
            lines.forEachIndexed { i, line -> RegexBlowupGuard.assertFinishesInTime("rule line #$i") { m.mask(line(it)) } }
        }
    }

    @Test
    fun `maskRequestPath returns the path itself when no path rule is set, whatever the other rules are`() {
        val m = LogMasker(patterns = listOf("(?<value>APPSECRET)"), maskEmails = true)
        listOf("/a/token=abc", "/a/eyJhbGciOi.eyJzdWIiOi.c2ln", "/APPSECRET/me@example.com", "/" + "x".repeat(2000)).forEach { assertEquals(it, m.maskRequestPath(it)) }
    }

    @Test
    fun `maskRequestPath cuts first and applies only the path rules`() {
        val m = LogMasker(patterns = listOf("(?<value>APPSECRET)", "/hooks/(?<value>\\S+)"), pathPatterns = listOf("/hooks/(?<value>\\S+)"))
        assertEquals("/hooks/[REDACTED]", m.maskRequestPath("/hooks/s3cr3t"))
        assertEquals("/a/token=abc/APPSECRET", m.maskRequestPath("/a/token=abc/APPSECRET"), "default and app rules do not run on a path")
        val cut = m.maskRequestPath("/p" + "y".repeat(600))
        assertEquals(("/p" + "y".repeat(510)) + LogMasker.TRUNCATED_MARK, cut)
    }
}
