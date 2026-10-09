package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountBlocks
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.ClaimLoginIdResult
import dev.sumin.skeleton.account.LoginIds
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 두 DB 에서 — 로그인 아이디의 두 겹 유일함(`accounts.login_id` 유니크 + `account_used_login_ids` 기본키)이 같게 동작한다:
 * 동시에 정하면 하나만 이기고, 탈퇴 · 행 삭제 · 운영자 지우기 뒤에도 같은 아이디를 다시 정하지 못한다. 해시는 서비스가 계산해 넘기고 저장소는 비밀을 모른다.
 */
class JdbcLoginIdDbTest {
    private val repo = AccountDb.accounts
    private val jdbc = AccountDb.jdbc
    private val now = Instant.parse("2026-10-09T00:00:00.123456Z")
    private val blocks = AccountBlocks(AccountDb.blocks, "a-long-random-block-secret".toByteArray(), Duration.ZERO, TimeProvider.systemUtc())

    @BeforeTest fun clean() = AccountDb.clean()

    private fun acc(id: String, status: AccountStatus = AccountStatus.ACTIVE, loginId: String? = null, purgeAfter: Instant? = null) =
        Account(id, "$id@example.com", true, status, setOf("USER"), null, null, null, now, now, loginId = loginId, purgeAfter = purgeAfter, deletedAt = purgeAfter?.minusSeconds(60))

    private fun hash(loginId: String) = blocks.loginIdHash(loginId)

    private fun claim(id: String, loginId: String) = repo.claimLoginId(id, loginId, hash(loginId), now)

    private fun count(sql: String) = jdbc.queryForObject(sql, emptyMap<String, Any>(), Int::class.java)

    @Test
    fun `a login id round-trips through insert and findByLoginId, and its hash is recorded in the same insert`() {
        assertTrue(repo.insert(acc("acc_1", loginId = "ann.kim"), emptyList(), hash("ann.kim")))
        assertEquals("acc_1", repo.findByLoginId("ann.kim")?.id)
        assertEquals("ann.kim", repo.findById("acc_1")?.loginId)
        assertTrue(repo.loginIdUsed(hash("ann.kim")))
        assertFalse(repo.loginIdUsed(hash("bob")))
        assertNull(repo.findByLoginId("bob"))
    }

    @Test
    fun `any number of accounts may have no login id, and the used table holds only a hash and a time - no account id`() {
        assertTrue(repo.insert(acc("acc_1"), emptyList()))
        assertTrue(repo.insert(acc("acc_2"), emptyList()))
        assertEquals(2, count("select count(*) from accounts where login_id is null"))
        assertEquals(0, count("select count(*) from account_used_login_ids"))
        repo.insert(acc("acc_3", loginId = "cat"), emptyList(), hash("cat"))
        val columns = jdbc.queryForList(
            "select column_name from information_schema.columns where table_name = 'account_used_login_ids' and " +
                (if (DbTestDatabase.vendor == "mysql") "table_schema = database()" else "table_schema = current_schema()"),
            emptyMap<String, Any>(),
        ).map { (it["column_name"] ?: it["COLUMN_NAME"]).toString().lowercase() }.toSet()
        assertEquals(setOf("hash", "used_at"), columns)
    }

    @Test
    fun `an insert whose id or hash is already in use is refused and leaves neither the account nor a hash behind`() {
        assertTrue(repo.insert(acc("acc_1", loginId = "ann"), emptyList(), hash("ann")))
        assertFalse(repo.insert(acc("acc_2", loginId = "ann"), emptyList(), hash("ann")))
        assertNull(repo.findById("acc_2"))
        // 아이디 열은 새것이지만 해시가 이미 쓰인 경우 (지워진 계정의 아이디) — 계정 행도 남지 않는다
        assertFalse(repo.insert(acc("acc_3", loginId = "zed"), emptyList(), hash("ann")))
        assertNull(repo.findById("acc_3"))
        assertEquals(1, count("select count(*) from account_used_login_ids"))
    }

