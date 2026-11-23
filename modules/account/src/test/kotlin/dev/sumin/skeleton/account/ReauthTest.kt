package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 민감한 일 앞의 다시 인증: 비밀번호 줄(포트가 있을 때) · 메일 코드. 비밀번호 변경이 코드를 죽이는 시험은 `account-password` 의 ReauthTest */
class ReauthTest {
    private val h = AccountHarness()
    private val ses = "ses_1"
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

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
        fun attempt(c: String) = code { h.reauth.check("acc_a", ReauthInput(confirmationCode = c), ses) }
        repeat(4) { assertEquals("ACCOUNT.CODE_INVALID", attempt(wrong)) }
        assertEquals("ACCOUNT.CODE_EXPIRED", attempt(wrong))
        assertEquals("ACCOUNT.CODE_EXPIRED", attempt(sent))
    }

    @Test
    fun `linking a sign-in method tells the account's address`() {
        val a = h.activeAccount()
        val registry = dev.sumin.skeleton.account.signin.SignInMethodRegistry(listOf(FakePasswordSignInMethod(), object : dev.sumin.skeleton.account.signin.SignInMethod { override val code = "google" }))
        dev.sumin.skeleton.account.signin.IdentityService(h.core, registry).link(a.id, "google", "g-1", verified = true)
        val notice = h.mailer.of(MailKind.IDENTITY_LINKED_NOTICE).single()
        assertEquals("ann@example.com", notice.to)
        assertEquals("google", notice.vars["method"])
    }

    @Test
    fun `another module can re-authenticate by account id alone - same checks as with the account`() {
        val a = h.activeAccount()
        assertEquals("ACCOUNT.CURRENT_PASSWORD_INVALID", code { h.reauth.check(a.id, ReauthInput("not-the-password"), ses) })
        h.reauth.check(a.id, ReauthInput("tangerine-42-moon"), ses)
        assertEquals("ACCOUNT.NOT_FOUND", code { h.reauth.check("acc_missing", ReauthInput("tangerine-42-moon"), ses) })
    }

    @Test
    fun `without a password port the password line does not exist - an account that still has a password row is re-authenticated by mailed code`() {
        val bare = AccountHarness(passwordCredentials = null)
        val a = bare.activeAccount(password = null)
        bare.repo.addIdentityIfEmailVerified(Identity("idn_pw", a.id, SignInMethods.PASSWORD, "ann@example.com", true, secret = "fake:tangerine-42-moon", createdAt = bare.time.now()), null)
        assertEquals("ACCOUNT.REAUTH_REQUIRED", assertFailsWith<ApplicationException> { bare.reauth.check(a.id, ReauthInput("tangerine-42-moon"), ses) }.errorCode.code, "the password is not even looked at")
        bare.reauth.requestConfirmation(a.id, ses)
        val sent = bare.mailer.of(MailKind.REAUTH_CODE).single().vars.getValue("code")
        bare.reauth.check(a.id, ReauthInput(confirmationCode = sent), ses).commit()
    }
}
