package dev.sumin.skeleton.notification.legal

import dev.sumin.skeleton.notification.advertising.AdvertisingNotificationTypes
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AdvertisingNoticeDeliveryTest {
    private val both = setOf(AdvertisingNotificationTypes.CONSENT_CHANGED, AdvertisingNotificationTypes.CONSENT_RECONFIRM)

    @Test
    fun `push alone carries the notices only when both types are in push types`() {
        assertNull(noticeMediumProblem(pushDeclared = true, pushTypes = both, mailReady = false))
        assertNotNull(noticeMediumProblem(pushDeclared = true, pushTypes = emptySet(), mailReady = false))
        assertNotNull(noticeMediumProblem(pushDeclared = true, pushTypes = setOf(AdvertisingNotificationTypes.CONSENT_CHANGED), mailReady = false))
        assertNotNull(noticeMediumProblem(pushDeclared = false, pushTypes = both, mailReady = false))
    }

    @Test
    fun `mail carries the notices only when the mailer is ready, not merely present`() {
        assertNull(noticeMediumProblem(pushDeclared = false, pushTypes = emptySet(), mailReady = true))
        assertNotNull(noticeMediumProblem(pushDeclared = false, pushTypes = emptySet(), mailReady = false))
    }

    private val clock = MutableClock(java.time.Instant.parse("2026-11-16T00:00:00Z"))

    @Test
    fun `a wake-up scans only when the scan interval has passed, while the timer always scans`() {
        var scans = 0
        val runner = AdvertisingReconfirmRunner({ scans++ }, { null }, Duration.ofHours(6), clock)

        runner.runIfDue()
        runner.runIfDue()
        clock.now = clock.now.plus(Duration.ofHours(5))
        runner.runIfDue()
        assertEquals(1, scans)

        clock.now = clock.now.plus(Duration.ofHours(2))
        runner.runIfDue()
        assertEquals(2, scans)

        runner.run()
        assertEquals(3, scans)
        runner.runIfDue()
        assertEquals(3, scans, "a timer scan also counts as the last scan")
    }

    @Test
    fun `a failing scan is swallowed and the next due wake-up tries again`() {
        var scans = 0
        val runner = AdvertisingReconfirmRunner({ scans++; error("db down") }, { null }, Duration.ofHours(6), clock)

        runner.runIfDue()
        clock.now = clock.now.plus(Duration.ofHours(7))
        runner.runIfDue()

        assertEquals(2, scans)
    }
}
