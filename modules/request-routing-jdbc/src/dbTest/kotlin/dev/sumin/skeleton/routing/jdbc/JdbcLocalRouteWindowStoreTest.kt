package dev.sumin.skeleton.routing.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.routing.LocalRouteWindowStore
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** 모듈 마이그레이션을 깔고 두 방언에서 같은 뜻인지 — 열린 줄 찾기 · 열면 앞 줄을 닫기 · 모두 닫기 · 마이크로초까지 같은 시각 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [RequestRoutingJdbcTestApplication::class], properties = ["skeleton.routing.enabled=true"])
class JdbcLocalRouteWindowStoreTest {
    @Autowired lateinit var store: LocalRouteWindowStore
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dialect: SqlDialect

    private val now = Instant.now().truncatedTo(ChronoUnit.MICROS).plusNanos(123_000)   // 마이크로초 자리까지 값이 있는 시각

    @BeforeTest
    fun clean() {
        jdbc.sql("delete from routing_local_windows").update()
    }

    @Test
    fun `the jdbc store is what the module wires when routing is on`() {
        assertIs<JdbcLocalRouteWindowStore>(store)
    }

    @Test
    fun `findOpen ignores closed and expired windows`() {
        val closed = store.open("alice", "closed one", now, now.plus(Duration.ofHours(1)))
        store.closeAll("alice", now)
        store.open("alice", "expired one", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)))

        assertNull(store.findOpen(now))
        assertNotNull(jdbc.sql("select closed_at from routing_local_windows where id = :id").param("id", closed.id).query { rs, _ -> dialect.readInstant(rs, "closed_at") }.single())
    }

    @Test
    fun `findOpen picks the latest of rows that are open at once`() {
        insertOpen("older", now.plus(Duration.ofHours(1)))
        insertOpen("newer", now.plus(Duration.ofMinutes(5)))

        assertEquals("newer", store.findOpen(now)?.reason)
    }

    @Test
    fun `open closes the previous open rows and fills closed_by and closed_at`() {
        val first = store.open("alice", "first", now, now.plus(Duration.ofHours(1)))
        val second = store.open("bob", "second", now.plusSeconds(10), now.plus(Duration.ofHours(2)))

        val rows = jdbc.sql("select id, closed_by, closed_at from routing_local_windows order by id")
            .query { rs, _ -> Triple(rs.getLong("id"), rs.getString("closed_by"), dialect.readInstant(rs, "closed_at")) }.list()
        assertEquals(listOf(first.id, second.id), rows.map { it.first })
        assertEquals("bob", rows[0].second)
        assertEquals(now.plusSeconds(10), rows[0].third)
        assertNull(rows[1].second)
        assertEquals(second.id, store.findOpen(now.plusSeconds(11))?.id)
    }

    @Test
    fun `closeAll closes what is open and returns the ids, nothing the second time`() {
        val opened = store.open("alice", "hotfix", now, now.plus(Duration.ofHours(1)))

        assertEquals(listOf(opened.id), store.closeAll("bob", now.plusSeconds(1)))
        assertNull(store.findOpen(now.plusSeconds(1)))
        assertEquals(emptyList(), store.closeAll("bob", now.plusSeconds(2)))
    }

    @Test
    fun `instants round trip to the microsecond`() {
        val opened = store.open("alice", "hotfix", now, now.plus(Duration.ofMinutes(30)))
        val read = store.findOpen(now)

        assertEquals(opened, read)
        assertEquals(now.truncatedTo(ChronoUnit.MICROS), read?.openedAt)
    }

    private fun insertOpen(reason: String, openUntil: Instant) {
        jdbc.sql("insert into routing_local_windows (opened_by, reason, opened_at, open_until) values ('alice', :reason, :at, :until)")
            .param("reason", reason).param("at", dialect.instantParam(now)).param("until", dialect.instantParam(openUntil)).update()
    }
}
