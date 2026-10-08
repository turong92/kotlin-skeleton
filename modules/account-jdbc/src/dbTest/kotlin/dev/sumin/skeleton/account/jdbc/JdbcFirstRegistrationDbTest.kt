package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountPatch
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.ChangeEmailResult
import dev.sumin.skeleton.account.Identity
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 주소 없는 계정의 **첫 이메일 등록 · 연락 이메일 · 버리기**가 서로 · 다른 계정과 겹칠 때 — 진짜 DB(PostgreSQL · MySQL 둘 다)에서.
 * 서비스 계층의 챌린지 소비는 [JdbcChallengeStoreDbTest] 가 맡고, 여기는 마지막 방어선인 계정 행의 락 · 유니크가 이기는지를 본다.
 */
class JdbcFirstRegistrationDbTest {
    private val repo = AccountDb.accounts
    private val now = Instant.parse("2026-10-08T00:00:00.123456Z")

    @BeforeTest fun clean() = AccountDb.clean()

    private fun shell(id: String) = repo.insert(
        Account(id, null, false, AccountStatus.ACTIVE, setOf("USER"), "K", null, null, now, now),
        listOf(Identity("idn_$id", id, "kakao", "kakao-$id", true, null, null, now)),
    )

    @Test
    fun `two address-less accounts registering the same first address at once - exactly one gets it`() {
        repeat(25) { round ->
            AccountDb.clean()
            shell("acc_a"); shell("acc_b")
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val a = pool.submit<ChangeEmailResult> { go.await(); repo.changeEmail("acc_a", "same@example.com", now, expectEmailVerified = false) }
            val b = pool.submit<ChangeEmailResult> { go.await(); repo.changeEmail("acc_b", "same@example.com", now, expectEmailVerified = false) }
            go.countDown()
            val results = listOf(a.get(), b.get())
            pool.shutdown()
            assertEquals(listOf(ChangeEmailResult.CHANGED, ChangeEmailResult.TAKEN), results.sortedBy { it.ordinal }, "round $round: $results")
            assertEquals(1, listOf("acc_a", "acc_b").count { repo.findById(it)!!.email == "same@example.com" }, "round $round")
        }
    }

    @Test
    fun `the first address and the contact address written at once on one account - neither write is lost`() {
        repeat(25) { round ->
            AccountDb.clean()
            shell("acc_a")
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val email = pool.submit<ChangeEmailResult> { go.await(); repo.changeEmail("acc_a", "first@example.com", now, expectEmailVerified = false) }
            val contact = pool.submit<Account?> { go.await(); repo.update("acc_a", AccountPatch(contactEmail = "contact@example.com"), now) }
            go.countDown()
            assertEquals(ChangeEmailResult.CHANGED, email.get()); contact.get()
            pool.shutdown()
            val after = repo.findById("acc_a")!!
            assertEquals("first@example.com", after.email, "round $round")
            assertEquals("contact@example.com", after.contactEmail, "round $round")
        }
    }

    @Test
    fun `an erase racing a first registration and a contact write leaves an empty ERASED row - nothing is written back after the erase`() {
        repeat(25) { round ->
            AccountDb.clean()
            shell("acc_a")
            val due = now.minusSeconds(60)
            repo.update("acc_a", AccountPatch(status = AccountStatus.DELETED, deletedAt = due, purgeAfter = due), now)
            assertTrue(repo.claimErasure("acc_a", now))
            val pool = Executors.newFixedThreadPool(3)
            val go = CountDownLatch(1)
            val erase = pool.submit<Boolean> { go.await(); repo.erase("acc_a", now) }
            val email = pool.submit<ChangeEmailResult> { go.await(); repo.changeEmail("acc_a", "late@example.com", now, expectEmailVerified = false) }
            val contact = pool.submit<Account?> { go.await(); repo.update("acc_a", AccountPatch(contactEmail = "late-contact@example.com"), now) }
            go.countDown()
            assertTrue(erase.get(), "round $round"); email.get(); contact.get()
            pool.shutdown()
            val row = repo.findById("acc_a")!!
            assertEquals(AccountStatus.ERASED, row.status, "round $round")
            assertNull(row.email, "round $round"); assertNull(row.contactEmail, "round $round")
            assertNull(repo.findIdentity("kakao", "kakao-acc_a"), "the social method is free again")
            assertNull(repo.findByEmail("late@example.com"), "round $round")
        }
    }
}
