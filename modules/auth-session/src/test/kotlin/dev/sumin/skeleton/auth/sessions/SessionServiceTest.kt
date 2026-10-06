package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.session.SessionEvent
import dev.sumin.skeleton.auth.session.SessionEventType
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionServiceTest {
    private class MutableTime(var at: Instant = Instant.parse("2026-10-06T00:00:00Z")) : TimeProvider {
        override fun now(): Instant = at
        fun advance(d: Duration) { at = at.plus(d) }
    }

    private val time = MutableTime()
    private val store = InMemorySessionStore()
    private val account = AuthAccount("acc_1", "ann", "ann@example.com", "", setOf("USER"))
    private val client = SessionClient(ip = "203.0.113.7", userAgent = "JUnit/1", deviceName = "Test laptop")
    private val events = CopyOnWriteArrayList<SessionEvent>()

    private fun service(props: AuthSessionProperties = AuthSessionProperties()) =
        SessionService(store, props, time) { events += it }

    private fun code(block: () -> Unit): String = assertFailsWith<ApplicationException> { block() }.errorCode.code

    @Test
    fun `open returns an opaque token and the store keeps only its hash`() {
        val opened = service().open(account.accountId, client)
        val token = requireNotNull(opened.refreshToken)
        assertTrue(token.startsWith("r1."))
        assertTrue(store.tokenHashes().none { token.contains(it) || it.contains(token) }, "the raw token must never be stored")
        assertEquals(1, store.tokenHashes().size)
        assertEquals(64, store.tokenHashes().single().length)
    }

    @Test
    fun `refresh rotates the token and the session id stays`() {
        val s = service()
        val first = s.open(account.accountId, client)
        val second = s.refresh(first.refreshToken!!, client)
        assertEquals(first.sessionId, second.session.sessionId)
        assertEquals(account.accountId, second.accountId)
        assertNotEquals(first.refreshToken, second.session.refreshToken)
        s.refresh(second.session.refreshToken!!, client)   // the new one works
    }

    @Test
    fun `replaying a rotated-away token is reuse and kills the whole family including the newest token`() {
        val s = service()
        val first = s.open(account.accountId, client)
        val second = s.refresh(first.refreshToken!!, client)

        assertEquals("AUTH.REFRESH_REUSED", code { s.refresh(first.refreshToken!!, client) })
        assertEquals("AUTH.REFRESH_INVALID", code { s.refresh(second.session.refreshToken!!, client) })
        val reuse = events.single { it.type == SessionEventType.REFRESH_REUSE_DETECTED }
        assertEquals(account.accountId, reuse.accountId)
        assertEquals(first.sessionId, reuse.sessionId)
        assertEquals("203.0.113.7", reuse.ip)
        assertTrue(s.list(account.accountId, null).isEmpty())
    }

    @Test
    fun `unknown garbage and wrong-version tokens are invalid, not reuse`() {
        val s = service()
        assertEquals("AUTH.REFRESH_INVALID", code { s.refresh("nope", client) })
        assertEquals("AUTH.REFRESH_INVALID", code { s.refresh("r1." + "A".repeat(43), client) })
    }

    @Test
    fun `sixteen threads presenting the same token produce exactly one winner`() {
        val s = service()
        val opened = s.open(account.accountId, client)
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val results = (1..16).map {
            pool.submit<Result<RefreshResult>> {
                start.await()
                runCatching { s.refresh(opened.refreshToken!!, client) }
            }
        }
        start.countDown()
        val outcomes = results.map { it.get() }
        pool.shutdown()
        assertEquals(1, outcomes.count { it.isSuccess }, "double spend: one refresh token must be spendable once")
        assertTrue(outcomes.filter { it.isFailure }.all { (it.exceptionOrNull() as ApplicationException).errorCode.code.startsWith("AUTH.REFRESH_") })
    }

    @Test
    fun `lost response - inside the grace the immediately previous token returns the SAME successor, so the client simply gets what it lost`() {
        val s = service(AuthSessionProperties(reuseGrace = Duration.ofSeconds(10)))
        val first = s.open(account.accountId, client)
        val lost = s.refresh(first.refreshToken!!, client)          // the browser navigated away: this response never arrived
        time.advance(Duration.ofSeconds(3))
        val retry = s.refresh(first.refreshToken!!, client)         // the next page presents the old token again
        assertEquals(lost.session.refreshToken, retry.session.refreshToken, "idempotent rotation: no second live token, no revoked family")
        assertEquals(1, store.tokenHashes().count { it != SessionService.hash(first.refreshToken!!) }, "exactly one successor exists")
        s.refresh(retry.session.refreshToken!!, client)             // and it works
        assertTrue(events.none { it.type == SessionEventType.REFRESH_REUSE_DETECTED })
    }

    @Test
    fun `theft - after the grace the same replay still kills the session, and a thief inside the grace gains nothing the victim lacks`() {
        val s = service(AuthSessionProperties(reuseGrace = Duration.ofSeconds(10)))
        val first = s.open(account.accountId, client)
        val victim = s.refresh(first.refreshToken!!, client)
        time.advance(Duration.ofSeconds(11))
        assertEquals("AUTH.REFRESH_REUSED", code { s.refresh(first.refreshToken!!, client) })
        assertEquals("AUTH.REFRESH_INVALID", code { s.refresh(victim.session.refreshToken!!, client) })
        assertTrue(events.any { it.type == SessionEventType.REFRESH_REUSE_DETECTED })
    }

    @Test
    fun `the successor cannot be derived from a stolen old token alone - it depends on a server key`() {
        val a = SessionService(InMemorySessionStore(), AuthSessionProperties(), time, "key-one-key-one-key-one-key-one!".toByteArray())
        val b = SessionService(InMemorySessionStore(), AuthSessionProperties(), time, "key-two-key-two-key-two-key-two!".toByteArray())
        assertTrue(a.successorOf("r1.same-old-token") != b.successorOf("r1.same-old-token"))
        assertEquals(a.successorOf("r1.same-old-token"), a.successorOf("r1.same-old-token"))
    }

    @Test
    fun `absolute lifetime and idle timeout both end a session`() {
        val s = service(AuthSessionProperties(absoluteTtl = Duration.ofDays(30), idleTtl = Duration.ofDays(7)))
        var token = s.open(account.accountId, client).refreshToken!!
        repeat(5) { time.advance(Duration.ofDays(5)); token = s.refresh(token, client).session.refreshToken!! }   // 25 days of use, never idle
        time.advance(Duration.ofDays(6))
        assertEquals("AUTH.REFRESH_INVALID", code { s.refresh(token, client) })   // past the absolute 30 days

        val idle = s.open(account.accountId, client).refreshToken!!
        time.advance(Duration.ofDays(8))
        assertEquals("AUTH.REFRESH_INVALID", code { s.refresh(idle, client) })
    }

    @Test
    fun `list shows device details and marks the current one, ordered newest first`() {
        val s = service()
        val a = s.open(account.accountId, client)
        time.advance(Duration.ofMinutes(1))
        val b = s.open(account.accountId, SessionClient("198.51.100.2", "Phone/2", null))
        val listed = s.list(account.accountId, b.sessionId)
        assertEquals(listOf(b.sessionId, a.sessionId), listed.map { it.id })
        assertEquals(listOf(true, false), listed.map { it.current })
        assertEquals("203.0.113.7", listed[1].ip)
        assertEquals("JUnit/1", listed[1].userAgent)
        assertEquals("Test laptop", listed[1].deviceName)
    }

    @Test
    fun `revoking someone else's session looks exactly like revoking a missing one`() {
        val s = service()
        val mine = s.open(account.accountId, client)
        val theirs = s.open("acc_2", client)
        assertEquals("AUTH.SESSION_NOT_FOUND", code { s.revoke(account.accountId, theirs.sessionId) })
        assertEquals("AUTH.SESSION_NOT_FOUND", code { s.revoke(account.accountId, "ses_missing") })
        s.revoke(account.accountId, mine.sessionId)
        assertEquals("AUTH.REFRESH_INVALID", code { s.refresh(mine.refreshToken!!, client) })
        s.refresh(theirs.refreshToken!!, client)   // untouched
    }

    @Test
    fun `revokeAll can keep the current session`() {
        val s = service()
        val keep = s.open(account.accountId, client)
        val other = s.open(account.accountId, client)
        s.revokeAll(account.accountId, keep.sessionId)
        s.refresh(keep.refreshToken!!, client)
        assertEquals("AUTH.REFRESH_INVALID", code { s.refresh(other.refreshToken!!, client) })
        s.revokeAll(account.accountId, null)
        assertTrue(s.list(account.accountId, null).isEmpty())
    }

    @Test
    fun `logout by token is idempotent and silent for garbage`() {
        val s = service()
        val opened = s.open(account.accountId, client)
        s.logout(opened.refreshToken!!)
        s.logout(opened.refreshToken!!)
        s.logout("garbage")
        assertEquals("AUTH.REFRESH_INVALID", code { s.refresh(opened.refreshToken!!, client) })
    }

    @Test
    fun `the oldest session is evicted when an account exceeds the per-account cap`() {
        val s = service(AuthSessionProperties(maxSessionsPerAccount = 2))
        val a = s.open(account.accountId, client); time.advance(Duration.ofMinutes(1))
        val b = s.open(account.accountId, client); time.advance(Duration.ofMinutes(1))
        val c = s.open(account.accountId, client)
        assertEquals(setOf(b.sessionId, c.sessionId), s.list(account.accountId, null).map { it.id }.toSet())
        assertFalse(s.list(account.accountId, null).any { it.id == a.sessionId })
    }

    @Test
    fun `old used tokens are forgotten after the reuse memory so the table does not grow forever`() {
        val s = service(AuthSessionProperties(reuseMemory = Duration.ofHours(1)))
        var token = s.open(account.accountId, client).refreshToken!!
        repeat(5) { time.advance(Duration.ofHours(2)); token = s.refresh(token, client).session.refreshToken!! }
        assertTrue(store.tokenHashes().size <= 2, "used tokens older than reuse-memory must be pruned, got ${store.tokenHashes().size}")
    }

    // ---- I5: bounded refresh-token rows

    private fun limited(max: Int, window: Duration = Duration.ofMinutes(10)): SessionService {
        val limits = dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore()
        val limiter = RotationLimiter { sessionId ->
            val d = limits.consume("rotate:$sessionId", max, window.toMillis(), time.now())
            if (d.allowed) null else (d.resetAt.epochSecond - time.now().epochSecond).coerceAtLeast(1)
        }
        return SessionService(store, AuthSessionProperties(), time, rotationLimiter = limiter) { events += it }
    }

    @Test
    fun `a session can rotate only so often - a refresh loop cannot grow the token table without bound, and the session stays valid`() {
        val s = limited(3)
        var current = s.open(account.accountId, client)
        repeat(3) { current = s.refresh(current.refreshToken!!, client).session }
        val e = assertFailsWith<RefreshRateLimitedException> { s.refresh(current.refreshToken!!, client) }
        assertEquals("AUTH.TOO_MANY_REFRESHES", e.errorCode.code)
        assertTrue(e.retryAfterSeconds in 1..600)
        repeat(50) { assertFailsWith<RefreshRateLimitedException> { s.refresh(current.refreshToken!!, client) } }
        assertEquals(4, store.tokenHashes().size, "one row per allowed rotation plus the first: 1 + 3")
        assertTrue(store.find(current.sessionId)!!.revokedAt == null, "rate limiting never signs the user out")

        time.advance(Duration.ofMinutes(11))
        current = s.refresh(current.refreshToken!!, client).session   // the same token works once the window has passed
        assertEquals(5, store.tokenHashes().size)
    }

    @Test
    fun `the rotation limit is per session and an unknown token spends nobody's allowance`() {
        val s = limited(1)
        val a = s.open(account.accountId, client)
        val b = s.open(account.accountId, client)
        repeat(20) { assertFailsWith<RefreshInvalidException> { s.refresh("r1.not-a-real-token-at-all-0000000000000000", client) } }
        s.refresh(a.refreshToken!!, client)
        s.refresh(b.refreshToken!!, client)   // b is not affected by a
        assertFailsWith<RefreshRateLimitedException> { s.refresh(s.successorOf(a.refreshToken!!), client) }
    }

    @Test
    fun `a thief who fills the rotation window cannot shield the session - the owner presenting the old (used) token is REFRESH_REUSED and the family dies`() {
        val s = limited(3)
        val owner = s.open(account.accountId, client)
        // the thief holds a stolen copy of the token and rotates until the window is full
        var thief = s.refresh(owner.refreshToken!!, client).session
        repeat(2) { thief = s.refresh(thief.refreshToken!!, client).session }
        assertFailsWith<RefreshRateLimitedException> { s.refresh(thief.refreshToken!!, client) }   // window full
        time.advance(Duration.ofMinutes(2))   // past the reuse grace, still inside the rotation window

        assertEquals("AUTH.REFRESH_REUSED", code { s.refresh(owner.refreshToken!!, client) }, "a used token goes to the reuse rule, never to the limiter")
        assertTrue(store.find(owner.sessionId)!!.revokedAt != null, "the family is revoked")
        assertTrue(events.any { it.type == SessionEventType.REFRESH_REUSE_DETECTED })
    }

    @Test
    fun `logging out with a rotated-out refresh token still closes the session - a client that lost the rotation race can sign out`() {
        val s = service()
        val first = s.open(account.accountId, client)
        val second = s.refresh(first.refreshToken!!, client).session
        s.logout(first.refreshToken!!)   // the previous, already used token
        assertTrue(store.find(first.sessionId)!!.revokedAt != null)
        assertFailsWith<RefreshInvalidException> { s.refresh(second.refreshToken!!, client) }
    }
}

