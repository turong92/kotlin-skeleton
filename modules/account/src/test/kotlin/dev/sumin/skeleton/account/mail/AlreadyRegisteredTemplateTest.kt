package dev.sumin.skeleton.account.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B1 — "이미 계정이 있어요" 메일의 문구: 가입 수단 · 로그인 · 재설정 · 매직 링크 (ko · en), 값이 없으면 그 줄이 빠진다 */
class AlreadyRegisteredTemplateTest {
    private val templates = DefaultAccountMailTemplates(MailFixtures.props)
    private val full = mapOf(
        "loginUrl" to "https://app.example.com/login", "methods" to "google,password,magic_link,line",
        "resetUrl" to "https://app.example.com/reset-password?token=RESETTOKEN", "resetMinutes" to "30",
        "magicUrl" to "https://app.example.com/magic-link?token=MAGICTOKEN", "magicMinutes" to "15",
    )

    @Test
    fun `korean - one sentence per sign-up method, the login page, the reset link and the one-time link`() {
        val r = templates.render(MailKind.ALREADY_REGISTERED, "ko", full, null)
        listOf("구글로 가입되어 있어요.", "이메일과 비밀번호로 가입되어 있어요.", "이메일 링크로 가입되어 있어요.", "Line(으)로 가입되어 있어요.").forEach { assertTrue(it in r.text, "$it in ${r.text}") }
        listOf("https://app.example.com/login", "RESETTOKEN", "MAGICTOKEN").forEach { assertTrue(it in r.text) }
        assertTrue("30분" in r.text && "15분" in r.text, r.text)
        val html = r.html!!
        assertTrue("href=\"https://app.example.com/login\"" in html)
        assertTrue("href=\"https://app.example.com/reset-password?token=RESETTOKEN\"" in html)
        assertTrue("href=\"https://app.example.com/magic-link?token=MAGICTOKEN\"" in html)
    }

    @Test
    fun `english - same content`() {
        val r = templates.render(MailKind.ALREADY_REGISTERED, "en", full, null)
        listOf("signed up with Google", "signed up with email and password", "signed up with an email link", "signed up with Line").forEach { assertTrue(it in r.text, "$it in ${r.text}") }
        assertTrue("30 minutes" in r.text && "15 minutes" in r.text, r.text)
    }

    @Test
    fun `lines whose values are missing are simply absent - no placeholder is ever printed`() {
        val r = templates.render(MailKind.ALREADY_REGISTERED, "en", mapOf("loginUrl" to "https://app.example.com/login", "methods" to "password"), null)
        assertTrue("https://app.example.com/login" in r.text)
        assertFalse("reset" in r.text.lowercase() && "token" in r.text.lowercase())
        assertFalse(r.text.contains("{") || r.text.contains("}"), r.text)
        assertFalse("one-time" in r.text)
        val bare = templates.render(MailKind.ALREADY_REGISTERED, "en", emptyMap(), null)
        assertTrue("an account already exists" in bare.text && !bare.text.contains("{"))
    }

    @Test
    fun `an odd method code cannot inject markup into the html`() {
        val r = templates.render(MailKind.ALREADY_REGISTERED, "en", mapOf("methods" to "<script>alert(1)</script>"), null)
        assertFalse("<script>" in r.html!!)
        assertEquals(1, Regex("&lt;script&gt;").findAll(r.html!!).count().coerceAtLeast(1))
    }
}
