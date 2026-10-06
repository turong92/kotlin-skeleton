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
    fun `revokeAll without an exception revokes every open session of the account and only that account`() {
        store.create(session("ses_a"), "a".repeat(64)); store.create(session("ses_b"), "b".repeat(64)); store.create(session("ses_c", account = "acc_2"), "c".repeat(64))
        assertEquals(2, store.revokeAll("acc_1", null, now, "ALL"))
        assertTrue(store.find("ses_a")!!.revokedAt != null && store.find("ses_b")!!.revokedAt != null)
        assertNull(store.find("ses_c")!!.revokedAt)
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
    fun `erasing an account deletes its sessions - open, revoked or expired - and their tokens, and only its own`() {
        store.create(session("ses_a"), "a".repeat(64)); store.create(session("ses_b"), "b".repeat(64)); store.create(session("ses_c", account = "acc_2"), "c".repeat(64))
        store.revoke("ses_b", now, "R")
        assertEquals(2, store.eraseAccount("acc_1"))
        assertNull(store.find("ses_a")); assertNull(store.find("ses_b"))
        assertNull(store.findToken("a".repeat(64)))
        assertTrue(store.find("ses_c") != null && store.findToken("c".repeat(64)) != null)
        assertEquals(0, store.eraseAccount("acc_1"), "erasure is idempotent")
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

    @Test
    fun `inside the reuse grace sixteen simultaneous refreshes of one token all get the SAME successor, the session survives, and one more after the grace is theft`() {
        val time = object : TimeProvider { var at = Instant.now(); override fun now() = at }
        val service = SessionService(store, AuthSessionProperties(reuseGrace = Duration.ofSeconds(10)), time, ByteArray(32) { 5 })
        val client = SessionClient("203.0.113.5", "UA", null)
        val opened = service.open("acc_1", client)
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val results = (1..16).map { pool.submit<Result<String>> { go.await(); runCatching { service.refresh(opened.refreshToken!!, client).session.refreshToken!! } } }
        go.countDown()
        val outcomes = results.map { it.get() }
        pool.shutdown()
        assertEquals(16, outcomes.count { it.isSuccess }, outcomes.filter { it.isFailure }.toString())
        assertEquals(1, outcomes.map { it.getOrThrow() }.toSet().size, "one successor - no fork of the token chain")
        assertTrue(store.find(opened.sessionId)!!.revokedAt == null)
        assertEquals(2, SessionDb.jdbc.queryForObject("select count(*) from skeleton_auth_refresh_tokens where session_id = :s", mapOf("s" to opened.sessionId), Int::class.java), "the first token and exactly one successor")

        time.at = time.at.plusSeconds(11)
        val e = kotlin.test.assertFailsWith<ApplicationException> { service.refresh(opened.refreshToken!!, client) }
        assertEquals("AUTH.REFRESH_REUSED", e.errorCode.code)
        assertTrue(store.find(opened.sessionId)!!.revokedAt != null, "after the grace a replay revokes the session")
    }
}