class SessionReuseMemoryTest {
    private class MutableTime(var at: Instant = Instant.parse("2026-10-06T00:00:00Z")) : TimeProvider {
        override fun now(): Instant = at
        fun advance(d: Duration) { at = at.plus(d) }
    }

    private val time = MutableTime()
    private val store = InMemorySessionStore()
    private val client = SessionClient("203.0.113.7", "JUnit/1", null)
    private val events = CopyOnWriteArrayList<SessionEvent>()
    private val service = SessionService(store, AuthSessionProperties(), time) { events += it }

    @Test
    fun `a stolen token used first is still recognised as reuse when the victim comes back days later - the memory lasts as long as the session`() {
        val victimToken = service.open("acc_1", client).refreshToken!!
        val attacker1 = service.refresh(victimToken, client)                 // the thief goes first
        time.advance(Duration.ofHours(25))
        val attacker2 = service.refresh(attacker1.session.refreshToken!!, client)   // ... and keeps rotating, which used to prune the victim's token
        time.advance(Duration.ofDays(3))
        val ex = assertFailsWith<ApplicationException> { service.refresh(victimToken, client) }
        assertEquals("AUTH.REFRESH_REUSED", ex.errorCode.code, "the old row was forgotten after reuse-memory, so the theft left no trace")
        assertEquals("AUTH.REFRESH_INVALID", assertFailsWith<ApplicationException> { service.refresh(attacker2.session.refreshToken!!, client) }.errorCode.code, "the thief's session was closed")
        assertTrue(events.any { it.type == SessionEventType.REFRESH_REUSE_DETECTED })
    }

    @Test
    fun `revoking sessions reports them`() {
        val a = service.open("acc_1", client)
        service.open("acc_1", client)
        service.revoke("acc_1", a.sessionId)
        service.revokeAll("acc_1", null)
        assertEquals(listOf(SessionEventType.SESSION_REVOKED, SessionEventType.SESSIONS_REVOKED), events.map { it.type })
        assertEquals(1, events.last().count)
    }
}

class RefreshRequestToStringTest {
    @Test
    fun `the refresh token never prints`() {
        val text = dev.sumin.skeleton.auth.sessions.web.RefreshRequest("r1.SECRET-TOKEN-VALUE").toString()
        assertFalse("SECRET-TOKEN-VALUE" in text, text)
    }
}
