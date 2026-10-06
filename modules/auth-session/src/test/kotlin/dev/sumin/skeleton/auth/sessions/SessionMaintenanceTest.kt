package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** I7 — 끝난 세션은 주기로 청소되고, 지워진 계정의 세션(IP · UA)은 지워진다 */
class SessionMaintenanceTest {
    private class MutableTime(var at: Instant = Instant.parse("2026-10-06T00:00:00Z")) : TimeProvider {
        override fun now(): Instant = at
        fun advance(d: Duration) { at = at.plus(d) }
    }

    private val time = MutableTime()
    private val store = InMemorySessionStore()
    private val service = SessionService(store, AuthSessionProperties(), time)
    private val client = SessionClient("203.0.113.7", "UA", null)

    @Test
    fun `the purge run deletes sessions that ended longer ago than the retention and keeps live and recent ones`() {
        val dead = service.open("acc_1", client)
        service.revoke("acc_1", dead.sessionId)
        val live = service.open("acc_1", client)
        time.advance(Duration.ofDays(8))
        val recent = service.open("acc_2", client)
        service.revoke("acc_2", recent.sessionId)

        val purge = SessionPurge(store, time, AuthSessionProperties.Purge(retention = Duration.ofDays(7)))
        assertEquals(1, purge.runOnce())
        assertNull(store.find(dead.sessionId))
        assertNotNull(store.find(live.sessionId))
        assertNotNull(store.find(recent.sessionId))
    }

    @Test
    fun `erasing an account removes its sessions and tokens and leaves the others`() {
        val mine = service.open("acc_1", client)
        val theirs = service.open("acc_2", client)
        val listener: AccountErasureListener = SessionErasureListener(store)
        listener.erase(ErasureRequest("acc_1", "deleted:x"))
        assertNull(store.find(mine.sessionId))
        assertTrue(store.tokenHashes().size == 1)
        assertNotNull(store.find(theirs.sessionId))
        listener.erase(ErasureRequest("acc_1", "deleted:x"))   // idempotent
    }

    @Test
    fun `the auto-configuration schedules the purge and registers the erasure listener`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthSessionAutoConfiguration::class.java))
            .withPropertyValues("skeleton.auth-session.purge.interval=PT1H")
            .run { ctx ->
                assertNotNull(ctx.getBean("authSessionPurgeScheduler"))
                assertTrue(ctx.getBean("sessionErasureListener") is AccountErasureListener)
            }
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthSessionAutoConfiguration::class.java))
            .withPropertyValues("skeleton.auth-session.purge.interval=0s")
            .run { ctx -> assertTrue(ctx.getBeansOfType(SessionPurgeScheduler::class.java).values.all { !it.running }, "interval 0 switches the schedule off") }
    }
}
