package dev.sumin.skeleton.account.mail

import dev.sumin.skeleton.account.AccountProperties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HTML 본문(multipart 의 대체 본문)의 모양과 안전: 모든 값은 이스케이프되고, 제목에는 비밀이 없고, 헤더는 주입되지 않는다 */
class AccountMailHtmlTest {
    private val link = "https://app.example.com/magic-link?token=abc_DEF-123&x=1"
    private val brand = AccountProperties.Mail.Brand(serviceName = "Notes", supportAddress = "help@notes.example", footer = "Notes 팀")
    private fun templates(mail: AccountProperties.Mail = AccountProperties.Mail(brand = brand)) = DefaultAccountMailTemplates(mail)
    private val vars = mapOf("code" to "739518", "minutes" to "10", "days" to "30", "method" to "google")
    private val linkKinds = setOf(MailKind.PASSWORD_RESET, MailKind.MAGIC_LINK)
    private val codeKinds = setOf(MailKind.VERIFY_CODE, MailKind.EMAIL_CHANGE_CODE, MailKind.REAUTH_CODE, MailKind.DELETE_CODE)

    private fun render(kind: MailKind, lang: String, t: AccountMailTemplates = templates(), v: Map<String, String> = vars, l: String? = if (kind in linkKinds) link else null) =
        t.render(kind, lang, v, l)

    @Test
    fun `every kind in ko and en has a table-based html with lang, preheader, max-width 560 and no external assets`() {
        for (kind in MailKind.entries) for (lang in listOf("ko", "en")) {
            val html = assertNotNull(render(kind, lang).html, "$kind/$lang")
            assertContains(html, "<html lang=\"$lang\"")
            assertContains(html, "max-width:560px")
            assertContains(html, "<table")
            assertContains(html, "display:none") // preheader
            assertContains(html, "color-scheme")
            assertFalse(html.contains("<img"), "no logo configured -> no image: $kind/$lang")
            assertFalse(Regex("(src|href)=\"http[^\"]*\"").findAll(html).any { m -> link.replace("&","&amp;") !in m.value && "mailto" !in m.value }, "$kind/$lang external reference")
            assertFalse(html.contains("@import") || html.contains("<link ") || html.contains("<script"), "$kind/$lang")
        }
    }

    @Test
    fun `code mails show the code in a large monospace spaced block, the expiry and the do-not-share line`() {
        for (kind in codeKinds) for (lang in listOf("ko", "en")) {
            val r = render(kind, lang)
            val html = r.html!!
            assertTrue(Regex("<[^>]*monospace[^>]*letter-spacing[^>]*>\\s*739518\\s*<", RegexOption.IGNORE_CASE).containsMatchIn(html), "$kind/$lang code block")
            assertContains(html, "10")
            assertContains(html, if (lang == "ko") "누구에게도 알려주지 마세요" else "Never tell this code to anyone")
            assertContains(r.text, "739518")
        }
    }

    @Test
    fun `link mails have one button plus the raw url as text`() {
        for (kind in linkKinds) for (lang in listOf("ko", "en")) {
            val html = render(kind, lang).html!!
            val escaped = "https://app.example.com/magic-link?token=abc_DEF-123&amp;x=1"
            assertEquals(1, Regex("<a [^>]*href=\"[^\"]*magic-link[^\"]*\"").findAll(html).count(), "$kind/$lang one anchor with the link")
            assertEquals(2, html.split(escaped).size - 1 + 0.coerceAtLeast(0), "$kind/$lang button href + visible text")
        }
    }

    @Test
    fun `footer carries the service name, the support address and the ignore line`() {
        val ko = render(MailKind.PASSWORD_CHANGED, "ko").html!!
        assertContains(ko, "Notes")
        assertContains(ko, "help@notes.example")
        assertContains(ko, "요청하지 않았다면 무시하세요")
        assertContains(render(MailKind.PASSWORD_CHANGED, "en").html!!, "If you did not request this, you can ignore this email.")
    }

    @Test
    fun `brand values are configuration only - accent colour, https logo`() {
        val t = templates(AccountProperties.Mail(brand = brand.copy(accentColor = "#ff0066", logoUrl = "https://cdn.example.com/logo.png")))
        val html = render(MailKind.VERIFY_CODE, "en", t).html!!
        assertContains(html, "#ff0066")
        assertContains(html, "<img src=\"https://cdn.example.com/logo.png\"")
    }