    @Test
    fun `claiming reports the five results and a refused claim records no hash`() {
        repo.insert(acc("acc_1"), emptyList())
        repo.insert(acc("acc_2"), emptyList())
        repo.insert(acc("acc_sus", status = AccountStatus.SUSPENDED), emptyList())
        repo.insert(acc("acc_del", status = AccountStatus.DELETED, purgeAfter = now.plusSeconds(3600)), emptyList())
        assertEquals(ClaimLoginIdResult.CLAIMED, claim("acc_1", "ann"))
        assertEquals("ann", repo.findById("acc_1")?.loginId)
        assertEquals(ClaimLoginIdResult.ALREADY_SET, claim("acc_1", "ann2"))
        assertEquals(ClaimLoginIdResult.TAKEN, claim("acc_2", "ann"))
        assertEquals(ClaimLoginIdResult.NOT_ACTIVE, claim("acc_sus", "bob"))
        assertEquals(ClaimLoginIdResult.NOT_ACTIVE, claim("acc_del", "bob"))
        assertEquals(ClaimLoginIdResult.NOT_FOUND, claim("acc_nope", "bob"))
        assertFalse(repo.loginIdUsed(hash("ann2")))
        assertFalse(repo.loginIdUsed(hash("bob")))
        assertNull(repo.findById("acc_2")?.loginId)
        assertEquals(1, count("select count(*) from account_used_login_ids"))
    }

    @Test
    fun `when many accounts claim the same id at once exactly one wins and all the others see TAKEN`() {
        val n = 12
        repeat(n) { repo.insert(acc("acc_$it"), emptyList()) }
        val pool = Executors.newFixedThreadPool(n)
        val ready = CountDownLatch(n)
        val go = CountDownLatch(1)
        val results = (0 until n).map { i ->
            pool.submit<ClaimLoginIdResult> { ready.countDown(); go.await(); claim("acc_$i", "race.winner") }
        }
        ready.await(); go.countDown()
        val all = results.map { it.get() }
        pool.shutdown()
        assertEquals(1, all.count { it == ClaimLoginIdResult.CLAIMED }, all.toString())
        assertEquals(n - 1, all.count { it == ClaimLoginIdResult.TAKEN }, all.toString())
        assertEquals(1, count("select count(*) from accounts where login_id = 'race.winner'"))
        assertEquals(1, count("select count(*) from account_used_login_ids"))
    }

    @Test
    fun `an upper-case input meets the same id once the service normalises it - the column is compared exactly`() {
        repo.insert(acc("acc_1"), emptyList())
        repo.insert(acc("acc_2"), emptyList())
        val first = LoginIds.normalize("Ann.Kim")
        val second = LoginIds.normalize(" ANN.KIM ")
        assertEquals(ClaimLoginIdResult.CLAIMED, claim("acc_1", first))
        assertEquals(ClaimLoginIdResult.TAKEN, claim("acc_2", second))
        assertEquals("ann.kim", jdbc.queryForObject("select login_id from accounts where id = 'acc_1'", emptyMap<String, Any>(), String::class.java), "the stored value is the normalised one")
        assertEquals("acc_1", repo.findByLoginId(LoginIds.normalize("ANN.KIM"))?.id)
    }

    @Test
    fun `erasing empties the column but the id stays taken - neither a claim nor an insert can reuse it`() {
        repo.insert(acc("acc_1", status = AccountStatus.DELETED, loginId = "ann", purgeAfter = now.minusSeconds(60)), emptyList(), hash("ann"))
        repo.insert(acc("acc_2"), emptyList())
        assertTrue(repo.erase("acc_1", now))
        assertNull(repo.findById("acc_1")?.loginId)
        assertNull(repo.findByLoginId("ann"))
        assertEquals(0, count("select count(*) from accounts where login_id is not null"))
        assertTrue(repo.loginIdUsed(hash("ann")))
        assertEquals(ClaimLoginIdResult.TAKEN, claim("acc_2", "ann"))
        assertFalse(repo.insert(acc("acc_3", loginId = "ann"), emptyList(), hash("ann")))
        assertNull(repo.findById("acc_3"))
    }

    @Test
    fun `the DELETE mode purge removes the row but the id stays taken`() {
        repo.insert(acc("acc_1", status = AccountStatus.DELETED, loginId = "ann", purgeAfter = now.minusSeconds(60)), emptyList(), hash("ann"))
        repo.insert(acc("acc_2"), emptyList())
        assertTrue(repo.purge("acc_1", now))
        assertNull(repo.findById("acc_1"))
        assertTrue(repo.loginIdUsed(hash("ann")))
        assertEquals(ClaimLoginIdResult.TAKEN, claim("acc_2", "ann"))
    }

