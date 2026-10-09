package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.AccountCaptcha
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.DefaultAccountMailTemplates
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.common.ApplicationException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B8 — 아이디 찾기(스펙 §10): `PasswordService.forgot` 과 같은 틀. 요청 스레드는 IP 한도와 캡차만, 주소 조회 · 메일은 뒤에서.
 * 계정 이메일로만, ACTIVE · PENDING_VERIFICATION 에게만, 주소별 한도는 조용히, 이벤트에는 계정 id 만.
 */
class LoginIdReminderTest {
    private val ip = "203.0.113.1"
    private fun props(contact: Boolean = false) = AccountProperties(
        mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        loginId = AccountProperties.LoginId(enabled = true), contactEmail = AccountProperties.ContactEmail(enabled = contact),
    )

    /** 아이디가 있는 가입 완료 계정 */
    private fun AccountHarness.withId(email: String = "ann@example.com", loginId: String = "ann.kim"): Account {
        registration.signUp(SignUpCommand(email, "tangerine-42-moon", null, "ko", null, ip, null, loginId = loginId)).also { signUpIds[email] = it.signUpId!! }
        verify(email)
        return repo.findByEmail(email)!!
    }

    /** 아이디를 아직 안 정한 계정 (소셜로 들어온 뒤 이메일을 등록했거나 기능을 켜기 전에 가입했다) */
    private fun AccountHarness.withoutId(email: String = "old@example.com", status: AccountStatus = AccountStatus.ACTIVE): Account {
        val now = time.now()
        val a = Account("acc_old", email, true, status, setOf("USER"), null, "en", "Asia/Seoul", now, now)
        repo.insert(a, listOf(Identity("idn_old", a.id, "password", email, true, createdAt = now)))
        return a
    }

    private fun AccountHarness.reminders() = mailer.of(MailKind.LOGIN_ID_REMINDER)
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    @Test
    fun `an account with an id gets one mail at its account address - the id and the sign-in page, no token`() {
        val h = AccountHarness(props())
        val a = h.withId()
        h.reminder.forgot("Ann@Example.com", ip, null)
        val mail = h.reminders().single()
        assertEquals("ann@example.com", mail.to)
        assertEquals("ann.kim", mail.vars["loginId"])
        assertEquals("https://app.example.com/login", mail.link, "the sign-in page, not a link that signs anyone in")
        assertFalse("token" in mail.link!!)
        assertEquals(listOf(AccountEventType.LOGIN_ID_REMINDER_SENT), h.events.types().filter { it == AccountEventType.LOGIN_ID_REMINDER_SENT })
        assertEquals(a.id, h.events.all.single { it.type == AccountEventType.LOGIN_ID_REMINDER_SENT }.accountId)
    }

    @Test
    fun `the event carries the account id only - no id value, no address, no detail`() {
        val h = AccountHarness(props())
        h.withId()
        h.reminder.forgot("ann@example.com", ip, null)
        val event = h.events.all.single { it.type == AccountEventType.LOGIN_ID_REMINDER_SENT }
        assertEquals(emptyMap(), event.detail)
        assertFalse("ann.kim" in event.toString() || "ann@example.com" in event.toString(), event.toString())
    }

    @Test
    fun `an account that has not chosen an id still gets the mail - without an id, with the same sign-in page`() {
        val h = AccountHarness(props())
        val a = h.withoutId()
        h.reminder.forgot("old@example.com", ip, null)
        val mail = h.reminders().single()
        assertEquals("old@example.com", mail.to)
        assertNull(mail.vars["loginId"])
        assertEquals("https://app.example.com/login", mail.link)
        assertEquals(a.id, h.events.all.single { it.type == AccountEventType.LOGIN_ID_REMINDER_SENT }.accountId)
    }

