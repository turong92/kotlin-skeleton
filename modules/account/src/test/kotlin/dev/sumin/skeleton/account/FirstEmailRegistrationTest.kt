package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `skeleton.account.email.required=true` 의 온보딩: 이메일 없이 만들어진 계정(Kakao · Naver · X · 이메일 동의를 안 한 소셜)이
 * **재인증 없이** 첫 이메일을 등록한다 — 단 로그인한 지 얼마 안 됐을 때(창)만, 그리고 계정에 이메일이 한 번도 없었을 때만.
 */
class FirstEmailRegistrationTest {
    private fun harness(window: Duration = Duration.ofHours(1), required: Boolean = true, social: SocialReauthVerifier? = null) = AccountHarness(
        socialReauth = social,
        props = AccountProperties(
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"),
            password = AccountProperties.Password(bcryptStrength = 4),
            email = AccountProperties.Email(required = required, firstRegistrationWindow = window),
        ),
    )

    private val ses = "ses_1"
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    /** 방금 소셜로 로그인한(세션 [ses] 가 지금 열렸다) 이메일 없는 계정 */
    private fun AccountHarness.emailless(id: String = "acc_k"): Account {
        val now = time.now()
        val account = Account(id, null, false, AccountStatus.ACTIVE, setOf("USER"), "K", null, null, now, now, lastLoginAt = now)
        repo.insert(account, listOf(Identity("idn_$id", id, "kakao", "kakao-$id", true, createdAt = now)))
        sessions.open(id, ses)
        return account
    }

    private fun AccountHarness.sentCode() = mailer.of(MailKind.EMAIL_CHANGE_CODE).last().vars.getValue("code")

    @Test
    fun `an address-less account that just signed in registers its first address without re-authenticating`() {
        val h = harness()
        val a = h.emailless()

        val window = h.emailChange.request(a.id, "Kim@Example.com", ReauthInput(), ses)

        assertEquals("kim@example.com", h.mailer.of(MailKind.EMAIL_CHANGE_CODE).single().to, "the code goes to the typed address")
        assertEquals(h.time.now().plus(Duration.ofMinutes(10)), window.expiresAt)
        assertNull(h.repo.findById(a.id)!!.email, "nothing changes before the code is entered")

        h.emailChange.confirm(a.id, ses, h.sentCode())

        val after = h.repo.findById(a.id)!!
        assertEquals("kim@example.com", after.email)
        assertTrue(after.emailVerified)
        assertEquals(listOf<Pair<String, String?>>(a.id to ses), h.revoker.calls, "the other sessions are closed, the registering one stays")
        assertTrue(AccountEventType.EMAIL_CHANGED in h.events.types())
        assertEquals("first_registration", h.events.all.last { it.type == AccountEventType.EMAIL_CHANGED }.detail["via"])
        assertTrue(h.mailer.of(MailKind.EMAIL_CHANGED_NOTICE).isEmpty(), "there was no old address to tell")
    }

