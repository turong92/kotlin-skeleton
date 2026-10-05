package dev.sumin.skeleton.alert

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobStatus
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class TriggersTest {
    private class Emitted(val kind: AlertKind, val key: String, val detail: String, val immediate: Boolean)

    private class Capturing : OwnerAlerts {
        val emitted = CopyOnWriteArrayList<Emitted>()
        override fun emit(kind: AlertKind, key: String, detail: String, immediate: Boolean) { emitted += Emitted(kind, key, detail, immediate) }
    }

    private class Clock(var now: Instant = Instant.parse("2026-10-06T00:00:00Z")) : TimeProvider { override fun now() = now }

    private val alerts = Capturing()
    private val clock = Clock()
    private fun surge(threshold: Int = 3, window: Duration = Duration.ofMinutes(1)) =
        ServerErrorSurge(alerts, AlertProperties.Surge(threshold = threshold, window = window), clock, handoff = { it.run() })

    @Test
    fun `5xx at the threshold inside the window raises one immediate alert and restarts the count`() {
        val surge = surge()
        repeat(2) { surge.record() }
        assertTrue(alerts.emitted.isEmpty())
        surge.record()

        val e = alerts.emitted.single()
        assertEquals(BuiltInAlertKind.SERVER_ERROR_SURGE, e.kind)
        assertTrue(e.immediate)
        repeat(2) { surge.record() }
        assertEquals(1, alerts.emitted.size, "the window was cleared, it has to build up again")
    }

    @Test
    fun `5xx older than the window do not count`() {
        val surge = surge()
        repeat(2) { surge.record() }
        clock.now = clock.now.plusSeconds(61)
        surge.record()
        assertTrue(alerts.emitted.isEmpty())
    }

    @Test
    fun `the filter counts 5xx responses and thrown exceptions, and rethrows`() {
        val filter = ServerErrorSurgeFilter(surge(threshold = 2))
        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse()) { _, res -> (res as MockHttpServletResponse).status = 503 }
        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse()) { _, res -> (res as MockHttpServletResponse).status = 404 }
        assertTrue(alerts.emitted.isEmpty())

        assertFailsWith<IllegalStateException> {
            filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse()) { _, _ -> throw IllegalStateException("boom") }
        }
        assertEquals(1, alerts.emitted.size)
    }

    @Test
    fun `the filter never fails the request because counting failed`() {
        val broken = object : OwnerAlerts { override fun emit(kind: AlertKind, key: String, detail: String, immediate: Boolean) = error("down") }
        val filter = ServerErrorSurgeFilter(ServerErrorSurge(broken, AlertProperties.Surge(threshold = 1), clock, handoff = { it.run() }))
        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse()) { _, res -> (res as MockHttpServletResponse).status = 500 }
    }

    private fun job(type: String = "send-mail") = Job(
        id = 7, type = type, payloadJson = "{}", status = JobStatus.DEAD, attempts = 5, maxAttempts = 5,
        nextRunAt = clock.now, lockedBy = null, lockedAt = null, lastError = null, createdAt = clock.now, updatedAt = clock.now,
    )

    @Test
    fun `a dead job raises JOB_DEAD keyed by its type with only the exception class, not the message`() {
        AlertDeadJobListener(alerts).onDead(job(), "com.acme.MailException: could not send to secret@example.com token=abc")

        val e = alerts.emitted.single()
        assertEquals(BuiltInAlertKind.JOB_DEAD, e.kind)
        assertEquals("send-mail", e.key)
        assertTrue(e.immediate)
        assertTrue(e.detail.contains("MailException"), e.detail)
        assertFalse(e.detail.contains("secret@example.com") || e.detail.contains("token=abc"), e.detail)
    }
}
