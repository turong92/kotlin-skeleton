package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.account.signin.SignInProof
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.LoginBlock
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 탈퇴 유예 중인 주인이 로그인에 성공하면 세션 대신 "탈퇴 대기" 상태와 한 번 쓰는 취소 토큰을 받고, 그 토큰으로만 탈퇴를 취소한다 */
class SelfRestoreTest {
    private val PW = ReauthInput("tangerine-42-moon")
    private object Google : SignInMethod { override val code = "google"; override val userRemovable = true }

    private fun harness(selfRestore: Boolean = true, ttl: Duration = Duration.ofMinutes(15), emailVerification: Boolean = true) = AccountHarness(
        AccountProperties(
            signUp = AccountProperties.SignUp(emailVerification = emailVerification),
            deletion = AccountProperties.Deletion(selfRestore = selfRestore, selfRestoreTtl = ttl), social = AccountProperties.Social(signUp = true),
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        ),
    )

    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code
    private fun AccountHarness.pending(email: String = "ann@example.com"): Account = activeAccount(email).also { deletion.delete(it.id, PW, null) }
    private fun AccountHarness.stateOf(email: String = "ann@example.com") = authRepository.findBy(AccountIdentifier(email = email))
    private fun Map<String, Any?>.token() = getValue("restoreToken") as String

    @Test
    fun `by default a deleted account still looks like no account at all`() {
        val h = harness(selfRestore = false)
        h.pending()
        assertNull(h.stateOf())
    }

    @Test
    fun `with self-restore on, the lookup says deletion pending and a restore token exists only after the sign-in asks for the state`() {
        val h = harness()
        val a = h.pending()
        val auth = assertNotNull(h.stateOf())
        assertEquals(LoginBlock.DELETION_PENDING, auth.loginBlock)
        assertTrue(auth.passwordHash.isNotBlank(), "the password is still checked - a wrong password is the same INVALID_CREDENTIALS as for any account")
        val data = auth.blockData!!()
        assertEquals(h.repo.findById(a.id)!!.purgeAfter.toString(), data["purgeAfter"].toString())
        assertTrue(data.token().length >= 20)
    }

