package dev.sumin.skeleton.alert.jdbc

import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.AlertSeverity
import dev.sumin.skeleton.alert.AlertStore
import dev.sumin.skeleton.alert.BuiltInAlertKind
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** 접기 판정이 DB 의 조건부 갱신이라는 것 — 같은 소스가 PostgreSQL · MySQL 두 벌에서 돈다 */
@Import(DbTestcontainers::class, JdbcAlertStoreIntegrationTest.Fixtures::class)
@SpringBootTest(
    classes = [AlertJdbcTestApplication::class],
    properties = ["skeleton.alert.webhook-url=", "skeleton.alert-jdbc.retention.enabled=true", "skeleton.alert-jdbc.retention.keep=30d", "skeleton.alert-jdbc.retention.interval=1h"],
)
class JdbcAlertStoreIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun time() = MutableTime()
    }

    class MutableTime(var now: Instant = Instant.parse("2026-10-06T00:00:00Z")) : TimeProvider { override fun now(): Instant = now }

    private enum class Kind(override val severity: AlertSeverity, override val title: String, override val minInterval: Duration) : AlertKind {
        STUCK(AlertSeverity.WARN, "Order stuck", Duration.ofMinutes(10)),
        PAID(AlertSeverity.INFO, "Paid", Duration.ZERO),
    }

    @Autowired lateinit var store: AlertStore
    @Autowired lateinit var jdbcStore: JdbcAlertStore
    @Autowired lateinit var alerts: OwnerAlerts
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun clean() {
        jdbc.sql("delete from skeleton_alerts").update()
        time.now = Instant.parse("2026-10-06T00:00:00Z")
    }

    private fun record(kind: AlertKind, key: String, detail: String = "d") =
        store.record(kind, key, kind.severity, kind.title, detail, time.now, kind.minInterval)

    @Test
    fun `the JDBC store replaces the in-memory default`() {
        assertTrue(store is JdbcAlertStore)
    }

    @Test
    fun `first sends, inside the interval folds, after the interval sends again carrying the folded count`() {
        assertTrue(record(Kind.STUCK, "o1").send)
        time.now = time.now.plusSeconds(60)
        assertEquals(false, record(Kind.STUCK, "o1", "second").send)
        assertEquals(false, record(Kind.STUCK, "o1", "third").send)
        assertTrue(record(Kind.STUCK, "o2").send, "another key is independent")

        time.now = time.now.plus(Duration.ofMinutes(10))
        val again = record(Kind.STUCK, "o1", "latest")

        assertTrue(again.send)
        assertEquals(2, again.suppressedFolded)
        assertEquals(4, again.occurrences)
        val row = assertNotNull(jdbcStore.find("STUCK", "o1"))
        assertEquals(0, row.suppressedCount)
        assertEquals(2, row.lastSuppressed)
        assertEquals("latest", row.detail)
    }

    @Test
    fun `interval zero always sends`() {
        repeat(3) { assertTrue(record(Kind.PAID, "order-1").send) }
        assertEquals(3, jdbcStore.find("PAID", "order-1")!!.occurrences)
    }

    @Test
    fun `many callers at once for the same kind and key send exactly once`() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (1..8).map { pool.submit<Boolean> { start.await(); record(Kind.STUCK, "race").send } }
        start.countDown()
        val sent = results.count { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, sent)
        val row = jdbcStore.find("STUCK", "race")!!
        assertEquals(8, row.occurrences)
        assertEquals(7, row.suppressedCount)
    }

    @Test
    fun `recent lists the latest first`() {
        record(Kind.PAID, "a")
        time.now = time.now.plusSeconds(5)
        record(Kind.PAID, "b")
        assertEquals(listOf("b", "a"), jdbcStore.recent(10).map { it.key })
    }

    @Test
    fun `with retention on, recording drops rows older than keep (checked at most once per interval)`() {
        record(Kind.PAID, "old")
        time.now = time.now.plus(Duration.ofDays(31))
        record(Kind.PAID, "fresh")

        assertEquals(listOf("fresh"), jdbcStore.recent(10).map { it.key })
        assertNull(jdbcStore.find("PAID", "old"))
    }

    @Test
    fun `purgeOlderThan deletes by last occurrence and says how many`() {
        record(Kind.PAID, "x")
        assertEquals(0, jdbcStore.purgeOlderThan(time.now.minusSeconds(1)))
        assertEquals(1, jdbcStore.purgeOlderThan(time.now.plusSeconds(1)))
    }

    @Test
    fun `OwnerAlerts writes through the store from the module wiring`() {
        alerts.emit(BuiltInAlertKind.TEST, key = "wired", detail = "hello", immediate = true)

        val row = assertNotNull(jdbcStore.find("TEST", "wired"))
        assertEquals("hello", row.detail)
        assertEquals(AlertSeverity.INFO.name, row.severity)
    }
}
