package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.challenge.ChallengePurposes
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

/** 비밀번호를 바꾸면 그 전에 나간 민감한 코드 · 링크가 죽고, 비밀번호 없는 계정의 민감한 일은 메일 코드로 다시 인증한다 */
class ReauthTest {
    private val h = AccountHarness()
    private val ses = "ses_1"
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    private fun attackerRequestsEmailChange(): Account {
        val a = h.activeAccount()
        h.emailChange.request(a.id, "attacker@example.com", ReauthInput("tangerine-42-moon"), ses)   // a stolen session + a known password
        return a
    }

    @Test
    fun `changing the password kills a pending email-change code`() {
        val a = attackerRequestsEmailChange()
        val sent = h.mailer.of(MailKind.EMAIL_CHANGE_CODE).single().vars.getValue("code")
        h.passwords.change(a.id, "tangerine-42-moon", "a-brand-new-pass-7", null)   // the victim, warned by the old-address notice
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.emailChange.confirm(a.id, ses, sent) })
        assertEquals("ann@example.com", h.repo.findById(a.id)!!.email)
    }

    @Test
    fun `resetting the password kills pending email-change, delete and reauth codes and magic links`() {
        val a = attackerRequestsEmailChange()
        h.deletion.requestConfirmation(a.id, ses)
        h.reauth.requestConfirmation(a.id, ses)
        val magic = h.tokens.issue(TokenPurposes.MAGIC_LINK, "ann@example.com", a.id, Duration.ofMinutes(15))
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        h.passwords.reset(h.mailer.tokenOf(h.mailer.of(MailKind.PASSWORD_RESET).single()), "a-brand-new-pass-7")

        assertNull(h.challenges.findOpen(ChallengePurposes.EMAIL_CHANGE, a.id))
        assertNull(h.challenges.findOpen(ChallengePurposes.DELETE_CONFIRM, a.id))
        assertNull(h.challenges.findOpen(ChallengePurposes.REAUTH, a.id))
        assertNull(h.tokens.peek(TokenPurposes.MAGIC_LINK, magic))
    }

    @Test
    fun `a reauth code is mailed to the account address as a code - not a link - and capped per account`() {
        val now = h.time.now()
        h.repo.insert(Account("acc_s", "s@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        h.reauth.requestConfirmation("acc_s", ses)
        val mail = h.mailer.of(MailKind.REAUTH_CODE).single()
        assertEquals("s@example.com", mail.to)
        assertNull(mail.link)
        assertTrue(mail.vars.getValue("code").matches(Regex("\\d{6}")))
        repeat(4) { h.reauth.requestConfirmation("acc_s", ses) }
        assertFailsWith<RateLimitedException> { h.reauth.requestConfirmation("acc_s", ses) }
    }

    @Test
    fun `a code issued for one account proves nothing for another and a failed attempt does not burn the owner's code`() {
        val now = h.time.now()
        h.repo.insert(Account("acc_a", "a@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        h.repo.insert(Account("acc_b", "b@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        h.reauth.requestConfirmation("acc_a", ses)
        val sent = h.mailer.of(MailKind.REAUTH_CODE).single().vars.getValue("code")
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.emailChange.request("acc_b", "x@example.com", ReauthInput(confirmationCode = sent), ses) })
        assertNotNull(h.challenges.findOpen(ChallengePurposes.REAUTH, "acc_a"), "the owner's code is still there")
        h.emailChange.request("acc_a", "x@example.com", ReauthInput(confirmationCode = sent), ses)
    }

    @Test
    fun `a code is worth five guesses, then it is dead`() {
        val now = h.time.now()
        h.repo.insert(Account("acc_a", "a@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        h.reauth.requestConfirmation("acc_a", ses)
        val sent = h.mailer.of(MailKind.REAUTH_CODE).single().vars.getValue("code")
        val wrong = if (sent == "000000") "000001" else "000000"
        fun attempt(c: String) = code { h.passwords.change("acc_a", null, "a-brand-new-pass-7", ses, c) }
        repeat(4) { assertEquals("ACCOUNT.CODE_INVALID", attempt(wrong)) }
        assertEquals("ACCOUNT.CODE_EXPIRED", attempt(wrong))
        assertEquals("ACCOUNT.CODE_EXPIRED", attempt(sent))
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
