package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 이메일 변경 = 새 주소로 간 6자리 코드를 **요청한 세션에서** 입력 (공개 확인 링크 없음) */
class EmailChangeServiceTest {
    private val h = AccountHarness()
    private val ses = "ses_1"
    private val pw = ReauthInput("tangerine-42-moon")
    private fun err(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }
    private fun code(block: () -> Unit) = err(block).errorCode.code
    private fun codeSent() = h.mailer.of(MailKind.EMAIL_CHANGE_CODE).last().vars.getValue("code")
    private fun wrong() = if (codeSent() == "000000") "000001" else "000000"

    @Test
    fun `the new address gets a code, nothing changes before it is entered, the old one is told`() {
        val a = h.activeAccount()
        h.emailChange.request(a.id, "New@Example.com", pw, ses)

        assertEquals("ann@example.com", h.repo.findById(a.id)!!.email, "nothing changes until the new address confirms")
        val mail = h.mailer.of(MailKind.EMAIL_CHANGE_CODE).single()
        assertEquals("new@example.com", mail.to)
        assertNull(mail.link, "there is no clickable email-change link any more")
        assertTrue(mail.vars.getValue("code").matches(Regex("\\d{6}")))
        assertEquals("ann@example.com", h.mailer.of(MailKind.EMAIL_CHANGE_REQUESTED_NOTICE).single().to)
        assertTrue(AccountEventType.EMAIL_CHANGE_REQUESTED in h.events.types())
    }

