package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** I1 — 비밀번호를 바꾸면 그 전에 나간 민감한 링크가 죽고, 비밀번호 없는 계정의 민감한 일은 메일함으로 다시 인증한다 */
class ReauthTest {
    private val h = AccountHarness()
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    private fun attackerRequestsEmailChange(): String {
        val a = h.activeAccount()
        h.emailChange.request(a.id, "attacker@example.com", "tangerine-42-moon")   // a stolen session + a known password
        return h.mailer.tokenOf(h.mailer.of(MailKind.EMAIL_CHANGE_CONFIRM).single())
    }

    @Test
    fun `changing the password kills a pending email-change link`() {
        val token = attackerRequestsEmailChange()
        val a = h.repo.findByEmail("ann@example.com")!!
        h.passwords.change(a.id, "tangerine-42-moon", "a-brand-new-pass-7", null)   // the victim, warned by the old-address notice
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.emailChange.confirm(token) })
        assertEquals("ann@example.com", h.repo.findById(a.id)!!.email)
    }

    @Test
    fun `resetting the password kills pending email-change, delete-confirmation, reauth and magic links`() {
        val token = attackerRequestsEmailChange()
        val a = h.repo.findByEmail("ann@example.com")!!
        h.deletion.requestConfirmation(a.id)
        h.reauth.requestConfirmation(a.id)
        val magic = h.tokens.issue(TokenPurposes.MAGIC_LINK, "ann@example.com", a.id, Duration.ofMinutes(15))
        val delete = h.mailer.tokenOf(h.mailer.of(MailKind.DELETE_CONFIRM).single())
        val reauth = h.mailer.tokenOf(h.mailer.of(MailKind.REAUTH_CONFIRM).single())
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        h.passwords.reset(h.mailer.tokenOf(h.mailer.of(MailKind.PASSWORD_RESET).single()), "a-brand-new-pass-7")

        assertNull(h.tokens.peek(TokenPurposes.EMAIL_CHANGE, token))
        assertNull(h.tokens.peek(TokenPurposes.DELETE_CONFIRM, delete))
        assertNull(h.tokens.peek(TokenPurposes.REAUTH, reauth))
        assertNull(h.tokens.peek(TokenPurposes.MAGIC_LINK, magic))
    }

    @Test
    fun `a reauth confirmation is mailed to the account address with the reauth path and is capped per account`() {
        val now = h.time.now()
        h.repo.insert(Account("acc_s", "s@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        h.reauth.requestConfirmation("acc_s")
        val mail = h.mailer.of(MailKind.REAUTH_CONFIRM).single()
        assertEquals("s@example.com", mail.to)
        assertTrue(mail.link!!.startsWith("https://app.example.com/confirm-reauth?token="), mail.link)
        repeat(4) { h.reauth.requestConfirmation("acc_s") }
        assertFailsWith<RateLimitedException> { h.reauth.requestConfirmation("acc_s") }
    }

    @Test
    fun `a confirmation issued for one account proves nothing for another`() {
        val now = h.time.now()
        h.repo.insert(Account("acc_a", "a@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        h.repo.insert(Account("acc_b", "b@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        h.reauth.requestConfirmation("acc_a")
        val token = h.mailer.tokenOf(h.mailer.of(MailKind.REAUTH_CONFIRM).single())
        assertEquals("ACCOUNT.REAUTH_FAILED", code { h.emailChange.request("acc_b", "x@example.com", null, token) })
        assertNotNull(h.tokens.peek(TokenPurposes.REAUTH, token), "a failed attempt by someone else does not burn the owner's link")
    }

    @Test
    fun `linking a sign-in method tells the account's address`() {
        val a = h.activeAccount()
        val registry = dev.sumin.skeleton.account.signin.SignInMethodRegistry(listOf(dev.sumin.skeleton.account.signin.PasswordSignInMethod(), object : dev.sumin.skeleton.account.signin.SignInMethod { override val code = "google" }))
        dev.sumin.skeleton.account.signin.IdentityService(h.core, registry).link(a.id, "google", "g-1", verified = true)
        val notice = h.mailer.of(MailKind.IDENTITY_LINKED_NOTICE).single()
        assertEquals("ann@example.com", notice.to)
        assertEquals("google", notice.vars["method"])
    }
}
