package dev.sumin.skeleton.dashboard.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.dashboard.DashboardDailyRow
import java.time.Instant
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate

private object DashboardDb {
    val dataSource = DbTestDatabase.dataSource().let { source ->
        source as DriverManagerDataSource
        HikariDataSource().apply { jdbcUrl = source.url; username = source.username; password = source.password; maximumPoolSize = 8 }
    }.also { Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate() }
    val jdbc = NamedParameterJdbcTemplate(dataSource)
    val tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
    var now: Instant = Instant.parse("2026-10-27T04:00:00.123456Z")
    val time = TimeProvider { now }
    val daily = JdbcDashboardDailyStore(jdbc, tx, DbTestDatabase.dialect, time)
    val layouts = JdbcDashboardLayoutStore(jdbc, tx, DbTestDatabase.dialect, time)
}

/** 적재 멱등: 같은 행을 두 번 써도 한 줄 · 같은 값, 값이 바뀌면 덮어쓴다, 다른 차원은 따로. 두 방언 */
class DashboardDailyStoreTest {
    private val store = DashboardDb.daily
    private val day = LocalDate.parse("2026-10-26")

    @BeforeTest
    fun clean() {
        DashboardDb.jdbc.update("delete from dashboard_daily", emptyMap<String, Any>())
        DashboardDb.jdbc.update("delete from dashboard_layouts", emptyMap<String, Any>())
    }

    private fun row(value: Long, dimension: String = "", d: LocalDate = day, kind: String = "referrer") = DashboardDailyRow(d, "cloudflare", kind, dimension, value)
    private fun count() = DashboardDb.jdbc.queryForObject("select count(*) from dashboard_daily", emptyMap<String, Any>(), Long::class.java)

    @Test
    fun `writing the same rows twice leaves one row each with the same value`() {
        val rows = listOf(row(10, "google.com"), row(4, "bing.com"), row(14))
        store.upsert(rows)
        store.upsert(rows)

        assertEquals(3, count())
        assertEquals(listOf("" to 14L), store.read("cloudflare", "referrer", day, day).filter { it.dimension == "" }.map { it.dimension to it.value })
        assertEquals(10, store.read("cloudflare", "referrer", day, day).single { it.dimension == "google.com" }.value)
    }

    @Test
    fun `reloading a day where a dimension dropped out leaves no old line for it, and other kinds, days and sources are untouched`() {
        store.upsert(listOf(row(10, "google.com"), row(4, "bing.com"), row(14), row(9, "/a", kind = "path"), row(5, "google.com", d = day.minusDays(1))))
        store.upsert(listOf(DashboardDailyRow(day, "ga", "referrer", "x", 1)))
        store.upsert(listOf(row(12, "google.com"), row(12)))   // 둘째 적재: bing.com 이 상위 밖으로 밀렸다

        val referrers = store.read("cloudflare", "referrer", day, day).associate { it.dimension to it.value }
        assertEquals(mapOf("google.com" to 12L, "" to 12L), referrers)
        assertEquals(9, store.read("cloudflare", "path", day, day).single().value)
        assertEquals(5, store.read("cloudflare", "referrer", day.minusDays(1), day.minusDays(1)).single().value)
        assertEquals(1, store.read("ga", "referrer", day, day).single().value)
    }

    @Test
    fun `a changed value overwrites and bumps the load time`() {
        store.upsert(listOf(row(10, "google.com")))
        val first = store.lastIngested("cloudflare")
        DashboardDb.now = DashboardDb.now.plusSeconds(3600)
        store.upsert(listOf(row(25, "google.com")))

        assertEquals(1, count())
        assertEquals(25, store.read("cloudflare", "referrer", day, day).single().value)
        assertEquals(first!!.plusSeconds(3600), store.lastIngested("cloudflare"))
    }

    @Test
    fun `other dimensions, days and kinds are separate rows - dimensions differing only in case too`() {
        store.upsert(listOf(row(1, "google.com"), row(2, "Google.com"), row(3, "google.com", d = day.minusDays(1)), row(4, "google.com", kind = "path")))

        assertEquals(4, count())
        assertEquals(setOf(1L, 2L), store.read("cloudflare", "referrer", day, day).map { it.value }.toSet())
        assertEquals(3, store.read("cloudflare", "referrer", day.minusDays(1), day).size)
    }

    @Test
    fun `the last load time is null before anything was loaded and per source`() {
        assertNull(store.lastIngested("cloudflare"))
        store.upsert(listOf(row(1)))
        assertNull(store.lastIngested("ga"))
    }

    @Test
    fun `a layout is saved, overwritten and erased with its account`() {
        val layouts = DashboardDb.layouts
        assertNull(layouts.find("acc_1"))
        layouts.save("acc_1", """{"hidden":["a"]}""")
        layouts.save("acc_1", """{"hidden":["b"]}""")
        layouts.save("acc_2", "{}")

        assertEquals("""{"hidden":["b"]}""", layouts.find("acc_1"))
        DashboardLayoutErasure(layouts).erase(ErasureRequest("acc_1", "deleted:x"))
        DashboardLayoutErasure(layouts).erase(ErasureRequest("acc_1", "deleted:x"))   // 멱등
        assertNull(layouts.find("acc_1"))
        assertEquals("{}", layouts.find("acc_2"))
    }
}