    @Test
    fun `entering the code in the same session switches the address and the login id, signs the OTHER sessions out, and tells the old address`() {
        val a = h.activeAccount()
        h.emailChange.request(a.id, "new@example.com", pw, ses)
        h.emailChange.confirm(a.id, ses, codeSent())

        val after = h.repo.findById(a.id)!!
        assertEquals("new@example.com", after.email)
        assertTrue(after.emailVerified)
        assertEquals(a.id, h.repo.findByIdentity("password", "new@example.com")!!.id)
        assertNull(h.repo.findIdentity("password", "ann@example.com"))
        assertEquals(listOf<Pair<String, String?>>(a.id to ses), h.revoker.calls, "the confirming session stays")
        assertEquals("ann@example.com", h.mailer.of(MailKind.EMAIL_CHANGED_NOTICE).single().to)
        assertTrue(AccountEventType.EMAIL_CHANGED in h.events.types())
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.emailChange.confirm(a.id, ses, codeSent()) }, "single use")
    }

    @Test
    fun `a wrong code counts down and the fifth wrong guess spends the request`() {
        val a = h.activeAccount()
        h.emailChange.request(a.id, "new@example.com", pw, ses)
        val right = codeSent()
        for (left in listOf(4, 3, 2, 1)) {
            val e = err { h.emailChange.confirm(a.id, ses, wrong()) }
            assertEquals("ACCOUNT.CODE_INVALID", e.errorCode.code)
            assertEquals(mapOf("attemptsLeft" to left), e.data)
        }
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.emailChange.confirm(a.id, ses, wrong()) })
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.emailChange.confirm(a.id, ses, right) })
        assertEquals("ann@example.com", h.repo.findById(a.id)!!.email)
    }

    @Test
    fun `the code works only in the session that asked and only for that account`() {
        val a = h.activeAccount()
        val b = h.activeAccount("bob@example.com")
        h.emailChange.request(a.id, "new@example.com", pw, ses)
        val sent = codeSent()
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.emailChange.confirm(a.id, "ses_other", sent) }, "a stolen access token in another session cannot confirm")
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.emailChange.confirm(b.id, ses, sent) })
        assertEquals("ann@example.com", h.repo.findById(a.id)!!.email)
    }

    @Test
    fun `the current password is required when the account has one`() {
        val a = h.activeAccount()
        assertEquals("ACCOUNT.CURRENT_PASSWORD_INVALID", code { h.emailChange.request(a.id, "new@example.com", ReauthInput("wrong-password-1"), ses) })
        assertEquals("ACCOUNT.CURRENT_PASSWORD_INVALID", code { h.emailChange.request(a.id, "new@example.com", ReauthInput(), ses) })
        assertEquals(0, h.mailer.of(MailKind.EMAIL_CHANGE_CODE).size)
    }

    @Test
    fun `an address that belongs to someone else is answered the same - no code goes to it, the old address is told as for any request, and entering codes counts down the same`() {
        val a = h.activeAccount()
        h.activeAccount("bob@example.com")
        h.mailer.sent.clear()
        h.emailChange.request(a.id, "bob@example.com", pw, ses)
        assertEquals(0, h.mailer.of(MailKind.EMAIL_CHANGE_CODE).size, "nothing is mailed to the taken address")
        assertEquals(listOf("ann@example.com"), h.mailer.of(MailKind.EMAIL_CHANGE_REQUESTED_NOTICE).map { it.to })
        assertEquals("bob@example.com", h.profile.me(a.id).pendingEmail, "no oracle in the visible state")
        assertEquals(mapOf("attemptsLeft" to 4), err { h.emailChange.confirm(a.id, ses, "123456") }.data, "the same countdown as for a free address")
    }

    @Test
    fun `if the address was taken in between, entering the right code is a 409 and changes nothing`() {
        val a = h.activeAccount()
        h.emailChange.request(a.id, "new@example.com", pw, ses)
        val sent = codeSent()
        h.activeAccount("new@example.com")
        assertEquals("ACCOUNT.EMAIL_TAKEN", code { h.emailChange.confirm(a.id, ses, sent) })
        assertEquals("ann@example.com", h.repo.findById(a.id)!!.email)
    }

    @Test
    fun `a code expires and a newer request replaces the older code`() {
        val a = h.activeAccount()
        h.emailChange.request(a.id, "new@example.com", pw, ses)
        val first = codeSent()
        h.emailChange.request(a.id, "newer@example.com", pw, ses)
        val second = codeSent()
        if (first != second) assertEquals("ACCOUNT.CODE_INVALID", code { h.emailChange.confirm(a.id, ses, first) }, "the replaced code no longer works")
        h.time.advance(Duration.ofMinutes(31))
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.emailChange.confirm(a.id, ses, second) })
    }

    @Test
    fun `requests are capped per account`() {
        val a = h.activeAccount()
        repeat(5) { h.emailChange.request(a.id, "n$it@example.com", pw, ses) }
        assertFailsWith<RateLimitedException> { h.emailChange.request(a.id, "n9@example.com", pw, ses) }
    }

    @Test
    fun `an invalid new address is a validation error not a mail`() {
        val a = h.activeAccount()
        assertFailsWith<ApplicationException> { h.emailChange.request(a.id, "not-an-email", pw, ses) }
    }

    @Test
    fun `an account without a password needs a mailed code to change its address - single use and bound to the session`() {
        val now = h.time.now()
        h.repo.insert(Account("acc_s", "s@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.emailChange.request("acc_s", "t@example.com", ReauthInput(), ses) })
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.emailChange.request("acc_s", "t@example.com", ReauthInput(confirmationCode = "123456"), ses) }, "no code was requested")
        assertEquals(0, h.mailer.of(MailKind.EMAIL_CHANGE_CODE).size)

        h.reauth.requestConfirmation("acc_s", ses)
        val proof = h.mailer.of(MailKind.REAUTH_CODE).single().vars.getValue("code")
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.emailChange.request("acc_s", "t@example.com", ReauthInput(confirmationCode = proof), "ses_other") })
        h.reauth.requestConfirmation("acc_s", ses)
        val fresh = h.mailer.of(MailKind.REAUTH_CODE).last().vars.getValue("code")
        h.emailChange.request("acc_s", "t@example.com", ReauthInput(confirmationCode = fresh), ses)
        assertEquals(1, h.mailer.of(MailKind.EMAIL_CHANGE_CODE).size)
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.emailChange.request("acc_s", "u@example.com", ReauthInput(confirmationCode = fresh), ses) }, "the confirmation is single use")
    }

    @Test
    fun `me exposes the pending change and its expiry until it is confirmed, superseded or expired`() {
        val a = h.activeAccount()
        assertNull(h.profile.me(a.id).pendingEmail)
        h.emailChange.request(a.id, "new@example.com", pw, ses)
        val me = h.profile.me(a.id)
        assertEquals("new@example.com", me.pendingEmail)
        assertEquals(h.time.now().plus(Duration.ofMinutes(30)), me.pendingEmailExpiresAt)

        h.emailChange.request(a.id, "newer@example.com", pw, ses)
        assertEquals("newer@example.com", h.profile.me(a.id).pendingEmail)
        h.time.advance(Duration.ofMinutes(31))
        assertNull(h.profile.me(a.id).pendingEmail, "an expired code is no longer pending")

        h.emailChange.request(a.id, "third@example.com", pw, ses)
        h.emailChange.confirm(a.id, ses, codeSent())
        assertNull(h.profile.me(a.id).pendingEmail)
    }

    @Test
    fun `the pending change is visible before the deferred mail task runs - and the same for a taken address`() {
        val deferred = java.util.concurrent.CopyOnWriteArrayList<Runnable>()
        var hold = false
        val q = AccountHarness(tasks = { _, task -> if (hold) deferred += task else task.run() })
        val a = q.activeAccount()
        q.activeAccount("bob@example.com")
        hold = true

        q.emailChange.request(a.id, "free@example.com", pw, ses)
        assertEquals("free@example.com", q.profile.me(a.id).pendingEmail, "GET /me right after the 202 must show the pending change")
        assertEquals(0, q.mailer.of(MailKind.EMAIL_CHANGE_CODE).size, "only the mail stays deferred")
        val freeView = q.profile.me(a.id)
        q.emailChange.request(a.id, "bob@example.com", pw, ses)
        val takenView = q.profile.me(a.id)
        assertEquals("bob@example.com", takenView.pendingEmail)
        assertEquals(freeView.pendingEmailExpiresAt == null, takenView.pendingEmailExpiresAt == null)
        deferred.forEach { it.run() }
        assertEquals(listOf("free@example.com"), q.mailer.of(MailKind.EMAIL_CHANGE_CODE).map { it.to }, "no code goes to the taken address")
    }

    @Test
    fun `confirming a change also closes the account's open re-authentication and deletion codes and the old address's magic links`() {
        val a = h.activeAccount()
        h.reauth.requestConfirmation(a.id, ses)
        h.deletion.requestConfirmation(a.id, ses)
        val magic = h.tokens.issue(dev.sumin.skeleton.account.token.TokenPurposes.MAGIC_LINK, "ann@example.com", a.id, Duration.ofMinutes(15))
        h.emailChange.request(a.id, "new@example.com", pw, ses)
        h.emailChange.confirm(a.id, ses, codeSent())
        assertNull(h.challenges.findOpen(ChallengePurposes.REAUTH, a.id))
        assertNull(h.challenges.findOpen(ChallengePurposes.DELETE_CONFIRM, a.id))
        assertNull(h.tokens.peek(dev.sumin.skeleton.account.token.TokenPurposes.MAGIC_LINK, magic), "a link for the old address must not outlive the change")
        assertNotNull(h.repo.findById(a.id))
    }
}