    @Test
    fun `an operator erase or purge of a suspended account keeps its id taken too`() {
        repo.insert(acc("acc_1", status = AccountStatus.SUSPENDED, loginId = "ann"), emptyList(), hash("ann"))
        repo.insert(acc("acc_2", status = AccountStatus.SUSPENDED, loginId = "bob"), emptyList(), hash("bob"))
        repo.insert(acc("acc_3"), emptyList())
        assertTrue(repo.erase("acc_1", now, forced = true))
        assertTrue(repo.purge("acc_2", now, forced = true))
        assertNull(repo.findById("acc_1")?.loginId)
        assertNull(repo.findById("acc_2"))
        assertEquals(ClaimLoginIdResult.TAKEN, claim("acc_3", "ann"))
        assertEquals(ClaimLoginIdResult.TAKEN, claim("acc_3", "bob"))
        assertEquals(ClaimLoginIdResult.CLAIMED, claim("acc_3", "cat"))
    }

    @Test
    fun `an erased row cannot claim an id`() {
        repo.insert(acc("acc_1", status = AccountStatus.DELETED, purgeAfter = now.minusSeconds(60)), emptyList())
        assertTrue(repo.erase("acc_1", now))
        assertEquals(ClaimLoginIdResult.NOT_FOUND, claim("acc_1", "ann"))
        assertFalse(repo.loginIdUsed(hash("ann")))
    }

    @Test
    fun `search by login id matches exactly, and combines with email and status`() {
        repo.insert(acc("acc_1", loginId = "ann"), emptyList(), hash("ann"))
        repo.insert(acc("acc_2", loginId = "ann2"), emptyList(), hash("ann2"))
        repo.insert(acc("acc_3"), emptyList())
        assertEquals(listOf("acc_1"), repo.search(null, null, 0, 10, loginId = "ann").items.map { it.id })
        assertEquals(emptyList(), repo.search(null, null, 0, 10, loginId = "an").items)
        assertEquals(1L, repo.search(null, null, 0, 10, loginId = "ann").total)
        assertEquals(emptyList(), repo.search("acc_2", null, 0, 10, loginId = "ann").items)
        assertEquals(listOf("acc_2"), repo.search("acc_2", AccountStatus.ACTIVE, 0, 10, loginId = "ann2").items.map { it.id })
        assertEquals(3L, repo.search(null, null, 0, 10).total)
    }

    // ---- M2: used_at 은 날짜 단위(UTC 자정) — 계정 행의 시각과 잇지 못한다

    private fun usedAt(loginId: String): Instant =
        jdbc.query("select used_at from account_used_login_ids where hash = :h", mapOf("h" to hash(loginId))) { rs, _ -> DbTestDatabase.dialect.readInstant(rs, "used_at") }.single()!!

    @Test
    fun `used_at is the UTC midnight of the day for both the insert and the claim - never the account's own created_at (M2)`() {
        val midnight = Instant.parse("2026-10-09T00:00:00Z")
        assertTrue(repo.insert(acc("acc_1", loginId = "ann"), emptyList(), hash("ann")))
        assertEquals(midnight, usedAt("ann"), "insert: account.createdAt is $now")
        repo.insert(acc("acc_2"), emptyList())
        val late = Instant.parse("2026-10-09T23:59:59.999999Z")
        assertEquals(ClaimLoginIdResult.CLAIMED, repo.claimLoginId("acc_2", "bob", hash("bob"), late))
        assertEquals(midnight, usedAt("bob"), "claim: now was $late")
        assertEquals(late.truncatedTo(java.time.temporal.ChronoUnit.MICROS), repo.findById("acc_2")!!.updatedAt, "the account row keeps its real time")
    }

    // ---- M10: 두 유니크의 잠금 순서 — 가입(insert)과 정하기(claim)가 같은 아이디를 두고 맞서도 교착이 나지 않는다

    @Test
    fun `an insert and a claim racing for one id in both orders always end with exactly one winner and no error (M10)`() {
        val rounds = 25
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(rounds) { round ->
                val id = "race.r$round"
                repo.insert(acc("claimer_$round"), emptyList())
                val ready = CountDownLatch(2); val go = CountDownLatch(1)
                val inserted = pool.submit<Boolean> { ready.countDown(); go.await(); repo.insert(acc("signer_$round", loginId = id), emptyList(), hash(id)) }
                val claimed = pool.submit<ClaimLoginIdResult> { ready.countDown(); go.await(); claim("claimer_$round", id) }
                ready.await(); go.countDown()
                val a = inserted.get(); val b = claimed.get()
                assertEquals(1, listOf(a, b == ClaimLoginIdResult.CLAIMED).count { it }, "round $round: insert=$a claim=$b")
                assertEquals(1, count("select count(*) from account_used_login_ids where hash = '${hash(id)}'"))
            }
        } finally {
            pool.shutdown()
        }
    }
}