    @Test
    fun `a pending-verification account gets it, a suspended or deleted one and an unknown address get nothing`() {
        val h = AccountHarness(props())
        val a = h.withId()
        h.repo.update(a.id, AccountPatch(status = AccountStatus.PENDING_VERIFICATION), h.time.now())
        h.reminder.forgot("ann@example.com", "203.0.113.2", null)
        assertEquals(1, h.reminders().size)
        h.mailer.sent.clear(); h.events.all.clear()
        h.repo.update(a.id, AccountPatch(status = AccountStatus.SUSPENDED), h.time.now())
        h.reminder.forgot("ann@example.com", "203.0.113.3", null)
        h.repo.update(a.id, AccountPatch(status = AccountStatus.DELETED), h.time.now())
        h.reminder.forgot("ann@example.com", "203.0.113.4", null)
        h.reminder.forgot("nobody@example.com", "203.0.113.5", null)
        assertTrue(h.mailer.sent.isEmpty(), h.mailer.sent.toString())
        assertTrue(AccountEventType.LOGIN_ID_REMINDER_SENT !in h.events.types(), "no mail, no event")
    }

    @Test
    fun `the request thread never touches the stores - a known and an unknown address do the same`() {
        val queue = LinkedBlockingQueue<Runnable>()
        val d = AccountHarness(props(), tasks = AccountTaskRunner { _, t -> queue.add(t) })
        d.registration.signUp(SignUpCommand("ann@example.com", "tangerine-42-moon", null, "ko", null, ip, null, loginId = "ann.kim")).also { d.signUpIds["ann@example.com"] = it.signUpId!! }
        queue.forEach { it.run() }; queue.clear(); d.verify()
        d.callLog.calls.clear()
        val me = Thread.currentThread()
        d.reminder.forgot("ann@example.com", ip, null)
        val known = d.callLog.by(me)
        d.reminder.forgot("ghost@example.com", ip, null)
        val unknown = d.callLog.by(me)
        assertEquals(emptyList(), known)
        assertEquals(emptyList(), unknown)
        assertEquals(0, d.reminders().size, "mail only after the queued task runs")
        assertEquals(2, queue.size, "one queued task per request, whoever the address belongs to")
        queue.forEach { it.run() }
        assertEquals(1, d.reminders().size)
    }

    @Test
    fun `the per-address allowance is spent silently - the fourth request in the window sends nothing and still returns`() {
        val h = AccountHarness(props())
        h.withId()
        repeat(6) { h.reminder.forgot("ann@example.com", "203.0.113.${it + 10}", null) }
        assertEquals(3, h.reminders().size, "reset.per-email = 3")
        assertEquals(3, h.events.types().count { it == AccountEventType.LOGIN_ID_REMINDER_SENT })
    }

    @Test
    fun `the per-IP allowance is loud - the eleventh request is 429, whoever the address belongs to`() {
        val h = AccountHarness(props())
        repeat(10) { h.reminder.forgot("x$it@example.com", "198.51.100.1", null) }
        assertFailsWith<RateLimitedException> { h.reminder.forgot("y@example.com", "198.51.100.1", null) }
        h.reminder.forgot("y@example.com", "198.51.100.2", null)
    }

    @Test
    fun `its buckets are its own - password reset requests do not spend the reminder allowance, nor the other way round`() {
        val h = AccountHarness(props())
        h.withId()
        repeat(10) { h.passwords.forgot("ann@example.com", "198.51.100.7", null) }
        assertEquals(3, h.mailer.of(MailKind.PASSWORD_RESET).size)
        h.reminder.forgot("ann@example.com", "198.51.100.7", null)   // the IP is spent for password/forgot, not for this
        assertEquals(1, h.reminders().size, "the address was spent for the reset mail, not for this one")
        repeat(9) { h.reminder.forgot("z$it@example.com", "198.51.100.7", null) }
        assertFailsWith<RateLimitedException> { h.reminder.forgot("z@example.com", "198.51.100.7", null) }
        h.passwords.forgot("nobody@example.com", "198.51.100.8", null)   // the other IP is untouched by the reminder buckets
    }

