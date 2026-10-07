package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.account.signin.SignInProof
import dev.sumin.skeleton.auth.account.LoginBlock
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 정지는 박제다 — 정지된 계정은 탈퇴로 빠져나가지 못하고, 운영자가 지운 뒤에도 같은 이메일 · 제공자 계정으로 돌아오지 못한다 */
class FrozenAccountTest {
    private val PW = ReauthInput("tangerine-42-moon")
    private object Google : SignInMethod { override val code = "google"; override val userRemovable = true }

    private fun harness(blocks: AccountProperties.Blocks = AccountProperties.Blocks()) = AccountHarness(
        AccountProperties(
            blocks = blocks, social = AccountProperties.Social(signUp = true),
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        ),
    )

    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code
    private fun AccountHarness.admin(): Account = repo.findByEmail("admin@example.com") ?: activeAccount("admin@example.com").also { repo.grantRole(it.id, "ADMIN", time.now()) }
    private fun AccountHarness.service() = AccountSignInService(core, SignInMethodRegistry(listOf(PasswordSignInMethod(), Google)))
    private fun proof(subject: String, email: String? = null) = SignInProof("google", subject, email, email != null, "Ann", "ko", "203.0.113.1", true)

    private fun AccountHarness.suspendedWithGoogle(): Account {
        val admin = admin()
        val a = activeAccount("ann@example.com")
        repo.addIdentity(Identity(core.newIdentityId(), a.id, "google", "g-ann", true, createdAt = time.now()))
        this.admin.suspend(admin.id, a.id, "abuse")
        return a
    }

