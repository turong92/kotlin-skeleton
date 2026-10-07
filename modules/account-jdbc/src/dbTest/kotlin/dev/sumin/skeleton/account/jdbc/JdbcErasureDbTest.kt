package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.challenge.ChallengeRow
import dev.sumin.skeleton.account.events.AccountEvent
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.account.token.TokenRow
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 두 DB 에서 — 지울 때 행은 남고 개인정보는 한 트랜잭션으로 사라지고, 이메일 유니크는 NULL 을 여럿 허용하고, 되살리기와 지우기 중 하나만 이긴다 */
class JdbcErasureDbTest {
    private val repo = AccountDb.accounts
    private val jdbc = AccountDb.jdbc
    private val now = Instant.parse("2026-10-06T00:00:00.123456Z")
    private val due = now.plus(Duration.ofDays(30))

    @BeforeTest fun clean() = AccountDb.clean()

    private fun leaving(id: String = "acc_1", email: String = "ann@example.com", purgeAfter: Instant = now.minusSeconds(60)) {
        assertTrue(
            repo.insert(
                Account(id, email, true, AccountStatus.DELETED, setOf("USER", "ADMIN"), "Ann Kim", "ko", "Asia/Seoul", now, now, lastLoginAt = now, suspendedReason = "spam", deletedAt = now.minusSeconds(3600), purgeAfter = purgeAfter),
                listOf(
                    Identity("idn_${id}_pw", id, "password", email, true, "{bcrypt}hash", null, now),
                    Identity("idn_${id}_g", id, "google", "google-subject-$id", true, null, null, now),
                ),
            ),
        )
    }

    private fun token(hash: String, purpose: String, subject: String, account: String?, payload: String? = null) =
        AccountDb.tokens.insert(TokenRow(hash, purpose, subject, account, payload, now, now.plusSeconds(600)))

    private fun challenge(id: String, purpose: String, subject: String, account: String?, payload: String? = null, ip: String? = null) =
        AccountDb.challenges.insert(ChallengeRow(id, purpose, subject, account, null, payload, null, "codehash", 5, 0, now, now.plusSeconds(600), now, ip))

    @Test
    fun `erasing keeps only the id, created_at and a terminal status and removes everything personal in one go`() {
        leaving()
        token("h1", TokenPurposes.PASSWORD_RESET, "ann@example.com", "acc_1")
        token("h2", TokenPurposes.MAGIC_LINK, "ann@example.com", null)
        token("h3", "email_change", "acc_1", "acc_1", payload = "new-address@example.com")
        challenge("c1", ChallengePurposes.EMAIL_CHANGE, "acc_1", "acc_1", payload = "new-address@example.com", ip = "203.0.113.77")
        challenge("c2", ChallengePurposes.SIGN_UP, "ann@example.com", null, ip = "203.0.113.77")
        AccountDb.audit.on(AccountEvent(AccountEventType.LOGIN_SUCCESS, "acc_1", now, "203.0.113.77", mapOf("method" to "google")))
        AccountDb.audit.on(AccountEvent(AccountEventType.LOGIN_SUCCESS, "acc_other", now, "198.51.100.9", mapOf("method" to "password")))

        assertTrue(repo.erase("acc_1", now))

        val kept = assertNotNull(repo.findById("acc_1"))
        assertEquals(AccountStatus.ERASED, kept.status)
        assertNull(kept.email); assertNull(kept.displayName); assertNull(kept.locale); assertNull(kept.timeZone); assertNull(kept.suspendedReason); assertNull(kept.lastLoginAt); assertNull(kept.purgeAfter)
        assertEquals(now, kept.erasedAt)
        assertEquals(now, kept.createdAt)
        assertFalse(kept.emailVerified)
        assertTrue(kept.roles.isEmpty())
        assertTrue(repo.identitiesOf("acc_1").isEmpty())
        assertEquals(0, jdbc.queryForObject("select count(*) from account_tokens", emptyMap<String, Any>(), Int::class.java))
        assertEquals(0, jdbc.queryForObject("select count(*) from account_challenges", emptyMap<String, Any>(), Int::class.java))
        // 감사: 사건 줄은 남고 IP · 상세만 비운다 — 다른 계정의 줄은 건드리지 않는다
        val mine = jdbc.queryForList("select ip, detail from account_audit where account_id = 'acc_1'", emptyMap<String, Any>())
        assertEquals(1, mine.size)
        assertNull(mine.single()["ip"]); assertNull(mine.single()["detail"])
        assertEquals("198.51.100.9", jdbc.queryForObject("select ip from account_audit where account_id = 'acc_other'", emptyMap<String, Any>(), String::class.java))
    }

