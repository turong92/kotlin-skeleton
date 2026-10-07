package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.account.signin.SignInProof
import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 삭제 유예 뒤: 기본(ANONYMIZE)은 계정 행을 ERASED 로 남기고 개인정보만 지운다. DELETE 는 행까지 지운다. */
class AccountErasureTest {
    private val PW = ReauthInput("tangerine-42-moon")
    private object Google : SignInMethod { override val code = "google"; override val userRemovable = true }

    private fun harness(mode: AccountProperties.Deletion.Mode? = null, batch: Int = 50) = AccountHarness(
        AccountProperties(
            deletion = if (mode == null) AccountProperties.Deletion(purgeBatch = batch) else AccountProperties.Deletion(mode = mode, purgeBatch = batch),
            social = AccountProperties.Social(signUp = true),
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        ),
    )

    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    /** 로그인한 적 있고 구글도 붙인 계정을 만들고 삭제 요청 + 유예 경과까지 */
    private fun AccountHarness.leave(email: String = "ann@example.com"): Account {
        val a = activeAccount(email)
        repo.update(a.id, AccountPatch(locale = "ko", timeZone = "Asia/Seoul", lastLoginAt = time.now(), suspendedReason = "spam?"), time.now())
        repo.addIdentity(Identity(core.newIdentityId(), a.id, "google", "g-${a.id}", true, createdAt = time.now()))
        repo.grantRole(a.id, "MODERATOR", time.now())
        deletion.delete(a.id, PW, null)
        time.advance(Duration.ofDays(31))
        return a
    }

    @Test
    fun `by default the row stays as ERASED with every personal field gone and the sign-in methods and roles removed`() {
        val h = harness()
        val a = h.leave()
        assertEquals(1, h.purge.purgeDue())

        val kept = assertNotNull(h.repo.findById(a.id), "the row stays so that references from other tables never dangle")
        assertEquals(AccountStatus.ERASED, kept.status)
        assertEquals(a.createdAt, kept.createdAt)
        assertNull(kept.email); assertNull(kept.displayName); assertNull(kept.locale); assertNull(kept.timeZone)
        assertNull(kept.suspendedReason); assertNull(kept.lastLoginAt); assertNull(kept.purgeAfter)
        assertEquals(h.time.now(), kept.erasedAt)
        assertEquals(false, kept.emailVerified)
        assertTrue(kept.roles.isEmpty())
        assertTrue(h.repo.identitiesOf(a.id).isEmpty())
        assertNull(h.repo.findIdentity("password", "ann@example.com"))
        assertNull(h.repo.findIdentity("google", "g-${a.id}"))
        assertNull(h.repo.findByEmail("ann@example.com"))
        assertTrue(AccountEventType.ACCOUNT_PURGED in h.events.types())
    }

    @Test
    fun `DELETE mode removes the row like before`() {
        val h = harness(AccountProperties.Deletion.Mode.DELETE)
        val a = h.leave()
        assertEquals(1, h.purge.purgeDue())
        assertNull(h.repo.findById(a.id))
    }

    @Test
    fun `an erased account cannot sign in, is not found by email and has no me`() {
        val h = harness()
        val a = h.leave()
        h.purge.purgeDue()
        assertNull(h.authRepository.findBy(AccountIdentifier(accountId = a.id)))
        assertNull(h.authRepository.findBy(AccountIdentifier(email = "ann@example.com")))
        assertNull(h.core.accountByEmail("ann@example.com"))
        assertEquals("ACCOUNT.NOT_FOUND", code { h.profile.me(a.id) })
    }

    @Test
    fun `an erased account can never be restored, granted a role or suspended - the error says it was erased`() {
        val h = harness()
        val a = h.leave()
        h.purge.purgeDue()
        assertEquals("ACCOUNT.ERASED", code { h.admin.restore("acc_admin", a.id) })
        assertEquals("ACCOUNT.ERASED", code { h.admin.grantRole("acc_admin", a.id, "ADMIN") })
        assertEquals("ACCOUNT.ERASED", code { h.admin.suspend("acc_admin", a.id, null) })
        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
        assertTrue(h.repo.findById(a.id)!!.roles.isEmpty())
        assertEquals(false, h.repo.grantRole(a.id, "ADMIN", h.time.now()), "the repository refuses too, not only the service")
        assertEquals(false, h.repo.restore(a.id, AccountStatus.ACTIVE, h.time.now()))
    }

