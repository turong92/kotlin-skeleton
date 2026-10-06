package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.signin.IdentityService
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.account.social.SocialLinkService
import dev.sumin.skeleton.account.social.SocialSignInMethod
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.common.ApplicationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * I5 — 메일함 증명(비밀번호 재설정 · 가입 코드)이 커밋된 **뒤에** 도착하는 쓰기. 증명은 미확인 계정에 남이 심은 수단을 지웠는데,
 * 다시 인증은 증명 **전에** 통과했고 그 뒤 쓰기(제공자 교환을 기다린 소셜 연결 · 이미 열려 있던 이메일 변경 코드)가 증명 뒤에 들어오면 지워진 것이 되살아난다.
 * 계정 행 락 안에서 "다시 인증이 본 이메일 확인 상태" 와 같을 때만 쓰게 해 막는다.
 */
class LateLandingAfterMailboxProofTest {
    private val ses = "ses_1"
    private val now: Instant = Instant.parse("2026-10-06T00:00:00Z")

    private class HookProvider(override val providerId: String, val onFetch: () -> Unit) : OAuthProvider {
        override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile {
            onFetch()
            return OAuthUserProfile(providerId, "$providerId-squatter", null, null, null)
        }
    }

    private fun squatted(h: AccountHarness) {
        h.repo.insert(
            Account("acc_q", "victim@example.com", false, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now),
            listOf(Identity("idn_pw", "acc_q", "password", "victim@example.com", false, secret = h.hasher.hash("squatter-pass-1"), createdAt = now)),
        )
    }

    /** 주인이 재설정 링크로 메일함을 증명했다 — [PasswordService.reset] 의 저장소 연산 그대로 */
    private fun ownerProvesMailbox(h: AccountHarness) {
        check(h.repo.proveMailbox("acc_q", now, MailboxProof(keepIdentityIds = setOf("idn_pw"), passwordSecret = h.hasher.hash("owner-pass-9"), newPasswordIdentityId = "idn_new")))
    }

    @Test
    fun `a social link whose provider exchange straddles the mailbox proof does not resurrect the squatter's link`() {
        val h = AccountHarness()
        squatted(h)
        val provider = HookProvider("alpha") { ownerProvesMailbox(h) }   // the proof lands while the provider is being called
        val methods = SignInMethodRegistry(listOf(PasswordSignInMethod(), SocialSignInMethod("alpha")))
        val links = SocialLinkService(OAuthProviderRegistry(listOf(provider), AuthSocialProperties(providers = mapOf("alpha" to AuthSocialProperties.Provider(enabled = true)))), IdentityService(h.core, methods), h.core)

        val e = assertFailsWith<ApplicationException> { links.link("acc_q", "alpha", "good-1", null, ReauthInput("squatter-pass-1"), ses) }

        assertEquals("ACCOUNT.REAUTH_FAILED", e.errorCode.code)
        assertEquals(listOf("password"), h.repo.identitiesOf("acc_q").map { it.method }, "nothing the squatter added survives the owner's proof")
        assertEquals(true, h.hasher.matches("owner-pass-9", h.repo.findIdentity("password", "victim@example.com")!!.secret!!))
    }

    @Test
    fun `an email-change code requested before the proof cannot be confirmed after it - even if nothing closed the challenge`() {
        val h = AccountHarness()
        squatted(h)
        h.emailChange.request("acc_q", "attacker@example.com", ReauthInput("squatter-pass-1"), ses)
        val code = h.mailer.of(MailKind.EMAIL_CHANGE_CODE).single().vars.getValue("code")
        ownerProvesMailbox(h)   // deliberately WITHOUT closeSensitiveLinks: the window between the proof's commit and the clean-up

        val e = assertFailsWith<ApplicationException> { h.emailChange.confirm("acc_q", ses, code) }

        assertEquals("ACCOUNT.CODE_EXPIRED", e.errorCode.code)
        assertEquals("victim@example.com", h.repo.findById("acc_q")!!.email, "the owner's account keeps the owner's address")
    }

    @Test
    fun `a proof that lands between the confirm's read and its write is caught by the row-locked check`() {
        val real = InMemoryAccountRepository()
        val between = AtomicReference<(() -> Unit)?>(null)
        val storage = object : AccountRepository by real {
            override fun changeEmail(id: String, newEmail: String, now: Instant, expectEmailVerified: Boolean?): ChangeEmailResult {
                between.getAndSet(null)?.invoke()
                return real.changeEmail(id, newEmail, now, expectEmailVerified)
            }
        }
        val h = AccountHarness(storage = storage)
        squatted(h)
        h.emailChange.request("acc_q", "attacker@example.com", ReauthInput("squatter-pass-1"), ses)
        val code = h.mailer.of(MailKind.EMAIL_CHANGE_CODE).single().vars.getValue("code")
        between.set { ownerProvesMailbox(h) }

        val e = assertFailsWith<ApplicationException> { h.emailChange.confirm("acc_q", ses, code) }

        assertEquals("ACCOUNT.CODE_EXPIRED", e.errorCode.code)
        assertEquals("victim@example.com", h.repo.findById("acc_q")!!.email)
    }
}