    @Test
    fun `after erasure no column of any table still holds the email, name, provider subject, IP or other planted personal values`() {
        leaving()
        challenge("c2", ChallengePurposes.SIGN_UP, "ann@example.com", null, ip = "203.0.113.77")
        token("h1", TokenPurposes.PASSWORD_RESET, "ann@example.com", "acc_1")
        AccountDb.audit.on(AccountEvent(AccountEventType.LOGIN_SUCCESS, "acc_1", now, "203.0.113.77", mapOf("method" to "google")))
        val planted = listOf("ann@example.com", "Ann Kim", "google-subject-acc_1", "203.0.113.77", "{bcrypt}hash")
        assertTrue(PlantedDataScan.find(jdbc, DbTestDatabase.vendor, planted).isNotEmpty(), "the scan must see the values before erasure, or it proves nothing")

        assertTrue(repo.erase("acc_1", now))

        assertEquals(emptyList(), PlantedDataScan.find(jdbc, DbTestDatabase.vendor, planted))
    }

    @Test
    fun `the unique email key allows any number of erased accounts, and the freed address can be registered again as a new account`() {
        repeat(3) { leaving("acc_$it", "u$it@example.com") }
        repeat(3) { assertTrue(repo.erase("acc_$it", now)) }
        assertEquals(3, jdbc.queryForObject("select count(*) from accounts where email is null", emptyMap<String, Any>(), Int::class.java))
        val fresh = Account("acc_new", "u0@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now)
        assertTrue(repo.insert(fresh, listOf(Identity("idn_new", "acc_new", "password", "u0@example.com", true, "{bcrypt}x", null, now))))
        assertEquals("acc_new", repo.findByEmail("u0@example.com")!!.id)
        assertEquals(AccountStatus.ERASED, repo.findById("acc_0")!!.status)
    }

    @Test
    fun `erasing needs a deleted account whose grace is over - a restored, still-waiting or already erased account is left alone`() {
        leaving("acc_wait", "w@example.com", purgeAfter = due)
        assertFalse(repo.erase("acc_wait", now), "grace not over")
        assertEquals("w@example.com", repo.findById("acc_wait")!!.email)

        leaving("acc_back", "b@example.com", purgeAfter = now.plusSeconds(1))
        assertTrue(repo.restore("acc_back", AccountStatus.ACTIVE, now))
        assertFalse(repo.erase("acc_back", now.plusSeconds(10)), "restored")
        assertEquals("b@example.com", repo.findById("acc_back")!!.email)

        leaving("acc_done", "d@example.com")
        assertTrue(repo.erase("acc_done", now))
        assertFalse(repo.erase("acc_done", now), "second time: nothing to do")
        assertFalse(repo.erase("acc_missing", now))
    }

    @Test
    fun `an erased account can no longer be restored or given a role`() {
        leaving()
        assertTrue(repo.erase("acc_1", now))
        assertFalse(repo.restore("acc_1", AccountStatus.ACTIVE, now.minusSeconds(3600)))
        assertFalse(repo.grantRole("acc_1", "ADMIN", now))
        assertTrue(repo.findById("acc_1")!!.roles.isEmpty())
    }

    @Test
    fun `the admin search leaves erased accounts out unless the status is asked for`() {
        leaving("acc_gone", "gone@example.com"); repo.erase("acc_gone", now)
        assertTrue(repo.insert(Account("acc_live", "live@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList()))
        assertEquals(listOf("acc_live"), repo.search(null, null, 0, 10).items.map { it.id })
        assertEquals(listOf("acc_gone"), repo.search(null, AccountStatus.ERASED, 0, 10).items.map { it.id })
    }

    @Test
    fun `a restore racing the erasure at the end of the grace has exactly one winner`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(20) { round ->
                val id = "acc_race_$round"
                leaving(id, "race$round@example.com", purgeAfter = now.plusSeconds(1))
                val go = CountDownLatch(1)
                val restoreWon = AtomicInteger(); val eraseWon = AtomicInteger()
                // 되살리는 쪽은 유예가 끝나기 전의 시각, 지우는 쪽은 끝난 뒤의 시각을 본다 (인스턴스 사이 시계 차이)
                val jobs = (1..4).map { pool.submit { go.await(); if (repo.restore(id, AccountStatus.ACTIVE, now)) restoreWon.incrementAndGet() } } +
                    (1..4).map { pool.submit { go.await(); if (repo.erase(id, now.plusSeconds(5))) eraseWon.incrementAndGet() } }
                go.countDown(); jobs.forEach { it.get() }
                assertEquals(1, restoreWon.get() + eraseWon.get(), "round $round: restore=${restoreWon.get()} erase=${eraseWon.get()}")
                val status = repo.findById(id)!!.status
                assertEquals(if (restoreWon.get() == 1) AccountStatus.ACTIVE else AccountStatus.ERASED, status)
                if (status == AccountStatus.ACTIVE) assertEquals("race$round@example.com", repo.findById(id)!!.email)
            }
        } finally { pool.shutdown() }
    }
}
