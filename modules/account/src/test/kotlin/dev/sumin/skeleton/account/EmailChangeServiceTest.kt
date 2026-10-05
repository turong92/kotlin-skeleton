package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmailChangeServiceTest {
    private val h = AccountHarness()
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code
    private fun confirmToken() = h.mailer.tokenOf(h.mailer.of(MailKind.EMAIL_CHANGE_CONFIRM).last())

    @Test
    fun `the new address is verified before anything changes, the old one is told`() {
        val a = h.activeAccount()
        h.emailChange.request(a.id, "New@Example.com", "tangerine-42-moon")

        assertEquals("ann@example.com", h.repo.findById(a.id)!!.email, "nothing changes until the new address confirms")
        val confirm = h.mailer.of(MailKind.EMAIL_CHANGE_CONFIRM).single()
        assertEquals("new@example.com", confirm.to)
        assertTrue(confirm.link!!.startsWith("https://app.example.com/confirm-email-change?token="))
        assertEquals("ann@example.com", h.mailer.of(MailKind.EMAIL_CHANGE_REQUESTED_NOTICE).single().to)
        assertTrue(AccountEventType.EMAIL_CHANGE_REQUESTED in h.events.types())
    }

    @Test
    fun `confirming switches the address and the login id, signs everyone out, and tells the old address`() {
        val a = h.activeAccount()
        h.emailChange.request(a.id, "new@example.com", "tangerine-42-moon")
        h.emailChange.confirm(confirmToken())

        val after = h.repo.findById(a.id)!!
        assertEquals("new@example.com", after.email)
        assertTrue(after.emailVerified)
        assertEquals(a.id, h.repo.findByIdentity("password", "new@example.com")!!.id)
        assertNull(h.repo.findIdentity("password", "ann@example.com"))
        assertEquals(listOf<Pair<String, String?>>(a.id to null), h.revoker.calls)
        assertEquals("ann@example.com", h.mailer.of(MailKind.EMAIL_CHANGED_NOTICE).single().to)
        assertTrue(AccountEventType.EMAIL_CHANGED in h.events.types())
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.emailChange.confirm(confirmToken()) })
    }

    @Test
    fun `the current password is required when the account has one`() {
        val a = h.activeAccount()
        assertEquals("ACCOUNT.CURRENT_PASSWORD_INVALID", code { h.emailChange.request(a.id, "new@example.com", "wrong-password-1") })
        assertEquals("ACCOUNT.CURRENT_PASSWORD_INVALID", code { h.emailChange.request(a.id, "new@example.com", null) })
        assertEquals(0, h.mailer.of(MailKind.EMAIL_CHANGE_CONFIRM).size)
    }

    @Test
    fun `an address that belongs to someone else is answered the same and mailed nothing`() {
        val a = h.activeAccount()
        h.activeAccount("bob@example.com")
        h.mailer.sent.clear()
        h.emailChange.request(a.id, "bob@example.com", "tangerine-42-moon")
        assertEquals(0, h.mailer.sent.size)
    }

    @Test
    fun `if the address was taken in between, confirming is a 409 and changes nothing`() {
        val a = h.activeAccount()
        h.emailChange.request(a.id, "new@example.com", "tangerine-42-moon")
        val token = confirmToken()
        h.activeAccount("new@example.com")
        assertEquals("ACCOUNT.EMAIL_TAKEN", code { h.emailChange.confirm(token) })
        assertEquals("ann@example.com", h.repo.findById(a.id)!!.email)
    }

    @Test
    fun `a change link expires and a newer request closes the older link`() {
        val a = h.activeAccount()
        h.emailChange.request(a.id, "new@example.com", "tangerine-42-moon")
        val first = confirmToken()
        h.emailChange.request(a.id, "newer@example.com", "tangerine-42-moon")
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.emailChange.confirm(first) })
        h.time.advance(Duration.ofMinutes(31))
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.emailChange.confirm(confirmToken()) })
    }

    @Test
    fun `requests are capped per account`() {
        val a = h.activeAccount()
        repeat(5) { h.emailChange.request(a.id, "n$it@example.com", "tangerine-42-moon") }
        assertFailsWith<RateLimitedException> { h.emailChange.request(a.id, "n9@example.com", "tangerine-42-moon") }
    }

    @Test
    fun `an invalid new address is a validation error not a mail`() {
        val a = h.activeAccount()
        assertFailsWith<ApplicationException> { h.emailChange.request(a.id, "not-an-email", "tangerine-42-moon") }
    }

    @Test
    fun `an account without a password changes its address without one`() {
        val now = h.time.now()
        h.repo.insert(Account("acc_s", "s@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        h.emailChange.request("acc_s", "t@example.com", null)
        assertEquals(1, h.mailer.of(MailKind.EMAIL_CHANGE_CONFIRM).size)
    }
}
