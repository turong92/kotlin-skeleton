package dev.sumin.skeleton.account.signin

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountHarness
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.common.ApplicationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInServiceTest {
    private object Google : SignInMethod { override val code = "google"; override val userRemovable = true }
    private object Kakao : SignInMethod { override val code = "kakao" }
    private object Magic : SignInMethod {
        override val code = "magic_link"
        override fun normalize(subject: String) = subject.trim().lowercase()
        override val provesEmail = true
        override val exposesSubject = true
    }
    private object Locked : SignInMethod { override val code = "hardware_key"; override val userRemovable = false }

    private fun harness(social: AccountProperties.Social = AccountProperties.Social(signUp = true), bootstrapEmail: String = "") = AccountHarness(
        AccountProperties(
            social = social, bootstrap = AccountProperties.Bootstrap(adminEmail = bootstrapEmail),
            mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4),
        ),
    )

    private val methods = listOf(PasswordSignInMethod(), Google, Kakao, Magic, Locked)
    private fun AccountHarness.service() = AccountSignInService(core, SignInMethodRegistry(methods))
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code
    private fun proof(method: String = "google", subject: String = "g-1", email: String? = "ann@example.com", verified: Boolean = true, signUp: Boolean = true) =
        SignInProof(method, subject, email, verified, "Ann", "ko", "203.0.113.1", signUp)

    // ---- registry

    @Test
    fun `the registry rejects duplicate or malformed codes and says what is unknown`() {
        assertFailsWith<IllegalArgumentException> { SignInMethodRegistry(listOf(Google, Google)) }
        assertFailsWith<IllegalArgumentException> { SignInMethodRegistry(listOf(object : SignInMethod { override val code = "Bad Code" })) }
        val registry = SignInMethodRegistry(methods)
        assertEquals("ACCOUNT.METHOD_UNKNOWN", assertFailsWith<ApplicationException> { registry.require("myspace") }.errorCode.code)
        assertEquals(setOf("password", "google", "kakao", "magic_link", "hardware_key"), registry.all().map { it.code }.toSet())
    }

    @Test
    fun `the password method normalizes emails like the rest of the module`() {
        assertEquals("ann@example.com", PasswordSignInMethod().normalize("  Ann@Example.COM "))
    }

    // ---- sign in / sign up through any method

    @Test
    fun `an unknown identity without permission to sign up yields nothing and creates nothing`() {
        val h = harness()
        assertNull(h.service().signIn(proof(signUp = false)))
        assertEquals(0, h.repo.search(null, null, 0, 10).total)
    }

    @Test
    fun `a verified social sign-up creates an active account with its email and a verified identity`() {
        val h = harness()
        val auth = assertNotNull(h.service().signIn(proof()))
        val account = h.repo.findById(auth.accountId)!!
        assertEquals(AccountStatus.ACTIVE, account.status)
        assertEquals("ann@example.com", account.email)
        assertTrue(account.emailVerified)
        assertEquals(setOf("USER"), account.roles)
        assertEquals("ko", account.locale)
        assertTrue(h.repo.findIdentity("google", "g-1")!!.verified)
        assertTrue(AccountEventType.SIGN_UP in h.events.types())
        assertTrue(AccountEventType.LOGIN_SUCCESS in h.events.types())
        assertEquals("", auth.passwordHash)
    }

    @Test
    fun `signing in again finds the same account and records the login`() {
        val h = harness()
        val first = h.service().signIn(proof())!!
        h.time.advance(java.time.Duration.ofHours(1))
        val second = h.service().signIn(proof(signUp = false))!!
        assertEquals(first.accountId, second.accountId)
        assertEquals(h.time.now(), h.repo.findById(first.accountId)!!.lastLoginAt)
        assertEquals(h.time.now(), h.repo.findIdentity("google", "g-1")!!.lastUsedAt)
    }

    @Test
    fun `a provider email that is not verified is ignored - no conflict, no squatting, no oracle`() {
        val h = harness()
        h.activeAccount("ann@example.com")
        val auth = h.service().signIn(proof(subject = "k-1", method = "kakao", verified = false))!!
        val created = h.repo.findById(auth.accountId)!!
        assertNull(created.email, "an unverified provider email must not be stored (it would reserve someone else's address)")
        assertTrue(created.id != h.repo.findByEmail("ann@example.com")!!.id)
    }

    @Test
    fun `a verified provider email that matches an existing account is a conflict - never silently merged`() {
        val h = harness()
        val existing = h.activeAccount("ann@example.com")
        assertEquals("ACCOUNT.SOCIAL_EMAIL_CONFLICT", code { h.service().signIn(proof()) })
        assertNull(h.repo.findIdentity("google", "g-1"))
        assertEquals(1, h.repo.identitiesOf(existing.id).size)
    }

    @Test
    fun `merging is opt-in and still needs both emails verified`() {
        val merge = harness(AccountProperties.Social(signUp = true, mergeOnVerifiedEmail = true))
        val existing = merge.activeAccount("ann@example.com")
        assertEquals(existing.id, merge.service().signIn(proof())!!.accountId)
        assertEquals(setOf("password", "google"), merge.repo.identitiesOf(existing.id).map { it.method }.toSet())

        val unverified = harness(AccountProperties.Social(signUp = true, mergeOnVerifiedEmail = true))
        unverified.signUp("ann@example.com")   // pending: the address was never proven by its owner
        assertEquals("ACCOUNT.SOCIAL_EMAIL_CONFLICT", code { unverified.service().signIn(proof()) })
    }

    @Test
    fun `social sign-up stays closed unless the caller allows it`() {
        val h = harness(AccountProperties.Social(signUp = false))
        assertNull(h.service().signIn(proof(signUp = false)))
    }

    @Test
    fun `a magic link proves the mailbox so it attaches to the existing account and verifies a pending one`() {
        val h = harness()
        h.signUp("ann@example.com")
        val auth = h.service().signIn(proof("magic_link", "  Ann@Example.com ", "ann@example.com", true))!!
        val account = h.repo.findById(auth.accountId)!!
        assertEquals(AccountStatus.ACTIVE, account.status)
        assertTrue(account.emailVerified)
        assertEquals(setOf("password", "magic_link"), h.repo.identitiesOf(account.id).map { it.method }.toSet())
        assertEquals(1, h.repo.search(null, null, 0, 10).total)
    }

    @Test
    fun `a magic link for a new address creates an active verified account`() {
        val h = harness()
        val auth = h.service().signIn(proof("magic_link", "new@example.com", "new@example.com", true))!!
        val account = h.repo.findById(auth.accountId)!!
        assertEquals(AccountStatus.ACTIVE, account.status)
        assertEquals("new@example.com", account.email)
        assertTrue(account.emailVerified)
    }

    @Test
    fun `a deleted account cannot be signed into and a suspended one comes back blocked`() {
        val h = harness()
        val a = h.service().signIn(proof())!!
        h.repo.update(a.accountId, dev.sumin.skeleton.account.AccountPatch(status = AccountStatus.SUSPENDED), h.time.now())
        assertEquals(dev.sumin.skeleton.auth.account.LoginBlock.SUSPENDED, h.service().signIn(proof(signUp = false))!!.loginBlock)
        h.repo.update(a.accountId, dev.sumin.skeleton.account.AccountPatch(status = AccountStatus.DELETED), h.time.now())
        assertNull(h.service().signIn(proof(signUp = false)))
    }

    @Test
    fun `two simultaneous first sign-ins with one provider identity make one account`() {
        val h = harness()
        val s = h.service()
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val results = (1..8).map { pool.submit<Result<String>> { go.await(); runCatching { s.signIn(proof(subject = "race", email = "race@example.com"))!!.accountId } } }
        go.countDown()
        val ids = results.map { it.get() }.filter { it.isSuccess }.map { it.getOrThrow() }.toSet()
        pool.shutdown()
        assertEquals(1, ids.size)
        assertEquals(1, h.repo.search(null, null, 0, 10).total)
    }

    @Test
    fun `the bootstrap admin can come in through a verified method too`() {
        val h = harness(bootstrapEmail = "boss@example.com")
        val auth = h.service().signIn(proof("magic_link", "boss@example.com", "boss@example.com", true))!!
        assertTrue("ADMIN" in h.repo.findById(auth.accountId)!!.roles)
    }

    // ---- linking, unlinking, listing

    private fun linked(h: AccountHarness): Account {
        val a = h.activeAccount()
        h.service().identities.link(a.id, "google", "g-1", verified = true)
        return h.repo.findById(a.id)!!
    }

    @Test
    fun `a signed-in account links a provider identity and sees it listed without the provider subject`() {
        val h = harness()
        val a = linked(h)
        val views = h.service().identities.list(a.id)
        assertEquals(listOf("password", "google"), views.map { it.method })
        assertEquals("ann@example.com", views.first { it.method == "password" }.subject)
        assertNull(views.first { it.method == "google" }.subject, "provider user ids are not shown")
        assertTrue(AccountEventType.IDENTITY_LINKED in h.events.types())
    }

    @Test
    fun `an identity that belongs to another account is taken, the same one twice exists, a second of one method exists`() {
        val h = harness()
        val a = linked(h)
        val b = h.activeAccount("bob@example.com")
        assertEquals("ACCOUNT.IDENTITY_TAKEN", code { h.service().identities.link(b.id, "google", "g-1", true) })
        assertEquals("ACCOUNT.IDENTITY_EXISTS", code { h.service().identities.link(a.id, "google", "g-1", true) })
        assertEquals("ACCOUNT.IDENTITY_EXISTS", code { h.service().identities.link(a.id, "google", "g-2", true) })
        assertEquals("ACCOUNT.METHOD_UNKNOWN", code { h.service().identities.link(a.id, "myspace", "x", true) })
    }

    @Test
    fun `the last sign-in method cannot be removed but any other can`() {
        val h = harness()
        val a = linked(h)
        val views = h.service().identities.list(a.id)
        val google = views.first { it.method == "google" }
        val password = views.first { it.method == "password" }
        h.service().identities.unlink(a.id, google.id)
        assertEquals("ACCOUNT.LAST_SIGN_IN_METHOD", code { h.service().identities.unlink(a.id, password.id) })
        assertTrue(AccountEventType.IDENTITY_UNLINKED in h.events.types())
    }

    @Test
    fun `removing someone else's identity looks like removing a missing one`() {
        val h = harness()
        val a = linked(h)
        val b = h.activeAccount("bob@example.com")
        val theirs = h.service().identities.list(a.id).first { it.method == "google" }
        assertEquals("ACCOUNT.IDENTITY_NOT_FOUND", code { h.service().identities.unlink(b.id, theirs.id) })
        assertEquals("ACCOUNT.IDENTITY_NOT_FOUND", code { h.service().identities.unlink(b.id, "idn_nope") })
    }

    @Test
    fun `a method that forbids user removal stays`() {
        val h = harness()
        val a = h.activeAccount()
        h.service().identities.link(a.id, "hardware_key", "key-1", true)
        val key = h.service().identities.list(a.id).first { it.method == "hardware_key" }
        assertEquals(false, key.removable)
        assertEquals("ACCOUNT.LAST_SIGN_IN_METHOD", code { h.service().identities.unlink(a.id, key.id) })
    }

    @Test
    fun `two simultaneous unlinks of the only two methods leave exactly one`() {
        repeat(20) {
            val h = harness()
            val a = linked(h)
            val s = h.service()
            val ids = s.identities.list(a.id).map { it.id }
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val r = ids.map { id -> pool.submit<Result<Unit>> { go.await(); runCatching { s.identities.unlink(a.id, id) } } }
            go.countDown()
            val ok = r.count { it.get().isSuccess }
            pool.shutdown()
            assertEquals(1, ok)
            assertEquals(1, h.repo.identitiesOf(a.id).size)
        }
    }
}
