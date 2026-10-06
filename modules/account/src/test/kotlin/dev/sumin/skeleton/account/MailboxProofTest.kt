package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.password.BreachedPasswordCheck
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.account.signin.SignInProof
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `sign-up.email-verification=false` 모드 — 가입 직후 ACTIVE 인데 주소는 미확인이다. 그 계정의 주인이 "메일함을 증명"(매직 링크 · 확인된 소셜 · 비밀번호 재설정)하면
 * **남이 심어 둔 어떤 자격도 남지 않아야 한다**: 증명한 수단 외 모든 로그인 수단, 열려 있는 코드 · 링크, 세션 — 한 번의 저장소 연산으로 (docs/accounts.md 위협 모델).
 */
class MailboxProofTest {
    private object Google : SignInMethod { override val code = "google" }
    private object Kakao : SignInMethod { override val code = "kakao" }
    private object Magic : SignInMethod {
        override val code = "magic_link"
        override val provesEmail = true
        override fun normalize(subject: String) = subject.trim().lowercase()
    }

    private var hook: () -> Unit = {}
    private val attacker = "attacker-chosen-42"
    private val ses = "ses_attacker"

    private fun harness(merge: Boolean = false) = AccountHarness(
        AccountProperties(
            signUp = AccountProperties.SignUp(emailVerification = false), social = AccountProperties.Social(signUp = true, mergeOnVerifiedEmail = merge),
            mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4),
        ),
        breached = BreachedPasswordCheck { hook(); false },
    )

    private fun AccountHarness.signIn() = AccountSignInService(core, SignInMethodRegistry(listOf(PasswordSignInMethod(), Google, Kakao, Magic)))

    /** 공격자가 피해자 주소로 가입하고(ACTIVE · 미확인) 자격을 심는다: 소셜 연결 · 열린 이메일 변경 코드 · 다시 인증 코드 · 삭제 코드 · 매직 링크 */
    private fun plant(h: AccountHarness): Account {
        h.signUp("victim@example.com", attacker)
        val planted = h.repo.findByEmail("victim@example.com")!!
        h.signIn().identities.link(planted.id, "google", "attacker-google", true)
        h.emailChange.request(planted.id, "attacker@example.net", ReauthInput(attacker), ses)
        h.deletion.requestConfirmation(planted.id, ses)
        return planted
    }

    private fun assertClean(h: AccountHarness, planted: Account, keep: Set<String>) {
        assertEquals(keep, h.repo.identitiesOf(planted.id).map { it.method }.toSet(), "nothing planted before the proof may survive")
        assertNull(h.challenges.findOpen(ChallengePurposes.EMAIL_CHANGE, planted.id), "the attacker's email-change code is dead")
        assertNull(h.challenges.findOpen(ChallengePurposes.DELETE_CONFIRM, planted.id))
        assertTrue(h.revoker.calls.any { it.first == planted.id }, "sessions are closed")
        assertTrue(h.repo.findById(planted.id)!!.emailVerified)
        assertEquals("ACCOUNT.CODE_EXPIRED", assertFailsWith<ApplicationException> { h.emailChange.confirm(planted.id, ses, "000000") }.errorCode.code)
    }

    @Test
    fun `a magic link proof removes every identity but the proving one and closes the codes planted by the squatter`() {
        val h = harness()
        val planted = plant(h)
        val magic = h.tokens.issue(TokenPurposes.MAGIC_LINK, "victim@example.com", null, Duration.ofMinutes(15))
        h.signIn().signIn(SignInProof("magic_link", "victim@example.com", "victim@example.com", true, ip = "203.0.113.1", allowSignUp = true))
        assertClean(h, planted, setOf("magic_link"))
        assertNull(h.tokens.peek(TokenPurposes.MAGIC_LINK, magic), "other magic links of the address are closed")
    }

    @Test
    fun `a merged social proof does the same - the proving provider stays, the planted one and the password go`() {
        val h = harness(merge = true)
        val planted = plant(h)
        h.signIn().signIn(SignInProof("kakao", "kakao-owner", "victim@example.com", true, ip = "203.0.113.1", allowSignUp = true))
        assertClean(h, planted, setOf("kakao"))
    }

    @Test
    fun `a password reset keeps only the new password - the planted social identity and codes are gone`() {
        val h = harness()
        val planted = plant(h)
        h.passwords.forgot("victim@example.com", "203.0.113.1", null)
        h.passwords.reset(h.mailer.tokenOf(h.mailer.of(MailKind.PASSWORD_RESET).single()), "owner-chosen-pass-9")
        assertClean(h, planted, setOf("password"))
        assertTrue(h.hasher.matches("owner-chosen-pass-9", h.repo.findIdentity("password", "victim@example.com")!!.secret!!))
    }

    @Test
    fun `an already verified account keeps its other sign-in methods on a magic link sign-in`() {
        val h = harness()
        h.signUp("ann@example.com", attacker)
        val a = h.repo.findByEmail("ann@example.com")!!
        h.repo.markEmailVerified(a.id, h.time.now())
        h.signIn().identities.link(a.id, "google", "g-1", true)
        h.signIn().signIn(SignInProof("magic_link", "ann@example.com", "ann@example.com", true, ip = "203.0.113.1", allowSignUp = true))
        assertEquals(setOf("password", "google", "magic_link"), h.repo.identitiesOf(a.id).map { it.method }.toSet())
    }

    @Test
    fun `a password change that races a mailbox proof cannot bring the squatter's new password back`() {
        val h = harness()
        val planted = plant(h)
        // the squatter's change passes the current-password check, then - before the new hash is stored - the owner proves the mailbox
        hook = { h.signIn().signIn(SignInProof("magic_link", "victim@example.com", "victim@example.com", true, ip = "203.0.113.9", allowSignUp = true)); hook = {} }
        assertFailsWith<ApplicationException> { h.passwords.change(planted.id, attacker, "squatter-new-pass-5", ses) }

        val password = h.repo.findIdentity("password", "victim@example.com")
        assertTrue(password == null || !h.hasher.matches("squatter-new-pass-5", password.secret!!), "the squatter's new password must not exist")
        assertTrue(password == null || !h.hasher.matches(attacker, password.secret!!))
    }

    @Test
    fun `a squatter's password change that races the owner's password reset cannot overwrite the owner's new password`() {
        val h = harness()
        val planted = plant(h)
        h.passwords.forgot("victim@example.com", "203.0.113.1", null)
        val resetToken = h.mailer.tokenOf(h.mailer.of(MailKind.PASSWORD_RESET).single())
        hook = {
            hook = {}
            h.passwords.reset(resetToken, "owner-chosen-pass-9")   // the owner completes the reset between the squatter's check and the squatter's write
        }
        assertFailsWith<ApplicationException> { h.passwords.change(planted.id, attacker, "squatter-new-pass-5", ses) }
        val secret = h.repo.findIdentity("password", "victim@example.com")!!.secret!!
        assertTrue(h.hasher.matches("owner-chosen-pass-9", secret), "the owner's password stands")
        assertTrue(!h.hasher.matches("squatter-new-pass-5", secret))
    }
}