    @Test
    fun `the admin list hides erased accounts unless asked for them and shows no personal data`() {
        val h = harness()
        val gone = h.leave("gone@example.com")
        val stays = h.activeAccount("stays@example.com")
        h.purge.purgeDue()

        assertEquals(listOf(stays.id), h.admin.search(null, null, 0, 10).items.map { it.id })
        val erased = h.admin.search(null, AccountStatus.ERASED, 0, 10).items.single()
        assertEquals(gone.id, erased.id)
        assertNull(erased.email); assertNull(erased.displayName)
        assertEquals(gone.id, h.admin.get(gone.id).id, "a single lookup by id still works (support, audit trail)")
    }

    @Test
    fun `the same email can sign up again and gets a NEW account, never the erased one`() {
        val h = harness()
        val old = h.leave()
        h.purge.purgeDue()
        val fresh = h.activeAccount("ann@example.com")
        assertNotEquals(old.id, fresh.id)
        assertEquals(AccountStatus.ERASED, h.repo.findById(old.id)!!.status)
        assertEquals(AccountStatus.ACTIVE, fresh.status)
    }

    @Test
    fun `a social sign-in with the same provider subject creates a new account because the identity is gone`() {
        val h = harness()
        val a = h.leave()
        h.purge.purgeDue()
        val service = AccountSignInService(h.core, SignInMethodRegistry(listOf(PasswordSignInMethod(), Google)))
        val again = service.signIn(SignInProof("google", "g-${a.id}", null, false, "Ann", "ko", "203.0.113.1", true))
        assertNotNull(again)
        assertNotEquals(a.id, again.accountId)
    }

    @Test
    fun `the erasure request tells listeners whether the account row stays`() {
        val seen = mutableListOf<ErasureRequest>()
        for (mode in AccountProperties.Deletion.Mode.entries) {
            val h = harness(mode)
            h.erasers += object : AccountErasureListener { override val name = "x"; override fun erase(request: ErasureRequest) { seen += request } }
            h.leave(); h.purge.purgeDue()
        }
        assertEquals(listOf(true, false), seen.map { it.accountKept })
    }

    @Test
    fun `purging twice is harmless - the second run finds nothing and calls no listener`() {
        val h = harness()
        var calls = 0
        h.erasers += object : AccountErasureListener { override val name = "x"; override fun erase(request: ErasureRequest) { calls++ } }
        h.leave()
        assertEquals(1, h.purge.purgeDue())
        assertEquals(0, h.purge.purgeDue())
        assertEquals(1, calls)
    }

    @Test
    fun `a listener failure leaves the account DELETED for the next run, which then erases it`() {
        val h = harness()
        var broken = true
        h.erasers += object : AccountErasureListener { override val name = "flaky"; override fun erase(request: ErasureRequest) { if (broken) error("db down") } }
        val a = h.leave()
        assertEquals(0, h.purge.purgeDue())
        assertEquals(AccountStatus.DELETED, h.repo.findById(a.id)!!.status)
        assertEquals("ann@example.com", h.repo.findById(a.id)!!.email)
        broken = false
        assertEquals(1, h.purge.purgeDue())
        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `one account that cannot be erased does not block the rest of the batch`() {
        val h = harness()
        val first = h.leave("first@example.com")
        val second = h.leave("second@example.com")
        h.erasers += object : AccountErasureListener {
            override val name = "picky"
            override fun erase(request: ErasureRequest) { if (request.accountId == first.id) error("cannot") }
        }
        assertEquals(1, h.purge.purgeDue())
        assertEquals(AccountStatus.DELETED, h.repo.findById(first.id)!!.status)
        assertEquals(AccountStatus.ERASED, h.repo.findById(second.id)!!.status)
    }

    @Test
    fun `a run erases at most purge-batch accounts and the next run takes the rest`() {
        val h = harness(batch = 2)
        repeat(3) { h.leave("u$it@example.com") }
        assertEquals(2, h.purge.purgeDue())
        assertEquals(1, h.purge.purgeDue())
        assertEquals(0, h.purge.purgeDue())
    }

    @Test
    fun `a restore that wins before the grace ends keeps the account - nothing is erased`() {
        val h = harness()
        val a = h.activeAccount()
        h.deletion.delete(a.id, PW, null)
        h.time.advance(Duration.ofDays(29))
        h.admin.restore("acc_admin", a.id)
        h.time.advance(Duration.ofDays(5))
        assertEquals(0, h.purge.purgeDue())
        assertEquals(AccountStatus.ACTIVE, h.repo.findById(a.id)!!.status)
        assertEquals("ann@example.com", h.repo.findById(a.id)!!.email)
    }
}
