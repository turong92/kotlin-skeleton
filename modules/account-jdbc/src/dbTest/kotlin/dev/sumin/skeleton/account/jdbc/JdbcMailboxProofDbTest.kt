package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.AddIdentityResult
import dev.sumin.skeleton.account.ChangeEmailResult
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.challenge.ChallengeRow
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

    // ---- I5: writes that landed after the proof (they saw the account unverified)

    private fun challenge(id: String, purpose: String, subject: String, accountId: String?) =
        ChallengeRow(id, purpose, subject, accountId, null, null, null, "h", 5, 0, now, now.plusSeconds(600), now, null)

    @Test
    fun `an identity insert that saw the account unverified is refused once the mailbox was proven - and the proof's clean-up is not undone`() {
        squatted()
        assertTrue(repo.proveMailbox("acc_1", now, MailboxProof(keepIdentityIds = setOf("idn_pw"), passwordSecret = "{bcrypt}owner", newPasswordIdentityId = "idn_new")))
        val late = Identity("idn_late", "acc_1", "kakao", "k-squatter", true, null, null, now)
        assertEquals(AddIdentityResult.STALE, repo.addIdentityIfEmailVerified(late, expectEmailVerified = false))
        assertNull(repo.findIdentity("kakao", "k-squatter"))
        assertEquals(AddIdentityResult.ADDED, repo.addIdentityIfEmailVerified(late, expectEmailVerified = true), "a caller that saw the proven state may link")
        assertEquals(AddIdentityResult.DUPLICATE, repo.addIdentityIfEmailVerified(late.copy(id = "idn_late2"), expectEmailVerified = true))
        assertEquals(AddIdentityResult.STALE, repo.addIdentityIfEmailVerified(late.copy(id = "idn_x", accountId = "acc_nope"), expectEmailVerified = true), "no such account")
    }

    @Test
    fun `an email change that saw the account unverified is refused once the mailbox was proven`() {
        squatted()
        assertTrue(repo.proveMailbox("acc_1", now, MailboxProof(keepIdentityIds = setOf("idn_pw"))))
        assertEquals(ChangeEmailResult.STALE, repo.changeEmail("acc_1", "attacker@example.com", now, expectEmailVerified = false))
        assertEquals("victim@example.com", repo.findById("acc_1")!!.email)
        assertEquals(ChangeEmailResult.CHANGED, repo.changeEmail("acc_1", "owner-new@example.com", now, expectEmailVerified = true))
    }

    @Test
    fun `the proof transaction also closes the account's open codes and the address's sign-up attempts`() {
        squatted()
        listOf(
            challenge("c_change", ChallengePurposes.EMAIL_CHANGE, "acc_1", "acc_1"), challenge("c_reauth", ChallengePurposes.REAUTH, "acc_1", "acc_1"),
            challenge("c_delete", ChallengePurposes.DELETE_CONFIRM, "acc_1", "acc_1"), challenge("c_signup", ChallengePurposes.SIGN_UP, "victim@example.com", null),
            challenge("c_other_signup", ChallengePurposes.SIGN_UP, "someone-else@example.com", null),
        ).forEach(AccountDb.challenges::insert)
        assertTrue(repo.proveMailbox("acc_1", now, MailboxProof(keepIdentityIds = setOf("idn_pw"))))
        assertEquals(listOf("c_other_signup"), listOf("c_change", "c_reauth", "c_delete", "c_signup", "c_other_signup").filter { AccountDb.challenges.find(it) != null })
    }

    @Test
    fun `a late identity insert racing the mailbox proof never survives - either the proof deletes it or the row-locked check refuses it`() {
        repeat(30) { round ->
            AccountDb.clean()
            squatted()
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val late = pool.submit<AddIdentityResult> { go.await(); repo.addIdentityIfEmailVerified(Identity("idn_late_$round", "acc_1", "kakao", "k-late-$round", true, null, null, now), expectEmailVerified = false) }
            val proof = pool.submit<Boolean> { go.await(); repo.proveMailbox("acc_1", now, MailboxProof(keepIdentityIds = setOf("idn_pw"), passwordSecret = "{bcrypt}owner", newPasswordIdentityId = "idn_new_$round")) }
            go.countDown()
            late.get(); assertTrue(proof.get())
            pool.shutdown()
            assertNull(repo.findIdentity("kakao", "k-late-$round"), "round $round")
            assertEquals(1, repo.identitiesOf("acc_1").size, "round $round")
        }
    }

    @Test
    fun `an email change racing the mailbox proof is either complete before it or refused after it - never half of each`() {
        repeat(30) { round ->
            AccountDb.clean()
            squatted()
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val change = pool.submit<ChangeEmailResult> { go.await(); repo.changeEmail("acc_1", "attacker-$round@example.com", now, expectEmailVerified = false) }
            val proof = pool.submit<Boolean> { go.await(); repo.proveMailbox("acc_1", now, MailboxProof(keepIdentityIds = setOf("idn_pw"))) }
            go.countDown()
            val result = change.get(); proof.get()
            pool.shutdown()
            when (result) {
                ChangeEmailResult.CHANGED -> assertEquals("attacker-$round@example.com", repo.findById("acc_1")!!.email, "round $round")
                ChangeEmailResult.STALE -> assertEquals("victim@example.com", repo.findById("acc_1")!!.email, "round $round")
                else -> error("unexpected $result")
            }
        }
    }
}
