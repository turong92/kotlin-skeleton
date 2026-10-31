package dev.sumin.skeleton.notification.push.legal

import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.notification.advertising.AdvertisingNotificationTypes
import dev.sumin.skeleton.notification.advertising.AdvertisingProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdvertisingNoticesTest {
    private val kit = LegalKit()
    private val publisher = RecordingPublisher()
    private val advertising = AdvertisingProperties(types = listOf("promo.tips"), fallbackZone = "Asia/Seoul", senderName = "Example Co.")
    private val gate = AdvertisingGate(advertising, null, null, kit.clock)
    private val t0 = kit.clock.now

    private fun hook() {
        kit.listeners += AdvertisingConsentNotifier(publisher, advertising, gate)
    }

    @Test
    fun `agreeing, reconfirming and withdrawing each send one informational notice to that person with the sender and the time`() {
        hook()

        kit.agree("acc_a")
        kit.agree("acc_a", source = "reconfirm")
        kit.withdraw("acc_a")

        assertEquals(3, publisher.events.size)
        assertTrue(publisher.events.all { it.type == AdvertisingNotificationTypes.CONSENT_CHANGED && it.recipientIds == setOf("acc_a") })
        val messages = publisher.events.map { it.message!! }
        assertTrue(messages.all { "Example Co." in it && "2026-10-07 09:00" in it }, messages.toString())   // 서울 시각
        assertEquals(listOf("AGREED", "AGREED", "WITHDRAWN"), publisher.events.map { it.payload["action"] })
        assertEquals(listOf("push-settings", "reconfirm", "withdraw"), publisher.events.map { it.payload["source"] })
        assertEquals("/settings/notifications", publisher.events.first().link)
    }

    @Test
    fun `the night consent is announced as its own scope and other documents are not announced`() {
        hook()

        kit.agree("acc_a", "marketing-night")

        assertEquals("night", publisher.events.single().payload["scope"])
        assertTrue("야간" in publisher.events.single().message!!)
    }

    @Test
    fun `a repeated identical agreement changes nothing and so announces nothing`() {
        hook()

        kit.agree("acc_a")
        kit.agree("acc_a")

        assertEquals(1, publisher.events.size)
    }

    @Test
    fun `a publisher that throws loses neither the consent nor the next announcement`() {
        hook()
        publisher.failure = IllegalStateException("broker down")

        kit.agree("acc_a")
        publisher.failure = null
        kit.withdraw("acc_a")

        assertEquals(2, (kit.store as MemoryConsentStore).rows.size)
        assertEquals(1, publisher.events.size)
    }

    // ---- 재확인 스캔

    private fun scanner(notices: MemoryNoticeStore = MemoryNoticeStore(), pageSize: Int = 500) =
        AdvertisingReconfirmScanner(kit.store, notices, publisher, gate, advertising, kit.clock, pageSize) to notices

    private val twoYears: Duration = Duration.ofDays(730)

    @Test
    fun `a person whose agreement is close to its end gets one reminder per agreement cycle, also when the scan runs again`() {
        kit.agree("acc_a")
        val (scanner, notices) = scanner()

        kit.clock.now = t0.plus(twoYears).minus(Duration.ofDays(31))
        assertEquals(0, scanner.run())
        kit.clock.now = t0.plus(twoYears).minus(Duration.ofDays(29))
        assertEquals(1, scanner.run())
        assertEquals(0, scanner.run())
        kit.clock.now = t0.plus(twoYears).plus(Duration.ofDays(3))
        assertEquals(0, scanner.run(), "an expired agreement was already reminded in this cycle")

        val reminder = publisher.events.single()
        assertEquals(AdvertisingNotificationTypes.CONSENT_RECONFIRM, reminder.type)
        assertEquals(setOf("acc_a"), reminder.recipientIds)
        assertEquals("/settings/notifications", reminder.link)
        assertTrue("Example Co." in reminder.message!!)
        assertTrue("2028-10-06" in reminder.message!!, reminder.message)   // 만료일 (2026-10-07 + 730 일)
        assertEquals(1, notices.marks.size)
    }

    @Test
    fun `a reconfirm opens a new cycle with a new reminder`() {
        kit.agree("acc_a")
        val (scanner, _) = scanner()
        kit.clock.now = t0.plus(twoYears).minus(Duration.ofDays(20))
        scanner.run()
        kit.agree("acc_a", source = "reconfirm")
        kit.clock.now = kit.clock.now.plus(twoYears).minus(Duration.ofDays(20))

        assertEquals(1, scanner.run())
        assertEquals(2, publisher.events.size)
        assertTrue(publisher.events[0].id != publisher.events[1].id)
    }

    @Test
    fun `withdrawn people are not reminded and both documents are looked at`() {
        kit.agree("acc_a")
        kit.withdraw("acc_a")
        kit.agree("acc_b")
        kit.agree("acc_b", "marketing-night")
        val (scanner, notices) = scanner()
        kit.clock.now = t0.plus(twoYears).minus(Duration.ofDays(10))

        assertEquals(2, scanner.run())

        assertEquals(setOf("marketing", "marketing-night"), notices.marks.map { it.second }.toSet())
        assertEquals(setOf("acc_b"), publisher.events.flatMap { it.recipientIds }.toSet())
    }

    @Test
    fun `the reminder goes to people without an effective agreement too, and a long list is read page by page`() {
        (1..7).forEach { kit.agree("acc_$it") }
        val (scanner, _) = scanner(pageSize = 3)
        kit.clock.now = t0.plus(twoYears).plus(Duration.ofDays(100))   // 모두 이미 만료

        assertEquals(7, scanner.run())
        assertEquals(7, publisher.events.map { it.recipientIds.single() }.toSet().size)
    }

    @Test
    fun `the id of a reminder is the same when the same cycle is announced again, so the inbox folds a double`() {
        kit.agree("acc_a")
        kit.clock.now = t0.plus(twoYears).minus(Duration.ofDays(10))
        val (first, _) = scanner()
        val (second, _) = scanner()   // 표시를 잃은 쪽(발행 뒤 표시 전에 죽은 경우)

        first.run()
        second.run()

        assertEquals(2, publisher.events.size)
        assertEquals(publisher.events[0].id, publisher.events[1].id)
    }

    @Test
    fun `a failing publish does not mark the cycle, so the next scan tries again`() {
        kit.agree("acc_a")
        kit.clock.now = t0.plus(twoYears).minus(Duration.ofDays(10))
        val (scanner, notices) = scanner()
        publisher.failure = IllegalStateException("broker down")

        assertEquals(0, scanner.run())
        assertEquals(0, notices.marks.size)
        publisher.failure = null
        assertEquals(1, scanner.run())
    }
}
