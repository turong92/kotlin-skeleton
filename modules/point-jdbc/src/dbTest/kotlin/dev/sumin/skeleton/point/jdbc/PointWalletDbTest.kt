package dev.sumin.skeleton.point.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.point.PointCursor
import dev.sumin.skeleton.point.PointEntryKind
import dev.sumin.skeleton.point.PointCancelExceededException
import dev.sumin.skeleton.point.PointInsufficientException
import dev.sumin.skeleton.point.PointSpendNotFoundException
import dev.sumin.skeleton.point.PointSourceConflictException
import dev.sumin.skeleton.point.PointWallet
import dev.sumin.skeleton.point.StorePointWallet
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate

internal object PointDb {
    val dataSource = DbTestDatabase.dataSource().let { source ->
        source as DriverManagerDataSource
        HikariDataSource().apply { jdbcUrl = source.url; username = source.username; password = source.password; maximumPoolSize = 12 }
    }.also { Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate() }
    val jdbc = NamedParameterJdbcTemplate(dataSource)
    val txManager = DataSourceTransactionManager(dataSource)
    val tx = TransactionTemplate(txManager)
    var now: Instant = Instant.parse("2026-10-28T03:00:00.123456Z")
    val time = TimeProvider { now }
    val store = JdbcPointStore(jdbc, tx, DbTestDatabase.dialect)
    val log = JdbcPointOperationLog(jdbc, txManager, DbTestDatabase.dialect)
    val operations = JdbcPointOperations(store, log, txManager, time)
    val wallet: PointWallet = StorePointWallet(store, time)
}

/** 포인트 원장: 돈 같은 영역이라 이중 차감 · 음수 잔액 · 지급 건 멱등 · 소멸을 두 방언에서 고정한다 */
class PointWalletDbTest {
    private val wallet = PointDb.wallet
    private val store = PointDb.store
    private val jdbc = PointDb.jdbc
    private val now get() = PointDb.now

    @BeforeTest
    fun clean() {
        PointDb.now = Instant.parse("2026-10-28T03:00:00.123456Z")
        jdbc.update("delete from point_spend_allocations", emptyMap<String, Any>())
        jdbc.update("delete from point_restore_links", emptyMap<String, Any>())
        jdbc.update("delete from point_operation_attempts", emptyMap<String, Any>())
        jdbc.update("delete from point_operations", emptyMap<String, Any>())
        jdbc.update("delete from point_entries", emptyMap<String, Any>())
        jdbc.update("delete from point_balances", emptyMap<String, Any>())
    }

    private fun entryCount(account: String) = jdbc.queryForObject("select count(*) from point_entries where account_id = :a", mapOf("a" to account), Long::class.java)
    private fun remaining(id: String) = jdbc.queryForObject("select remaining from point_entries where id = :id", mapOf("id" to id), Long::class.java)

    @Test
    fun `a deduction takes the bundle that expires first, the one without an expiry last`() {
        val noExpiry = wallet.earn("a1", 100, null, "GRANT", "g-none")!!
        val soon = wallet.earn("a1", 100, now.plus(Duration.ofDays(10)), "GRANT", "g-soon")!!

        val spent = wallet.spend("a1", 150, "ORDER", "o1")

        assertEquals(-150, spent.delta)
        assertEquals(PointEntryKind.SPEND, spent.kind)
        assertEquals(0, remaining(soon.id))
        assertEquals(50, remaining(noExpiry.id))
        assertEquals(50, wallet.balance("a1").balance)
    }

    @Test
    fun `a deduction or adjustment bigger than the balance is refused and changes nothing`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        val before = entryCount("a1")

        assertFailsWith<PointInsufficientException> { wallet.spend("a1", 101, "ORDER", "o1") }
        assertFailsWith<PointInsufficientException> { wallet.adjust("a1", -101, "typo", null, "op1") }