    @Test
    fun `hostile brand values are dropped or escaped - css injection, javascript url, markup`() {
        val t = templates(AccountProperties.Mail(brand = AccountProperties.Mail.Brand(
            serviceName = "<script>alert(1)</script>", logoUrl = "javascript:alert(1)", accentColor = "red;background:url(http://evil)",
            supportAddress = "a@b.c\r\nBcc: x@y.z", footer = "<b>x</b>\r\ny",
        )))
        for (kind in MailKind.entries) {
            val html = render(kind, "ko", t).html!!
            assertFalse(html.contains("<script") || html.contains("javascript:") || html.contains("evil") || html.contains("<b>x"), "$kind")
            assertFalse(html.contains("Bcc:") && html.contains("\r"), "$kind")
            assertFalse(html.contains("<img"), "$kind: a non-https logo is ignored")
        }
    }

    @Test
    fun `hostile values cannot add markup - the tag, attribute and style counts equal the benign render`() {
        val rnd = Random(20261007)
        val alphabet = "<>\"'&;/\\ \r\n\t{}()=:abc가\u0000 "
        fun hostile() = (1..rnd.nextInt(3, 40)).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("") + "<img src=x onerror=alert(1)>\"><script>"
        fun shape(s: String) = listOf("<", ">", "\"", "'", "<a ", "<td", "<tr", "<table").map { s.split(it).size - 1 }
        for (kind in MailKind.entries) for (lang in listOf("ko", "en")) {
            repeat(25) {
                val h = hostile()
                val benignMail = AccountProperties.Mail(brand = AccountProperties.Mail.Brand(serviceName = "x", footer = "x"))
                val hostMail = AccountProperties.Mail(brand = AccountProperties.Mail.Brand(serviceName = h, footer = h))
                val b = render(kind, lang, templates(benignMail), mapOf("code" to "1", "minutes" to "1", "days" to "1", "method" to "x"), if (kind in linkKinds) "https://a.example/p?token=x" else null).html!!
                val x = render(kind, lang, templates(hostMail), mapOf("code" to h, "minutes" to h, "days" to h, "method" to h), if (kind in linkKinds) "https://a.example/p?token=\"$h" else null).html!!
                assertEquals(shape(b), shape(x), "$kind/$lang for '$h'")
                assertFalse(x.contains("<script") || x.contains("<img"), "$kind/$lang for '$h'")
            }
        }
    }

    @Test
    fun `a value that looks like a placeholder is not expanded a second time`() {
        val r = render(MailKind.VERIFY_CODE, "en", v = mapOf("code" to "{minutes}", "minutes" to "10"))
        assertContains(r.text, "{minutes}")
        assertFalse(r.html!!.contains("{minutes}") && r.html!!.contains("10 minutes {minutes}"))
        assertContains(r.text, "10 minutes")
    }

    @Test
    fun `subjects carry no code, token, link or line break whatever the variables are`() {
        val v = mapOf("code" to "739518", "minutes" to "10", "days" to "30", "method" to "google\r\nBcc: x")
        for (kind in MailKind.entries) for (lang in listOf("ko", "en")) {
            val s = render(kind, lang, v = v).subject
            assertFalse("739518" in s || "http" in s || '\r' in s || '\n' in s || "abc_DEF" in s, "$kind/$lang: $s")
        }
    }

    @Test
    fun `an app layout bean replaces only the frame and receives raw values to escape itself`() {
        val layout = AccountMailLayout { page -> "L[" + page.lang + "|" + page.heading + "|" + (page.code ?: "-") + "]" }
        val r = DefaultAccountMailTemplates(AccountProperties.Mail(), layout).render(MailKind.VERIFY_CODE, "en", vars, null)
        assertEquals("L[en|Your verification code|739518]", r.html)
    }

    @Test
    fun `text-only is one property - the mailer drops the html part`() {
        var seen: String? = "unset"
        val transport = AccountMailTransport { _, _, _, html -> seen = html; true }
        val off = AccountProperties.Mail(htmlEnabled = false)
        TemplatedAccountMailer(DefaultAccountMailTemplates(off), transport, off).send(AccountMail(MailKind.VERIFY_CODE, "a@b.co", "en", vars = vars))
        assertNull(seen)
        val on = AccountProperties.Mail()
        TemplatedAccountMailer(DefaultAccountMailTemplates(on), transport, on).send(AccountMail(MailKind.VERIFY_CODE, "a@b.co", "en", vars = vars))
        assertNotNull(seen)
    }

    @Test
    fun `a subject prefix with line breaks cannot inject a header`() {
        var subject = ""
        val p = AccountProperties.Mail(subjectPrefix = "[X]\r\nBcc: evil@example.com")
        TemplatedAccountMailer(DefaultAccountMailTemplates(p), { _, s, _, _ -> subject = s; true }, p).send(AccountMail(MailKind.PASSWORD_CHANGED, "a@b.co", "en"))
        assertFalse('\r' in subject || '\n' in subject, subject)
    }
}
