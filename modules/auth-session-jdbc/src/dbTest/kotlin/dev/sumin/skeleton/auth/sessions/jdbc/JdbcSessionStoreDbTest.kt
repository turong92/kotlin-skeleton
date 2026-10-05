package dev.sumin.skeleton.auth.sessions.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.auth.sessions.AuthSessionProperties
import dev.sumin.skeleton.auth.sessions.SessionClient
import dev.sumin.skeleton.auth.sessions.SessionRecord
import dev.sumin.skeleton.auth.sessions.SessionService
import dev.sumin.skeleton.common.ApplicationException
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
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

object SessionDb {
    val dataSource = DbTestDatabase.dataSource().let { source ->
        source as DriverManagerDataSource
        HikariDataSource().apply { jdbcUrl = source.url; username = source.username; password = source.password; maximumPoolSize = 32 }
    }.also { Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate() }
    val jdbc = NamedParameterJdbcTemplate(dataSource)
    val store = JdbcSessionStore(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), DbTestDatabase.dialect)
}

class JdbcSessionStoreDbTest {
    private val store = SessionDb.store
    private val now = Instant.parse("2026-10-06T00:00:00.123456Z")

    @BeforeTest fun clean() { SessionDb.jdbc.update("delete from skeleton_auth_sessions", emptyMap<String, Any>()) }

    private fun session(id: String = "ses_a", account: String = "acc_1", at: Instant = now) =
        SessionRecord(id, account, "Laptop", "UA", "203.0.113.1", at, at, at.plus(Duration.ofDays(30)))

    @Test
    fun `a session round-trips with microsecond instants and the first token is unused`() {
        store.create(session(), "h".repeat(64))
        val back = store.find("ses_a")!!
        assertEquals(now, back.createdAt)
        assertEquals(now.plus(Duration.ofDays(30)), back.expiresAt)
        assertEquals("Laptop", back.deviceName)
        assertNull(store.findToken("h".repeat(64))!!.usedAt)
    }

    @Test
    fun `a token can be marked used exactly once even from many threads`() {
        store.create(session(), "t".repeat(64))
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val wins = (1..16).map { pool.submit<Boolean> { go.await(); store.markTokenUsed("t".repeat(64), now) } }
        go.countDown()
        assertEquals(1, wins.count { it.get() })
        pool.shutdown()
    }

    @Test
    fun `revoke only flips an open session and revokeAll spares the excepted one`() {
        store.create(session("ses_a"), "a".repeat(64)); store.create(session("ses_b"), "b".repeat(64))
        assertEquals(1, store.revokeAll("acc_1", "ses_b", now, "X"))
        assertTrue(store.find("ses_b")!!.revokedAt == null)
        assertFalse(store.revoke("ses_a", now, "again"))
        assertEquals("X", store.find("ses_a")!!.revokedReason)
    }

    @Test
    fun `listActive hides revoked expired and idle sessions, newest first`() {
        store.create(session("ses_old", at = now.minus(Duration.ofDays(40))), "o".repeat(64))
        store.create(session("ses_a", at = now), "a".repeat(64))
        store.create(session("ses_b", at = now.plusSeconds(5)), "b".repeat(64))
        store.revoke("ses_a", now, "R")
        assertEquals(listOf("ses_b"), store.listActive("acc_1", now.plusSeconds(10), now.minus(Duration.ofDays(14))).map { it.id })
    }

    @Test
    fun `prune and purge delete used tokens and ended sessions with their tokens`() {
        store.create(session("ses_a"), "a".repeat(64)); store.markTokenUsed("a".repeat(64), now)
        store.addToken("ses_a", "n".repeat(64), now)
        store.pruneUsedTokens("ses_a", now.plusSeconds(1))
        assertNull(store.findToken("a".repeat(64)))
        assertTrue(store.findToken("n".repeat(64)) != null)
        store.revoke("ses_a", now, "R")
        assertEquals(1, store.purge(now.plusSeconds(1)))
        assertNull(store.findToken("n".repeat(64)))
    }

    @Test
    fun `the service on the real store spends a refresh token once under a 16-way race and rotates cleanly`() {
        val service = SessionService(store, AuthSessionProperties(), TimeProvider.systemUtc())
        val client = SessionClient("203.0.113.5", "UA", null)
        val opened = service.open("acc_1", client)
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val results = (1..16).map { pool.submit<Result<*>> { go.await(); runCatching { service.refresh(opened.refreshToken!!, client) } } }
        go.countDown()
        val outcomes = results.map { it.get() }
        pool.shutdown()
        assertEquals(1, outcomes.count { it.isSuccess })
        assertTrue(outcomes.filter { it.isFailure }.all { (it.exceptionOrNull() as ApplicationException).errorCode.code.startsWith("AUTH.REFRESH_") })
    }
}
