package dev.sumin.skeleton.ads.webrewarded

import dev.sumin.skeleton.adswebtest.FakeWebRewardStore
import dev.sumin.skeleton.adswebtest.MutableClock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 보관 기한 — 기본은 안 지운다, 너무 짧으면 기동 실패(오늘 창의 카운터를 지우면 상한이 샌다), 켜면 기한 지난 줄만 멱등하게 지운다 */
class WebRewardRetentionTest {
    @Test
    fun `retention is off by default and anything under two days refuses to start`() {
        assertEquals(null, WebRewardedProperties().retention)
        assertFailsWith<IllegalArgumentException> { WebRewardedProperties(retention = Duration.ofDays(1)) }
        WebRewardedProperties(retention = Duration.ofDays(2))
    }

    @Test
    fun `purge loops in batches up to the cutoff and a second run deletes nothing`() {
        val clock = MutableClock(Instant.parse("2026-11-18T03:00:00Z"))
        val store = FakeWebRewardStore()
        val old = clock.now.minus(Duration.ofDays(40))
        repeat(5) { store.insert(WebRewardSession("wrs_old$it", "a1", "p", "ip", old, old.plusSeconds(900), null, null, null, null, null)) }
        store.insert(WebRewardSession("wrs_new", "a1", "p", "ip", clock.now, clock.now.plusSeconds(900), null, null, null, null, null))
        val retention = WebRewardRetention(store, Duration.ofDays(30), clock, batch = 2)

        assertEquals(5, retention.purge())
        assertEquals(setOf("wrs_new"), store.sessions.keys)
        assertEquals(0, retention.purge())
    }
}