        assertEquals(100, wallet.balance("a1").balance)
        assertEquals(before, entryCount("a1"))
        assertEquals(100, wallet.spend("a1", 100, "ORDER", "o2").delta * -1)   // 정확히 잔액만큼은 된다
        assertEquals(0, wallet.balance("a1").balance)
        assertEquals(0, wallet.balance("nobody").balance)
    }

    @Test
    fun `an insufficient deduction inside a caller transaction does not poison it`() {
        wallet.earn("a1", 10, null, "GRANT", "g1")

        PointDb.tx.executeWithoutResult {
            assertFailsWith<PointInsufficientException> { wallet.spend("a1", 50, "ORDER", "o1") }
            wallet.spend("a1", 4, "ORDER", "o2")   // 잡고 계속한다
        }

        assertEquals(6, wallet.balance("a1").balance)
    }

    @Test
    fun `the same grant earns once and the same order spends once`() {
        val first = wallet.earn("a1", 100, null, "GRANT", "g1")
        val second = wallet.earn("a1", 100, null, "GRANT", "g1")

        assertNotNull(first)
        assertNull(second)
        assertEquals(100, wallet.balance("a1").balance)
        assertEquals(1, entryCount("a1"))

        val spent = wallet.spend("a1", 30, "ORDER", "o1")
        val again = wallet.spend("a1", 30, "ORDER", "o1")
        assertEquals(spent.id, again.id)
        assertEquals(70, wallet.balance("a1").balance)
    }

    @Test
    fun `the same key with a different amount is refused, for a deduction and for a grant, and nothing changes`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        val spent = wallet.spend("a1", 4, "ORDER", "o1")

        // 40 이 빠졌다고 믿는 호출자에게 4 짜리 첫 줄을 돌려주면 안 된다
        assertFailsWith<PointSourceConflictException> { wallet.spend("a1", 40, "ORDER", "o1") }
        assertFailsWith<PointSourceConflictException> { wallet.earn("a1", 500, null, "GRANT", "g1") }
        assertEquals(96, wallet.balance("a1").balance)
        assertEquals(2, entryCount("a1"))
        assertEquals(spent.id, wallet.spend("a1", 4, "ORDER", "o1").id, "the true repeat still returns the first line")
        assertNull(wallet.earn("a1", 100, Instant.parse("2027-01-01T00:00:00Z"), "GRANT", "g1"), "an expiry difference alone is not a conflict")
    }

    @Test
    fun `an earn and a spend sharing a key are refused both ways`() {
        wallet.earn("a1", 100, null, "GRANT", "k1")

        assertFailsWith<PointSourceConflictException> { wallet.spend("a1", 100, "GRANT", "k1") }
        wallet.spend("a1", 30, "ORDER", "k2")
        assertFailsWith<PointSourceConflictException> { wallet.earn("a1", 30, null, "ORDER", "k2") }
        assertEquals(70, wallet.balance("a1").balance)
        assertEquals(2, entryCount("a1"))
    }

    @Test
    fun `a conflicting key inside a caller transaction does not poison it`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.spend("a1", 4, "ORDER", "o1")

        PointDb.tx.executeWithoutResult {
            assertFailsWith<PointSourceConflictException> { wallet.spend("a1", 40, "ORDER", "o1") }
            assertFailsWith<PointSourceConflictException> { wallet.earn("a1", 7, null, "GRANT", "g1") }
            wallet.spend("a1", 6, "ORDER", "o2")   // 잡고 계속한다
        }

        assertEquals(90, wallet.balance("a1").balance)
    }


    private fun restoredTotal(spendRef: String) = jdbc.queryForObject(
        "select coalesce(sum(a.restored), 0) from point_spend_allocations a join point_entries e on e.id = a.spend_entry_id where e.source_ref = :r",
        mapOf("r" to spendRef), Long::class.java,
    )

    @Test
    fun `a full cancellation puts every point back into its original bundle with its original expiry`() {
        val soon = wallet.earn("a1", 100, now.plus(Duration.ofDays(10)), "GRANT", "g1")!!
        val later = wallet.earn("a1", 50, now.plus(Duration.ofDays(20)), "GRANT", "g2")!!
        wallet.spend("a1", 120, "ORDER", "o1")   // 소멸이 가까운 100 + 그다음 20
        assertEquals(30, wallet.balance("a1").balance)

        val restore = wallet.cancelSpend("a1", "ORDER", "o1", null, "CANCEL", "c1")

        assertEquals(PointEntryKind.RESTORE, restore.kind)
        assertEquals(120, restore.delta)
        assertEquals(100, remaining(soon.id))
        assertEquals(50, remaining(later.id))
        assertEquals(now.plus(Duration.ofDays(10)), store.findBySource("GRANT", "g1")!!.expiresAt)
        assertEquals(150, wallet.balance("a1").balance)
        assertEquals(PointEntryKind.RESTORE, wallet.history("a1", null, 1).single().kind, "the history carries the restore line")
    }

    @Test
    fun `two partial cancellations restore the latest-expiring bundles first and together reach the whole deduction`() {
        val soon = wallet.earn("a1", 100, now.plus(Duration.ofDays(10)), "GRANT", "g1")!!
        val later = wallet.earn("a1", 50, now.plus(Duration.ofDays(20)), "GRANT", "g2")!!
        wallet.spend("a1", 120, "ORDER", "o1")

        wallet.cancelSpend("a1", "ORDER", "o1", 50, "CANCEL", "c1")   // 늦은 묶음 20 + 이른 묶음 30
        assertEquals(30, remaining(soon.id))
        assertEquals(50, remaining(later.id), "the later-expiring bundle is restored first")
        wallet.cancelSpend("a1", "ORDER", "o1", 70, "CANCEL", "c2")
        assertEquals(100, remaining(soon.id))
        assertEquals(50, remaining(later.id))
        assertEquals(120, restoredTotal("o1"))
        assertEquals(150, wallet.balance("a1").balance)
        assertFailsWith<PointCancelExceededException> { wallet.cancelSpend("a1", "ORDER", "o1", null, "CANCEL", "c3") }
    }

    @Test
    fun `a cancellation above what is left of the deduction is refused and changes nothing`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.spend("a1", 40, "ORDER", "o1")
        wallet.cancelSpend("a1", "ORDER", "o1", 30, "CANCEL", "c1")

        assertFailsWith<PointCancelExceededException> { wallet.cancelSpend("a1", "ORDER", "o1", 11, "CANCEL", "c2") }
        assertEquals(90, wallet.balance("a1").balance)
        assertEquals(30, restoredTotal("o1"))
        assertEquals(null, wallet.entryOf("CANCEL", "c2"))
    }

    @Test
    fun `the same cancel key repeats to the first line, a different request on it is a source conflict`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.spend("a1", 40, "ORDER", "o1")
        wallet.spend("a1", 10, "ORDER", "o2")
        val first = wallet.cancelSpend("a1", "ORDER", "o1", 30, "CANCEL", "c1")

        assertEquals(first.id, wallet.cancelSpend("a1", "ORDER", "o1", 30, "CANCEL", "c1").id)
        assertEquals(80, wallet.balance("a1").balance, "restored once")
        assertFailsWith<PointSourceConflictException> { wallet.cancelSpend("a1", "ORDER", "o1", 20, "CANCEL", "c1") }   // 다른 금액
        assertFailsWith<PointSourceConflictException> { wallet.cancelSpend("a1", "ORDER", "o2", 30, "CANCEL", "c1") }   // 다른 차감
        assertFailsWith<PointSourceConflictException> { wallet.cancelSpend("a1", "ORDER", "o1", 10, "ORDER", "o2") }   // 차감이 쓴 키
        assertFailsWith<PointSourceConflictException> { wallet.spend("a1", 30, "CANCEL", "c1") }   // 환원이 쓴 키로 차감
        assertEquals(80, wallet.balance("a1").balance)
    }

    @Test
    fun `a deduction that does not exist cannot be cancelled`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")

        assertFailsWith<PointSpendNotFoundException> { wallet.cancelSpend("a1", "ORDER", "missing", null, "CANCEL", "c1") }
        assertFailsWith<PointSpendNotFoundException> { wallet.cancelSpend("a1", "GRANT", "g1", 10, "CANCEL", "c2") }   // 적립은 차감이 아니다
        assertEquals(100, wallet.balance("a1").balance)
        assertEquals(1, entryCount("a1"))
    }

    @Test
    fun `a deduction made before allocations were recorded cannot be cancelled`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.spend("a1", 40, "ORDER", "o1")
        jdbc.update("delete from point_spend_allocations", emptyMap<String, Any>())   // 이 기능 이전의 차감 모양

        assertFailsWith<PointSpendNotFoundException> { wallet.cancelSpend("a1", "ORDER", "o1", null, "CANCEL", "c1") }
        assertEquals(60, wallet.balance("a1").balance)
    }

    @Test
    fun `points returned to a bundle that has expired are closed at once - a restore line plus an expire line`() {
        val lapsing = wallet.earn("a1", 60, now.plus(Duration.ofDays(10)), "GRANT", "g1")!!
        wallet.earn("a1", 60, null, "GRANT", "g2")
        wallet.spend("a1", 120, "ORDER", "o1")
        PointDb.now = now.plus(Duration.ofDays(11))

        val restore = wallet.cancelSpend("a1", "ORDER", "o1", null, "CANCEL", "c1")

        assertEquals(120, restore.delta)
        assertEquals(0, remaining(lapsing.id))
        assertEquals(60, wallet.balance("a1").balance, "only the part that is still alive comes back")
        val closes = wallet.history("a1", null, 10).filter { it.kind == PointEntryKind.EXPIRE }
        assertEquals(listOf(-60L), closes.map { it.delta })
        assertEquals(60, jdbc.queryForObject("select balance from point_balances where account_id = 'a1'", emptyMap<String, Any>(), Long::class.java))
    }

    @Test
    fun `restoring into a bundle the expiry job already closed does not collide with its earlier expire line`() {
        val bundle = wallet.earn("a1", 100, now.plus(Duration.ofDays(10)), "GRANT", "g1")!!
        wallet.spend("a1", 30, "ORDER", "o1")
        PointDb.now = now.plus(Duration.ofDays(11))
        assertEquals(1, wallet.expireDue(now))   // 남은 70 을 닫는다 - source (EXPIRE, 묶음 id)

        wallet.cancelSpend("a1", "ORDER", "o1", null, "CANCEL", "c1")

        assertEquals(0, remaining(bundle.id))
        assertEquals(0, wallet.balance("a1").balance)
        assertEquals(2, wallet.history("a1", null, 20).count { it.kind == PointEntryKind.EXPIRE })
    }

    @Test
    fun `cancellations racing over one deduction never restore more than was deducted`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.spend("a1", 100, "ORDER", "o1")

        val results = race(4) { i -> runCatching { wallet.cancelSpend("a1", "ORDER", "o1", 60, "CANCEL", "c$i") } }

        // MySQL 의 낡은 스냅샷에서는 늦은 쪽이 다른 예외로 던질 수 있다 - 어느 쪽이든 60 이 두 번 돌아오지는 않는다
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(60, restoredTotal("o1"))
        assertEquals(60, wallet.balance("a1").balance)
        assertEquals(60, jdbc.queryForObject("select balance from point_balances where account_id = 'a1'", emptyMap<String, Any>(), Long::class.java))
    }

    @Test
    fun `a cancellation inside a caller transaction does not poison it when refused`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.spend("a1", 40, "ORDER", "o1")

        PointDb.tx.executeWithoutResult {
            assertFailsWith<PointCancelExceededException> { wallet.cancelSpend("a1", "ORDER", "o1", 41, "CANCEL", "c1") }
            assertFailsWith<PointSpendNotFoundException> { wallet.cancelSpend("a1", "ORDER", "nope", null, "CANCEL", "c2") }
            wallet.cancelSpend("a1", "ORDER", "o1", 40, "CANCEL", "c3")   // 잡고 계속한다
        }

        assertEquals(100, wallet.balance("a1").balance)
    }

    @Test
    fun `a bundle without an expiry is restored before one that expires`() {
        val never = wallet.earn("a1", 50, null, "GRANT", "g1")!!
        val expiring = wallet.earn("a1", 50, now.plus(Duration.ofDays(30)), "GRANT", "g2")!!
        wallet.spend("a1", 80, "ORDER", "o1")   // 소멸이 가까운 50 + 유효기간 없는 30

        wallet.cancelSpend("a1", "ORDER", "o1", 30, "CANCEL", "c1")   // 유효기간 없는 묶음의 30 이 먼저 돌아온다

        assertEquals(50, remaining(never.id))
        assertEquals(0, remaining(expiring.id))
        wallet.cancelSpend("a1", "ORDER", "o1", null, "CANCEL", "c2")
        assertEquals(50, remaining(expiring.id))
    }

    @Test
    fun `cancelling another account's deduction is an error and changes nothing`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.spend("a1", 40, "ORDER", "o1")

        assertFailsWith<IllegalStateException> { wallet.cancelSpend("a2", "ORDER", "o1", 10, "CANCEL", "c1") }
        assertEquals(60, wallet.balance("a1").balance)
        assertEquals(0, wallet.balance("a2").balance)
        assertEquals(0, restoredTotal("o1"))
    }

    @Test
    fun `a source key owned by another account is an error, not a silent no-op`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")

        assertFailsWith<IllegalStateException> { wallet.earn("a2", 100, null, "GRANT", "g1") }
        assertEquals(0, wallet.balance("a2").balance)
    }

    @Test
    fun `two deductions of 80 racing over a balance of 100 let exactly one through`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")

        val results = race(2) { i -> runCatching { wallet.spend("a1", 80, "ORDER", "race-$i") } }

        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is PointInsufficientException)
        assertEquals(20, wallet.balance("a1").balance)
        assertEquals(20, jdbc.queryForObject("select balance from point_balances where account_id = 'a1'", emptyMap<String, Any>(), Long::class.java))
    }

    @Test
    fun `the same order spent from two workers at once is deducted once`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")

        val results = race(4) { runCatching { wallet.spend("a1", 30, "ORDER", "same") } }

        // MySQL 의 낡은 스냅샷에서는 늦은 쪽이 던질 수 있다 - 어느 쪽이든 두 번 빠지지는 않는다
        assertTrue(results.any { it.isSuccess })
        assertEquals(70, wallet.balance("a1").balance)
        assertEquals(2, entryCount("a1"))   // 적립 + 차감 한 줄
    }

    @Test
    fun `the same grant earned from two workers at once is one entry`() {
        val results = race(4) { runCatching { wallet.earn("a1", 100, null, "GRANT", "same") } }

        assertTrue(results.any { it.isSuccess && it.getOrNull() != null })
        assertEquals(100, wallet.balance("a1").balance)
        assertEquals(1, entryCount("a1"))
    }

    @Test
    fun `expiry closes only what is left, once, and an expired bundle cannot be spent before the job runs`() {
        val bundle = wallet.earn("a1", 100, now.plus(Duration.ofDays(10)), "GRANT", "g1")!!
        wallet.earn("a1", 40, null, "GRANT", "g2")
        wallet.spend("a1", 30, "ORDER", "o1")   // 묶음에서 30 을 먼저 쓴다 (소멸이 가까운 쪽)
        assertEquals(70, remaining(bundle.id))

        PointDb.now = now.plus(Duration.ofDays(11))
        // 지났지만 작업이 아직 안 돌았다 - 잔액에서 이미 빠지고, 쓸 수도 없다
        assertEquals(40, wallet.balance("a1").balance)
        assertFailsWith<PointInsufficientException> { wallet.spend("a1", 41, "ORDER", "o2") }

        assertEquals(1, wallet.expireDue(now))
        assertEquals(0, wallet.expireDue(now))

        val expire = store.findBySource("EXPIRE", bundle.id)!!
        assertEquals(-70, expire.delta)
        assertEquals(PointEntryKind.EXPIRE, expire.kind)
        assertEquals(0, remaining(bundle.id))
        assertEquals(40, wallet.balance("a1").balance)
        assertEquals(40, jdbc.queryForObject("select balance from point_balances where account_id = 'a1'", emptyMap<String, Any>(), Long::class.java))
    }

    @Test
    fun `a deduction first closes the expired bundles of that account so the books stay whole`() {
        val bundle = wallet.earn("a1", 100, now.plus(Duration.ofDays(1)), "GRANT", "g1")!!
        wallet.earn("a1", 50, null, "GRANT", "g2")
        PointDb.now = now.plus(Duration.ofDays(2))

        wallet.spend("a1", 20, "ORDER", "o1")

        assertEquals(30, wallet.balance("a1").balance)
        assertEquals(-100, store.findBySource("EXPIRE", bundle.id)!!.delta)
        assertEquals(30, jdbc.queryForObject("select balance from point_balances where account_id = 'a1'", emptyMap<String, Any>(), Long::class.java))
    }

    @Test
    fun `balance reports what expires within the window`() {
        wallet.earn("a1", 10, now.plus(Duration.ofDays(5)), "GRANT", "g1")
        wallet.earn("a1", 20, now.plus(Duration.ofDays(29)), "GRANT", "g2")
        wallet.earn("a1", 40, now.plus(Duration.ofDays(60)), "GRANT", "g3")
        wallet.earn("a1", 80, null, "GRANT", "g4")

        val balance = wallet.balance("a1")

        assertEquals(150, balance.balance)
        assertEquals(30, balance.expiringSoon!!.amount)
        assertNull(wallet.balance("a2").expiringSoon)
    }

    @Test
    fun `an adjustment is a ledger line with its reason, positive with an expiry or negative`() {
        val added = wallet.adjust("a1", 70, "welcome back", now.plus(Duration.ofDays(30)), "op1")
        val taken = wallet.adjust("a1", -20, "duplicate bonus", null, "op1")

        assertEquals(PointEntryKind.ADJUST, added.kind)
        assertEquals("welcome back", added.reason)
        assertEquals(PointEntryKind.ADJUST, taken.kind)
        assertEquals(50, wallet.balance("a1").balance)
        // 같은 조정을 두 번 보내면 두 번 반영된다 - 운영자의 행동마다 한 줄
        wallet.adjust("a1", -20, "duplicate bonus", null, "op1")
        assertEquals(30, wallet.balance("a1").balance)
    }

    @Test
    fun `history is newest first and pages by cursor`() {
        repeat(5) { i ->
            PointDb.now = now.plusSeconds(1)
            wallet.earn("a1", 10L + i, null, "GRANT", "g$i")
        }
        wallet.earn("a2", 999, null, "GRANT", "other")

        val first = wallet.history("a1", null, 2)
        val second = wallet.history("a1", PointCursor(first.last().createdAt, first.last().id), 10)

        assertEquals(listOf(14L, 13L), first.map { it.delta })
        assertEquals(listOf(12L, 11L, 10L), second.map { it.delta })
    }

    @Test
    fun `erasing an account removes its balance and every line, and only theirs`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.earn("a2", 50, null, "GRANT", "g2")

        store.eraseAccount("a1")
        store.eraseAccount("a1")

        assertEquals(0, wallet.balance("a1").balance)
        assertEquals(0, entryCount("a1"))
        assertEquals(50, wallet.balance("a2").balance)
    }

    @Test
    fun `erasing an account also removes its allocation and restore link rows, and only theirs`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.spend("a1", 40, "ORDER", "o1")
        wallet.cancelSpend("a1", "ORDER", "o1", 10, "CANCEL", "c1")
        wallet.earn("a2", 50, null, "GRANT", "g2")
        wallet.spend("a2", 20, "ORDER", "o2")

        store.eraseAccount("a1")

        assertEquals(0, jdbc.queryForObject("select count(*) from point_restore_links", emptyMap<String, Any>(), Long::class.java))
        assertEquals(1, jdbc.queryForObject("select count(*) from point_spend_allocations", emptyMap<String, Any>(), Long::class.java))
    }

    @Test
    fun `the table itself refuses a negative balance`() {
        wallet.earn("a1", 10, null, "GRANT", "g1")

        assertFailsWith<Exception> { jdbc.update("update point_balances set balance = -1 where account_id = 'a1'", emptyMap<String, Any>()) }
    }

    private fun <T> race(workers: Int, task: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val ready = CountDownLatch(workers)
            val go = CountDownLatch(1)
            val futures = (0 until workers).map { i ->
                pool.submit<T> { ready.countDown(); go.await(); task(i) }
            }
            ready.await()
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