    @Test
    fun `with required off the same request still needs the provider re-authentication - the module default is unchanged`() {
        val h = harness(required = false)
        val a = h.emailless()
        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.emailChange.request(a.id, "kim@example.com", ReauthInput(), ses) })
        assertTrue(h.mailer.of(MailKind.EMAIL_CHANGE_CODE).isEmpty())
    }

    @Test
    fun `after the window the exemption is gone and the provider re-authentication is asked again`() {
        val h = harness()
        val a = h.emailless()
        h.time.advance(Duration.ofHours(1).plusSeconds(1))
        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.emailChange.request(a.id, "kim@example.com", ReauthInput(), ses) })
        assertTrue(h.mailer.of(MailKind.EMAIL_CHANGE_CODE).isEmpty())
    }

    @Test
    fun `a new login - a new session - reopens the window, the window is measured from that session's login`() {
        val h = harness()
        val a = h.emailless()
        h.time.advance(Duration.ofDays(3))
        h.sessions.open(a.id, "ses_2")
        h.emailChange.request(a.id, "kim@example.com", ReauthInput(), "ses_2")
        assertEquals(1, h.mailer.of(MailKind.EMAIL_CHANGE_CODE).size)
    }

    @Test
    fun `a stale session does not inherit the window when the owner logs in somewhere else - the account-level last login is not the proof`() {
        val h = harness()
        val a = h.emailless()   // session ses_1: opened by the attacker long ago (shared PC, leaked refresh token that keeps rotating)
        h.time.advance(Duration.ofDays(3))
        h.sessions.open(a.id, "ses_owner")   // the owner signs in on another device now: the account's last login is fresh
        h.repo.update(a.id, AccountPatch(lastLoginAt = h.time.now()), h.time.now())

        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.emailChange.request(a.id, "attacker@example.com", ReauthInput(), ses) }, "the old session gets the provider re-authentication, not the exemption")
        assertTrue(h.mailer.of(MailKind.EMAIL_CHANGE_CODE).isEmpty())
        h.emailChange.request(a.id, "owner@example.com", ReauthInput(), "ses_owner")   // the session that just logged in is exempt
        assertEquals("owner@example.com", h.mailer.of(MailKind.EMAIL_CHANGE_CODE).single().to)
    }

    @Test
    fun `without a session store or with a closed or unknown session there is no exemption - the provider re-authentication is asked`() {
        val h = harness()
        val a = h.emailless()
        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.emailChange.request(a.id, "kim@example.com", ReauthInput(), null) }, "a JWT-only access token carries no session")
        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.emailChange.request(a.id, "kim@example.com", ReauthInput(), "ses_unknown") })
        assertTrue(h.mailer.of(MailKind.EMAIL_CHANGE_CODE).isEmpty())
    }

    @Test
    fun `a zero window turns the exemption off`() {
        val h = harness(window = Duration.ZERO)
        val a = h.emailless()
        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.emailChange.request(a.id, "kim@example.com", ReauthInput(), ses) })
    }

    @Test
    fun `an account that already has an address is never exempt - changing it needs the usual proof`() {
        val h = harness()
        val a = h.activeAccount()
        h.repo.update(a.id, AccountPatch(lastLoginAt = h.time.now()), h.time.now())
        assertEquals("ACCOUNT.CURRENT_PASSWORD_INVALID", code { h.emailChange.request(a.id, "new@example.com", ReauthInput(), ses) })
        assertTrue(h.mailer.of(MailKind.EMAIL_CHANGE_CODE).isEmpty())
    }

    @Test
    fun `an address that belongs to another account gets the already-registered mail and no code - the answer is the same 202 window`() {
        val h = harness()
        val owner = h.activeAccount("owner@example.com")
        val a = h.emailless()

        val taken = h.emailChange.request(a.id, "owner@example.com", ReauthInput(), ses)
        val free = h.emailChange.request(a.id, "free@example.com", ReauthInput(), ses)

        assertEquals(free.expiresAt, taken.expiresAt, "expiresAt does not tell")
        assertEquals(free.resendAvailableAt, taken.resendAvailableAt, "resendAvailableAt does not tell")
        assertTrue(h.mailer.of(MailKind.ALREADY_REGISTERED).isEmpty(), "the sign-up wording (someone tried to sign up) does not fit this situation")
        val notices = h.mailer.of(MailKind.EMAIL_REGISTRATION_ATTEMPT)
        assertEquals("owner@example.com", notices.single().to)
        assertNull(notices.single().link, "no token in this mail")
        assertEquals("free@example.com", h.mailer.of(MailKind.EMAIL_CHANGE_CODE).single().to, "only the free address got a code")
        assertEquals(owner.email, h.repo.findById(owner.id)!!.email)
    }

    @Test
    fun `an old session that re-authenticated with the provider still tells the owner of a taken address`() {
        val h = harness(window = Duration.ZERO, social = SocialReauthVerifier { _, proof -> proof.authorizationCode == "fresh" })
        h.activeAccount("owner@example.com")
        val a = h.emailless()

        h.emailChange.request(a.id, "owner@example.com", ReauthInput(social = SocialReauth("kakao", "fresh")), ses)

        assertEquals("owner@example.com", h.mailer.of(MailKind.EMAIL_REGISTRATION_ATTEMPT).single().to, "the first registration of an address-less account, exempt or not")
        assertTrue(h.mailer.of(MailKind.EMAIL_CHANGE_CODE).isEmpty())
    }

    @Test
    fun `a taken address can never be confirmed - no code exists, guesses fail like on a free address`() {
        val h = harness()
        h.activeAccount("owner@example.com")
        val a = h.emailless()
        h.emailChange.request(a.id, "owner@example.com", ReauthInput(), ses)
        assertEquals("ACCOUNT.CODE_INVALID", code { h.emailChange.confirm(a.id, ses, "000000") })
        assertNull(h.repo.findById(a.id)!!.email)
    }

    @Test
    fun `an address the operator erased is refused when the code is entered`() {
        val h = harness()
        val a = h.emailless()
        val admin = h.activeAccount("admin@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val gone = h.activeAccount("gone@example.com")
        h.admin.suspend(admin.id, gone.id, "fraud")
        assertTrue(h.purge.eraseSuspended(admin.id, gone.id, "fraud"))
        h.emailChange.request(a.id, "gone@example.com", ReauthInput(), ses)
        assertEquals("ACCOUNT.REGISTRATION_BLOCKED", code { h.emailChange.confirm(a.id, ses, h.sentCode()) })
        assertNull(h.repo.findById(a.id)!!.email)
    }

    @Test
    fun `the code is bound to the session that asked`() {
        val h = harness()
        val a = h.emailless()
        h.emailChange.request(a.id, "kim@example.com", ReauthInput(), ses)
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.emailChange.confirm(a.id, "ses_other", h.sentCode()) })
        assertNull(h.repo.findById(a.id)!!.email)
    }

    @Test
    fun `me says an address is still needed only while the setting is on and the account has none`() {
        val on = harness()
        val a = on.emailless()
        assertEquals(true, on.profile.me(a.id).emailRequired)
        on.emailChange.request(a.id, "kim@example.com", ReauthInput(), ses)
        on.emailChange.confirm(a.id, ses, on.sentCode())
        assertEquals(false, on.profile.me(a.id).emailRequired)

        val off = harness(required = false)
        assertNull(off.profile.me(off.emailless().id).emailRequired, "off: the field is absent from the JSON, the response is what it was")
    }
}
