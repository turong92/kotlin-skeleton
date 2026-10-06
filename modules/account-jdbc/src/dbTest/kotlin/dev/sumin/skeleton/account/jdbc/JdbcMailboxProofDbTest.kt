package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.MailboxProof
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 메일함 증명 한 번 = 한 트랜잭션 — 두 DB 에서 같게 (docs/accounts.md "확인 없는 가입" 행) */
class JdbcMailboxProofDbTest {
    private val repo = AccountDb.accounts
    private val now = Instant.parse("2026-10-06T00:00:00.123456Z")

    @BeforeTest fun clean() = AccountDb.clean()

    private fun squatted(verified: Boolean = false) {
        val account = Account("acc_1", "victim@example.com", verified, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now)
        repo.insert(
            account,
            listOf(
                Identity("idn_pw", "acc_1", "password", "victim@example.com", verified, "{bcrypt}squatter", null, now),
                Identity("idn_g", "acc_1", "google", "g-squatter", true, null, null, now),
                Identity("idn_m", "acc_1", "magic_link", "victim@example.com", verified, null, null, now),
            ),
        )
    }

    @Test
    fun `an unverified account keeps only the proving identity - and is verified in the same transaction`() {
        squatted()
        assertTrue(repo.proveMailbox("acc_1", now, MailboxProof(keepIdentityIds = setOf("idn_m"))))
        assertEquals(listOf("magic_link"), repo.identitiesOf("acc_1").map { it.method })
        assertTrue(repo.identitiesOf("acc_1").single().verified)
        assertTrue(repo.findById("acc_1")!!.emailVerified)
    }

    @Test
    fun `a new password replaces the squatter's password row - the row id changes, so a late write to the old id is a no-op`() {
        squatted()
        assertTrue(repo.proveMailbox("acc_1", now, MailboxProof(keepIdentityIds = setOf("idn_pw"), passwordSecret = "{bcrypt}owner", newPasswordIdentityId = "idn_new")))
        val pw = repo.findIdentity("password", "victim@example.com")!!
        assertEquals("idn_new", pw.id)
        assertEquals("{bcrypt}owner", pw.secret)
        assertTrue(pw.verified)
        assertEquals(listOf("password"), repo.identitiesOf("acc_1").map { it.method })
        assertFalse(repo.updateIdentitySecret("idn_pw", "{bcrypt}squatter-late"), "the squatter's late change hits no row")
        assertEquals("{bcrypt}owner", repo.findIdentity("password", "victim@example.com")!!.secret)
    }

    @Test
    fun `a verified account loses nothing, and its password is simply replaced`() {
        squatted(verified = true)
        assertTrue(repo.proveMailbox("acc_1", now, MailboxProof(passwordSecret = "{bcrypt}owner", newPasswordIdentityId = "idn_unused")))
        assertEquals(setOf("password", "google", "magic_link"), repo.identitiesOf("acc_1").map { it.method }.toSet())
        assertEquals("{bcrypt}owner", repo.findIdentity("password", "victim@example.com")!!.secret)
        assertEquals("idn_pw", repo.findIdentity("password", "victim@example.com")!!.id)
    }

    @Test
    fun `an account without a password identity gets one, and an unknown or address-less account is refused`() {
        repo.insert(Account("acc_2", "social@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        assertTrue(repo.proveMailbox("acc_2", now, MailboxProof(passwordSecret = "{bcrypt}first", newPasswordIdentityId = "idn_first")))
        assertEquals("{bcrypt}first", repo.findIdentity("password", "social@example.com")!!.secret)
        assertFalse(repo.proveMailbox("acc_nope", now, MailboxProof()))
        repo.insert(Account("acc_3", null, false, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        assertFalse(repo.proveMailbox("acc_3", now, MailboxProof()))
        assertNull(repo.findIdentity("password", "none"))
    }

    @Test
    fun `the squatter's password write racing the owner's proof never survives - the outcome is the owner's password every time`() {
        repeat(30) { round ->
            AccountDb.clean()
            squatted()
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val squatter = pool.submit<Boolean> { go.await(); repo.updateIdentitySecret("idn_pw", "{bcrypt}squatter-$round") }
            val owner = pool.submit<Boolean> { go.await(); repo.proveMailbox("acc_1", now, MailboxProof(passwordSecret = "{bcrypt}owner", newPasswordIdentityId = "idn_new_$round")) }
            go.countDown()
            squatter.get(); assertTrue(owner.get())
            pool.shutdown()
            assertEquals("{bcrypt}owner", repo.findIdentity("password", "victim@example.com")!!.secret, "round $round")
            assertEquals(1, repo.identitiesOf("acc_1").size)
        }
    }
}
