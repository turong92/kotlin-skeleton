package dev.sumin.skeleton.notification.scheduled

import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.advertising.AdvertisingConsent
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.notification.advertising.AdvertisingProperties
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScheduledPushSweepTest {
    private var now = T0
    private val clock = TimeProvider { now }
    private val store = FakeScheduledPushStore()
    private val publisher = RecordingPublisher()

    /** 광고 종류 `reminder.ad` · 동의 상태는 [consents] 에서(사람 → 광고 동의, 야간 동의) */
    private var consents = mapOf("acc_ad" to AdvertisingConsent(send = true, night = true))
    private val gate = AdvertisingGate(
        AdvertisingProperties(types = listOf("reminder.ad"), fallbackZone = "Asia/Seoul", senderName = "Acme"),
        { id, _ -> consents[id] ?: AdvertisingConsent(send = false, night = false) },
        null,
        clock,
    )

    private fun properties(limit: Int? = null) = ScheduledPushProperties(enabled = true, type = "reminder", advertisingType = "reminder.ad", dailyLimit = limit)
    private fun sweep(limit: Int? = null) = ScheduledPushSweep(store, publisher, gate, properties(limit), clock)
    private fun pushes(limit: Int? = null) = ScheduledPushes(store, properties(limit), clock)

    private fun push(key: String, at: Instant = T0, to: String = "acc_1", ad: Boolean = false, title: String = "다음 화가 기다려요") =
        ScheduledPush(key, to, at, title, "본문", "/series/1", ad)

    @Test
    fun `the same key replaces the pending reservation instead of creating another, and a sent key stays one`() {
        val pushes = pushes()
        assertEquals(ScheduleResult.CREATED, pushes.schedule(push("k1", at = T0.plusSeconds(60), title = "처음")))
        assertEquals(ScheduleResult.REPLACED, pushes.schedule(push("k1", at = T0.plusSeconds(120), title = "바뀐 제목")))
        assertEquals(1, store.rows.size)
        assertEquals("바뀐 제목", store.rows.getValue("k1").push.title)

        now = T0.plusSeconds(200)
        sweep().run()
        assertEquals(listOf("바뀐 제목"), publisher.events.map { it.title })
        assertEquals(ScheduleResult.IGNORED, pushes.schedule(push("k1", title = "또 같은 계기")))   // 이미 나간 키는 한 통만
        sweep().run()
        assertEquals(1, publisher.events.size)
    }

    @Test
    fun `a finished key is ignored inside the dedupe window and becomes a new reservation after it`() {
        pushes().schedule(push("k1"))
        sweep().run()
        assertEquals(ScheduleResult.IGNORED, pushes().schedule(push("k1")))
        now = now.plus(properties().dedupeWindow).plusSeconds(1)
        assertEquals(ScheduleResult.CREATED, pushes().schedule(push("k1", at = now)))
        sweep().run()
        assertEquals(2, publisher.events.size)
    }

    @Test
    fun `a reservation that keeps failing is given up after max attempts instead of retrying forever`() {
        pushes().schedule(push("k1"))
        repeat(properties().maxAttempts + 1) {
            publisher.failNext = true
            sweep().run()
            now = now.plus(properties().lease).plusSeconds(1)
        }
        sweep().run()
        assertEquals(0, publisher.events.size)
        assertEquals(SkipReason.TOO_MANY_ATTEMPTS, store.rows.getValue("k1").reason)
    }

    @Test
    fun `a sweeper that lost its lease cannot close or move the row a newer owner holds`() {
        pushes().schedule(push("k1"))
        val stale = store.claimDue(now, properties().lease, 10).single()
        now = now.plus(properties().lease).plusSeconds(1)
        val fresh = store.claimDue(now, properties().lease, 10).single()
        store.finish("k1", stale.claimToken, ScheduledPushStatus.SENT, null, now)   // 옛 집기 - 아무 일도 없다
        store.reschedule("k1", stale.claimToken, now.plusSeconds(999), now)
        assertEquals(ScheduledPushStatus.PENDING, store.rows.getValue("k1").status)
        store.finish("k1", fresh.claimToken, ScheduledPushStatus.SENT, null, now)
        assertEquals(ScheduledPushStatus.SENT, store.rows.getValue("k1").status)
    }

    @Test
    fun `cancel removes a pending reservation by key so it never goes out`() {
        pushes().schedule(push("k1"))
        assertTrue(pushes().cancel("k1"))
        assertEquals(false, pushes().cancel("k1"))
        sweep().run()
        assertEquals(0, publisher.events.size)
    }

    @Test
    fun `only reservations that are due go out, to their one recipient, over push`() {
        pushes().schedule(push("due", to = "acc_1"))
        pushes().schedule(push("later", at = T0.plusSeconds(3600), to = "acc_2"))
        assertEquals(1, sweep().run())
        val event = publisher.events.single()
        assertEquals(setOf("acc_1"), event.recipientIds)
        assertEquals("reminder", event.type)
        assertEquals(setOf("push"), event.channels)
        assertEquals("/series/1", event.link)
    }

    @Test
    fun `overlapping sweeps on many instances send each reservation exactly once`() {
        repeat(20) { pushes().schedule(push("k$it", to = "acc_$it")) }
        val pool = Executors.newFixedThreadPool(6)
        try {
            (1..6).map { pool.submit { sweep().run() } }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(20, publisher.events.size)
        assertEquals(20, publisher.events.map { it.id }.distinct().size)
    }

    @Test
    fun `a failed send is retried only after the lease and keeps the same event id so the inbox merges a repeat`() {
        pushes().schedule(push("k1"))
        publisher.failNext = true
        sweep().run()
        assertEquals(0, publisher.events.size)
        sweep().run()   // 아직 lease 안 - 다른 훑기가 집지 못한다
        assertEquals(0, publisher.events.size)
        now = now.plus(properties().lease).plusSeconds(1)
        sweep().run()
        assertEquals(1, publisher.events.size)
        assertTrue(publisher.events.single().id.startsWith("sp_"))
    }

    @Test
    fun `advertising without consent is skipped and never published, informational ignores consent`() {
        pushes().schedule(push("ad", to = "acc_none", ad = true))
        pushes().schedule(push("info", to = "acc_none"))
        sweep().run()
        assertEquals(listOf("reminder"), publisher.events.map { it.type })
        assertEquals(SkipReason.NO_CONSENT, store.rows.getValue("ad").reason)
        assertEquals(ScheduledPushStatus.SKIPPED, store.rows.getValue("ad").status)
    }

    @Test
    fun `advertising with consent is branded with the advertising mark and sent as the advertising type`() {
        pushes().schedule(push("ad", to = "acc_ad", ad = true))
        sweep().run()
        val event = publisher.events.single()
        assertEquals("reminder.ad", event.type)
        assertTrue(event.title!!.startsWith("(광고)"))
    }

    @Test
    fun `at night without night consent advertising waits for the next 8am instead of going out`() {
        consents = mapOf("acc_ad" to AdvertisingConsent(send = true, night = false))
        now = Instant.parse("2026-11-01T15:30:00Z")   // 00:30 KST
        pushes().schedule(push("ad", at = now, to = "acc_ad", ad = true))
        sweep().run()
        assertEquals(0, publisher.events.size)
        assertEquals(Instant.parse("2026-11-01T23:00:00Z"), store.rows.getValue("ad").push.runAt)   // 08:00 KST
        assertEquals(ScheduledPushStatus.PENDING, store.rows.getValue("ad").status)

        now = Instant.parse("2026-11-01T23:00:01Z")
        sweep().run()
        assertEquals(1, publisher.events.size)
    }

    @Test
    fun `the daily limit skips the rest for that person only`() {
        repeat(3) { pushes(limit = 2).schedule(push("a$it", to = "acc_1", at = T0.plusSeconds(it.toLong()))) }
        pushes(limit = 2).schedule(push("b0", to = "acc_2"))
        now = T0.plusSeconds(10)
        sweep(limit = 2).run()
        assertEquals(listOf("acc_1", "acc_1", "acc_2"), publisher.events.flatMap { it.recipientIds }.sorted())
        assertEquals(SkipReason.DAILY_LIMIT, store.rows.getValue("a2").reason)

        pushes(limit = 2).schedule(push("a3", to = "acc_1", at = now.plus(Duration.ofHours(25))))
        now = now.plus(Duration.ofHours(25)).plusSeconds(1)
        sweep(limit = 2).run()
        assertEquals(4, publisher.events.size)   // 24시간이 지나 다시 받는다
    }

    @Test
    fun `account erasure deletes that person's reservations`() {
        pushes().schedule(push("k1", to = "acc_gone"))
        pushes().schedule(push("k2", to = "acc_keep"))
        ScheduledPushErasureListener(store).erase(ErasureRequest("acc_gone", "deleted:x"))
        assertEquals(setOf("k2"), store.rows.keys)
        sweep().run()
        assertEquals(setOf("acc_keep"), publisher.events.flatMap { it.recipientIds }.toSet())
    }

    @Test
    fun `an advertising reservation is refused when no advertising type is configured`() {
        val noAd = ScheduledPushes(store, ScheduledPushProperties(enabled = true, type = "reminder"), clock)
        assertFailsWith<IllegalArgumentException> { noAd.schedule(push("ad", ad = true)) }
    }
}