    @Test
    fun `a suspended account cannot ask to delete itself`() {
        val h = harness()
        val a = h.suspendedWithGoogle()
        assertEquals("ACCOUNT.SUSPENDED_CANNOT_DELETE", code { h.deletion.delete(a.id, PW, null) })
        assertEquals("ACCOUNT.SUSPENDED_CANNOT_DELETE", code { h.deletion.requestConfirmation(a.id, "ses_1") })
        assertEquals(AccountStatus.SUSPENDED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `an account suspended during its grace period is not erased when the grace ends, and lifting the suspension resumes the deletion`() {
        val h = harness()
        val admin = h.admin()
        val a = h.activeAccount("ann@example.com")
        h.deletion.delete(a.id, PW, null)
        h.admin.suspend(admin.id, a.id, "abuse")
        h.time.advance(Duration.ofDays(40))
        assertEquals(0, h.purge.purgeDue())
        val frozen = h.repo.findById(a.id)!!
        assertEquals(AccountStatus.SUSPENDED, frozen.status)
        assertEquals("ann@example.com", frozen.email)

        h.admin.unsuspend(admin.id, a.id)
        val resumed = h.repo.findById(a.id)!!
        assertEquals(AccountStatus.DELETED, resumed.status, "the owner still wanted to leave")
        assertTrue(resumed.purgeAfter!!.isAfter(h.time.now()), "a fresh grace, so a wrongful suspension can still be undone by a restore")
        assertEquals(0, h.purge.purgeDue())
    }

    @Test
    fun `a suspended account keeps its sign-in methods so the same provider subject cannot start over - no new account appears`() {
        val h = harness()
        val a = h.suspendedWithGoogle()
        val before = h.repo.search(null, null, 0, 50).total
        val auth = assertNotNull(h.service().signIn(proof("g-ann")))
        assertEquals(a.id, auth.accountId)
        assertEquals(LoginBlock.SUSPENDED, auth.loginBlock)
        assertEquals(before, h.repo.search(null, null, 0, 50).total)
    }

    @Test
    fun `a sign-up code for the address of a suspended unproven account does not take the account over`() {
        val h = harness()
        val admin = h.admin()
        val now = h.time.now()
        h.repo.insert(Account("acc_u", "unproven@example.com", false, AccountStatus.SUSPENDED, setOf("USER"), null, null, null, now, now), emptyList())
        assertNotNull(admin)
        h.signUp("unproven@example.com")   // the same 202 as always
        assertEquals("ACCOUNT.REGISTRATION_BLOCKED", code { h.verify("unproven@example.com") })
        assertEquals(AccountStatus.SUSPENDED, h.repo.findById("acc_u")!!.status)
        assertTrue(h.repo.identitiesOf("acc_u").isEmpty())
    }

    @Test
    fun `an administrator erases a suspended account at once - personal data goes, a hash-only block remains and the person cannot come back`() {
        val h = harness()
        val admin = h.admin()
        val a = h.suspendedWithGoogle()
        assertTrue(h.purge.eraseSuspended(admin.id, a.id, "fraud"))

        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
        val blocks = h.admin.listBlocks(0, 20)
        assertEquals(setOf("email", "identity"), blocks.items.map { it.kind }.toSet())
        assertEquals(2, blocks.total, "one for the address, one for the google subject (the password identity is the address)")
        // the STORED values, not a toString (which hides the hash anyway): every row is a 64-hex keyed hash, none of the fields holds a raw value, and the hash is the keyed one
        assertTrue(blocks.items.all { it.hash.matches(Regex("[0-9a-f]{64}")) }, "only hashes are kept")
        assertTrue(blocks.items.none { b -> listOf(b.hash, b.reason, b.createdBy, b.accountId).any { f -> f != null && listOf("ann@example.com", "g-ann").any { raw -> raw in f } } })
        assertEquals(setOf(h.core.blocks.emailHash("ann@example.com"), h.core.blocks.identityHash("google", "g-ann")), blocks.items.map { it.hash }.toSet())

        // the sign-up REQUEST still looks like any other (202, same shape); only someone who proves the mailbox / provider identity is refused
        assertEquals(SignUpStatus.VERIFICATION_SENT, h.signUp("ann@example.com").status)
        assertEquals("ACCOUNT.REGISTRATION_BLOCKED", code { h.verify("ann@example.com") })
        assertEquals("ACCOUNT.REGISTRATION_BLOCKED", code { h.service().signIn(proof("g-ann")) })
        assertEquals("ACCOUNT.REGISTRATION_BLOCKED", code { h.service().signIn(proof("g-other", "ann@example.com")) }, "a verified provider email is a proven mailbox")
        assertNull(h.repo.findByEmail("ann@example.com"))

        // an administrator can lift the block
        blocks.items.forEach { h.admin.removeBlock(admin.id, it.id) }
        assertEquals(0, h.admin.listBlocks(0, 20).total)
        assertNotNull(h.service().signIn(proof("g-ann")))
    }

    @Test
    fun `only a suspended account can be erased by an administrator, and not by itself`() {
        val h = harness()
        val admin = h.admin()
        val active = h.activeAccount("bob@example.com")
        assertEquals("ACCOUNT.NOT_SUSPENDED", code { h.purge.eraseSuspended(admin.id, active.id, null) })
        assertEquals("ACCOUNT.NOT_FOUND", code { h.purge.eraseSuspended(admin.id, "acc_missing", null) })
        h.admin.suspend(admin.id, active.id, null)
        assertEquals("ACCOUNT.SELF_ACTION_FORBIDDEN", code { h.purge.eraseSuspended(active.id, active.id, null) })
    }

    @Test
    fun `a self-service deletion leaves no block - leaving is not a punishment`() {
        val h = harness()
        h.admin()
        val a = h.activeAccount("ann@example.com")
        h.deletion.delete(a.id, PW, null)
        h.time.advance(Duration.ofDays(31))
        h.purge.purgeDue()
        assertEquals(0, h.admin.listBlocks(0, 20).total)
    }

    @Test
    fun `a block with a retention lapses by itself`() {
        val h = harness(AccountProperties.Blocks(retention = Duration.ofDays(10)))
        val admin = h.admin()
        val a = h.suspendedWithGoogle()
        h.purge.eraseSuspended(admin.id, a.id, null)
        assertEquals("ACCOUNT.REGISTRATION_BLOCKED", code { h.service().signIn(proof("g-ann")) })
        h.time.advance(Duration.ofDays(11))
        assertNotNull(h.service().signIn(proof("g-ann")))
        h.purge.purgeDue()
        assertEquals(0, h.admin.listBlocks(0, 20).total, "the periodic cleanup drops lapsed blocks")
    }

    @Test
    fun `erasing twice is harmless`() {
        val h = harness()
        val admin = h.admin()
        val a = h.suspendedWithGoogle()
        assertTrue(h.purge.eraseSuspended(admin.id, a.id, null))
        assertEquals("ACCOUNT.ERASED", code { h.purge.eraseSuspended(admin.id, a.id, null) })
        assertEquals(2, h.admin.listBlocks(0, 20).total)
    }
}