    @Test
    fun `the captcha is checked on the request thread after the IP allowance, and a failure sends nothing`() {
        val seen = mutableListOf<Triple<String?, String?, String>>()
        val h = AccountHarness(props(), captcha = AccountCaptcha { t, i, action -> seen += Triple(t, i, action); t == "ok" })
        h.withoutId()
        assertEquals("ACCOUNT.CAPTCHA_FAILED", code { h.reminder.forgot("old@example.com", ip, "bad") })
        assertTrue(h.reminders().isEmpty())
        h.reminder.forgot("old@example.com", ip, "ok")
        assertEquals(1, h.reminders().size)
        assertEquals(listOf(Triple<String?, String?, String>("bad", ip, "login_id_forgot"), Triple("ok", ip, "login_id_forgot")), seen)
    }

    @Test
    fun `the mail goes to the account address only - the contact address neither receives it nor finds the account`() {
        val h = AccountHarness(props(contact = true))
        val a = h.withId()
        h.contact.request(a.id, "contact@example.com", "ses_1")
        h.contact.confirm(a.id, "ses_1", h.mailer.of(MailKind.CONTACT_EMAIL_CODE).last().vars.getValue("code"))
        h.mailer.sent.clear()
        h.reminder.forgot("ann@example.com", ip, null)
        assertEquals(mapOf("ann@example.com" to 1), h.mailer.sent.groupingBy { it.to }.eachCount())
        h.mailer.sent.clear()
        h.reminder.forgot("contact@example.com", "203.0.113.9", null)
        assertTrue(h.mailer.sent.isEmpty(), "the contact address is not an account address")
    }

    @Test
    fun `the lookup is by the normalized address`() {
        val h = AccountHarness(props())
        h.withId("ann@example.com", "ann.kim")
        h.reminder.forgot("  ANN@EXAMPLE.COM ", ip, null)
        assertEquals("ann@example.com", h.reminders().single().to)
    }

    // ---- 두 본문 × 두 언어 (골든 파일은 AccountMailGoldenTest, 여기서는 의미)

    private val templates = DefaultAccountMailTemplates(AccountProperties.Mail(linkBaseUrl = "https://app.example.com"))
    private val login = "https://app.example.com/login"

    @Test
    fun `with an id the body names it once and offers the sign-in page - text and html, ko and en`() {
        for (lang in listOf("ko", "en")) {
            val r = templates.render(MailKind.LOGIN_ID_REMINDER, lang, mapOf("loginId" to "ann.kim"), login)
            assertTrue("ann.kim" in r.text && login in r.text, "$lang text: ${r.text}")
            assertTrue("ann.kim" in r.html!! && login in r.html, "$lang html")
            assertFalse("ann.kim" in r.subject, "the subject is logged on a failed send - no id in it")
        }
    }

    @Test
    fun `without an id the body says it was not chosen yet and offers the same page - text and html, ko and en`() {
        val ko = templates.render(MailKind.LOGIN_ID_REMINDER, "ko", emptyMap(), login)
        assertTrue("아직 아이디를 정하지 않았어요." in ko.text && "로그인하면 정할 수 있어요" in ko.text && login in ko.text, ko.text)
        assertTrue("아직 아이디를 정하지 않았어요." in ko.html!! && login in ko.html)
        val en = templates.render(MailKind.LOGIN_ID_REMINDER, "en", emptyMap(), login)
        assertTrue("not chosen a login ID yet" in en.text && login in en.text, en.text)
        assertTrue("not chosen a login ID yet" in en.html!! && login in en.html)
        assertFalse("{loginId}" in ko.text + en.text, "no placeholder left over")
    }

    @Test
    fun `an id is escaped in the html body and a placeholder inside it is not expanded`() {
        val r = templates.render(MailKind.LOGIN_ID_REMINDER, "en", mapOf("loginId" to "<b>a&\"b</b>{minutes}", "minutes" to "10"), login)
        assertFalse("<b>a" in r.html!!, "raw markup in the html body")
        assertTrue("&lt;b&gt;a&amp;&quot;b&lt;/b&gt;{minutes}" in r.html, r.html)
        assertTrue("{minutes}" in r.text, "the value is kept as written in the text part")
    }
}