    @Test
    fun `the token cancels the deletion once - the account is active again, the owner is told and the event is recorded`() {
        val h = harness()
        val a = h.pending()
        val token = h.stateOf()!!.blockData!!().token()
        val auth = h.deletion.cancel(token, "203.0.113.5")
        assertEquals(a.id, auth.accountId)
        assertNull(auth.loginBlock)
        val back = h.repo.findById(a.id)!!
        assertEquals(AccountStatus.ACTIVE, back.status)
        assertNull(back.purgeAfter); assertNull(back.deletedAt)
        assertEquals(1, h.mailer.of(MailKind.DELETION_CANCELLED).size)
        assertTrue(AccountEventType.DELETION_CANCELLED in h.events.types())
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.deletion.cancel(token, "203.0.113.5") }, "single use")
    }

    @Test
    fun `the cancel reports the sign-in method that earned the restore token - the login it records is not always a password`() {
        val h = harness()
        val a = h.pending()
        val viaPassword = h.stateOf()!!.blockData!!().token()
        assertEquals("password", h.deletion.cancelWithMethod(viaPassword, null).method)

        h.deletion.delete(a.id, PW, null)
        h.repo.addIdentity(Identity(h.core.newIdentityId(), a.id, "google", "g-ann", true, createdAt = h.time.now()))
        val social = assertNotNull(AccountSignInService(h.core, SignInMethodRegistry(listOf(PasswordSignInMethod(), Google))).signIn(SignInProof("google", "g-ann", null, false, "Ann", "ko", "203.0.113.1", true)))
        val cancelled = h.deletion.cancelWithMethod(social.blockData!!().token(), null)
        assertEquals("google", cancelled.method)
        assertEquals(a.id, cancelled.auth.accountId)
    }

    @Test
    fun `asking for the state again replaces the earlier token - only the newest works`() {
        val h = harness()
        h.pending()
        val first = h.stateOf()!!.blockData!!().token()
        val second = h.stateOf()!!.blockData!!().token()
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.deletion.cancel(first, null) })
        h.deletion.cancel(second, null)
    }

    @Test
    fun `with email verification switched off the account comes back ACTIVE - it was ACTIVE before, never PENDING_VERIFICATION`() {
        val h = harness(emailVerification = false)
        h.signUp()
        val a = h.repo.findByEmail("ann@example.com")!!
        assertEquals(AccountStatus.ACTIVE, a.status)
        assertEquals(false, a.emailVerified, "no verification step exists in this mode")
        h.deletion.delete(a.id, PW, null)
        h.deletion.cancel(h.stateOf()!!.blockData!!().token(), null)
        assertEquals(AccountStatus.ACTIVE, h.repo.findById(a.id)!!.status)

        // an operator's restore and the end of a suspension reopen the same way
        h.deletion.delete(a.id, PW, null)
        h.admin.restore("acc_admin", a.id)
        assertEquals(AccountStatus.ACTIVE, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `with email verification on, an unverified account still returns to PENDING_VERIFICATION`() {
        val h = harness()
        val unproven = Account("acc_u", "unproven@example.com", false, AccountStatus.DELETED, setOf("USER"), null, null, null, h.time.now(), h.time.now(), deletedAt = h.time.now(), purgeAfter = h.time.now().plus(Duration.ofDays(30)))
        h.repo.insert(unproven, listOf(Identity("idn_u", "acc_u", "password", "unproven@example.com", false, secret = "x", createdAt = h.time.now())))
        h.admin.restore("acc_admin", "acc_u")
        assertEquals(AccountStatus.PENDING_VERIFICATION, h.repo.findById("acc_u")!!.status)
    }

    @Test
    fun `the restore token is good for nothing else, and no other token restores`() {
        val h = harness()
        val a = h.pending()
        val token = h.stateOf()!!.blockData!!().token()
        assertNull(h.tokens.consume(TokenPurposes.PASSWORD_RESET, token))
        assertNull(h.tokens.consume(TokenPurposes.MAGIC_LINK, token))
        val other = h.tokens.issue(TokenPurposes.MAGIC_LINK, "ann@example.com", a.id, Duration.ofMinutes(5))
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.deletion.cancel(other, null) })
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.deletion.cancel("not-a-token-not-a-token-1234", null) })
        assertEquals(AccountStatus.DELETED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `the token expires`() {
        val h = harness(ttl = Duration.ofMinutes(15))
        h.pending()
        val token = h.stateOf()!!.blockData!!().token()
        h.time.advance(Duration.ofMinutes(16))
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.deletion.cancel(token, null) })
    }

    @Test
    fun `after the grace there is nothing to cancel - not even with a token minted earlier`() {
        val h = harness()
        val a = h.pending()
        val token = h.stateOf()!!.blockData!!().token()
        h.time.advance(Duration.ofDays(31))
        assertNull(h.stateOf(), "a lapsed grace shows no state")
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.deletion.cancel(token, null) })
        assertEquals(AccountStatus.DELETED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `a suspended account never gets the way back, even if its token was minted before the suspension`() {
        val h = harness()
        val admin = h.activeAccount("admin@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val a = h.pending()
        val token = h.stateOf()!!.blockData!!().token()
        h.admin.suspend(admin.id, a.id, "abuse")
        assertEquals(LoginBlock.SUSPENDED, h.stateOf()!!.loginBlock)
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.deletion.cancel(token, null) })
        assertEquals(AccountStatus.SUSPENDED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `cancelling is rate limited per address`() {
        val h = harness()
        h.pending()
        val limit = AccountProperties().login.perIp
        repeat(limit) { assertEquals("ACCOUNT.TOKEN_INVALID", code { h.deletion.cancel("not-a-token-not-a-token-1234", "203.0.113.9") }) }
        assertFailsWith<RateLimitedException> { h.deletion.cancel("not-a-token-not-a-token-1234", "203.0.113.9") }
    }

    @Test
    fun `a social sign-in gets the same state, creates no account and records no login`() {
        val h = harness()
        val a = h.activeAccount()
        h.repo.addIdentity(Identity(h.core.newIdentityId(), a.id, "google", "g-ann", true, createdAt = h.time.now()))
        h.deletion.delete(a.id, PW, null)
        val before = h.repo.search(null, null, 0, 50).total
        val loginsBefore = h.events.types().count { it == AccountEventType.LOGIN_SUCCESS }
        val auth = assertNotNull(AccountSignInService(h.core, SignInMethodRegistry(listOf(PasswordSignInMethod(), Google))).signIn(SignInProof("google", "g-ann", null, false, "Ann", "ko", "203.0.113.1", true)))
        assertEquals(LoginBlock.DELETION_PENDING, auth.loginBlock)
        assertEquals(before, h.repo.search(null, null, 0, 50).total)
        assertNull(h.repo.findById(a.id)!!.lastLoginAt)
        assertEquals(loginsBefore, h.events.types().count { it == AccountEventType.LOGIN_SUCCESS }, "a pending-deletion sign-in is not a successful login")
    }
}
