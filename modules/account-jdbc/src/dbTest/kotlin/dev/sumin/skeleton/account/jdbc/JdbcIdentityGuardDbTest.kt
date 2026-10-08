package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountPatch
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.AddIdentityResult
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.RemoveIdentityResult
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 로그인 수단 연결 · 해제의 판정은 계정 행 락 안에서 — 정지된 계정은 수단이 늘지도 줄지도 않고, 한 계정에 같은 제공자는 동시 연결에도 하나 (두 방언 모두) */
class JdbcIdentityGuardDbTest {
    private val repo = AccountDb.accounts
    private val now = Instant.parse("2026-10-06T00:00:00.123456Z")

    @BeforeTest fun clean() = AccountDb.clean()

    private fun account(id: String, email: String?) = Account(id, email, true, AccountStatus.ACTIVE, setOf("USER"), "Ann", "ko", "Asia/Seoul", now, now)
    private fun identity(id: String, account: String, method: String, subject: String, secret: String? = null) = Identity(id, account, method, subject, true, secret, null, now)

    private fun seeded() {
        assertNotNull(repo.insert(account("acc_1", "ann@example.com"), listOf(identity("idn_pw", "acc_1", "password", "ann@example.com", "{bcrypt}h"), identity("idn_g", "acc_1", "google", "g-ann"))))
    }

    @Test
    fun `a frozen account's methods cannot be removed or added - the status is read under the row lock`() {
        seeded()
        repo.update("acc_1", AccountPatch(status = AccountStatus.SUSPENDED), now)
        assertEquals(RemoveIdentityResult.NOT_ACTIVE, repo.removeIdentityUnlessLast("acc_1", "idn_g", setOf("password", "google")))
        assertEquals(AddIdentityResult.NOT_ACTIVE, repo.addIdentityIfEmailVerified(identity("idn_k", "acc_1", "kakao", "k-ann"), null))
        assertEquals(AddIdentityResult.NOT_ACTIVE, repo.addIdentityIfEmailVerified(identity("idn_k", "acc_1", "kakao", "k-ann"), true))
        assertEquals(setOf("password", "google"), repo.identitiesOf("acc_1").map { it.method }.toSet())
        assertNull(repo.findIdentity("kakao", "k-ann"))
        // lifted: both work again
        repo.update("acc_1", AccountPatch(status = AccountStatus.ACTIVE, clearSuspendedReason = true), now)
        assertEquals(AddIdentityResult.ADDED, repo.addIdentityIfEmailVerified(identity("idn_k", "acc_1", "kakao", "k-ann"), null))
        assertEquals(RemoveIdentityResult.REMOVED, repo.removeIdentityUnlessLast("acc_1", "idn_g", setOf("password", "google")))
    }

    @Test
    fun `a method the account already has is METHOD_EXISTS, a subject another account holds is DUPLICATE`() {
        seeded()
        repo.insert(account("acc_2", "bob@example.com"), listOf(identity("idn_k2", "acc_2", "kakao", "k-bob")))
        assertEquals(AddIdentityResult.METHOD_EXISTS, repo.addIdentityIfEmailVerified(identity("idn_g2", "acc_1", "google", "g-other"), null))
        assertEquals(AddIdentityResult.DUPLICATE, repo.addIdentityIfEmailVerified(identity("idn_k1", "acc_1", "kakao", "k-bob"), null))
    }

    @Test
    fun `two simultaneous links of different subjects of one provider to one account - exactly one wins`() {
        repeat(30) { round ->
            AccountDb.clean()
            assertNotNull(repo.insert(account("acc_1", "ann@example.com"), listOf(identity("idn_pw", "acc_1", "password", "ann@example.com", "{bcrypt}h"))))
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val results = (1..2).map { i -> pool.submit<AddIdentityResult> { go.await(); repo.addIdentityIfEmailVerified(identity("idn_g$i", "acc_1", "google", "g-$round-$i"), true) } }
            go.countDown()
            val outcomes = results.map { it.get() }
            pool.shutdown()
            assertEquals(1, outcomes.count { it == AddIdentityResult.ADDED }, "round $round: $outcomes")
            assertEquals(1, outcomes.count { it == AddIdentityResult.METHOD_EXISTS }, "round $round: $outcomes")
            assertEquals(1, repo.identitiesOf("acc_1").count { it.method == "google" }, "round $round")
        }
    }
}
