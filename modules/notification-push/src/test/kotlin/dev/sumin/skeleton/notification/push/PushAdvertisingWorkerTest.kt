package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.notification.advertising.AdvertisingConsent
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.notification.advertising.AdvertisingProperties
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 처리기가 광고 작업을 집을 때 다시 하는 판정 - 대기 중 철회가 광고를 내보내면 사고다 */
class PushAdvertisingWorkerTest {
    private val noon = Instant.parse("2026-10-31T03:00:00Z")      // 서울 12:00
    private val night = Instant.parse("2026-10-31T14:30:00Z")     // 서울 23:30
    private val clock = MutableTime(noon)
    private val devices = FakePushDeviceStore()
    private val mutes = FakePushTypeMuteStore()
    private val sender = FakePushSender()
    private val tasks = RecordingPushTasks()
    private val policy = FakeAdvertisingPolicy()
    private val properties = AdvertisingProperties(types = listOf("promo.tips"), fallbackZone = "Asia/Seoul", senderName = "Example Co.")
    private val worker = PushDeliveryWorker(devices, mutes, { listOf(sender) }, clock, AdvertisingGate(properties, policy, null, clock), tasks)

    private fun job(device: PushDevice, advertising: Boolean = true, type: String = "promo.tips") = PushJob(
        device.id, device.accountId,
        PushMessage("evt_1", type, "(광고) t", createdAt = noon, expiresAt = noon.plusSeconds(86_400)), advertising,
    )

    @Test
    fun `a job whose consent was withdrawn while it waited is not sent`() {
        val device = devices.add("acc_a")
        policy.overrides["acc_a"] = AdvertisingConsent(send = true, night = false)
        val queued = job(device)
        policy.overrides["acc_a"] = AdvertisingConsent(send = false, night = false)   // 대기 중 철회

        worker.process(queued)

        assertTrue(sender.sent.isEmpty())
        assertTrue(tasks.jobs.isEmpty())
    }

    @Test
    fun `a job with a lookup that throws, or a recipient the policy does not know, is not sent`() {
        val device = devices.add("acc_a")

        worker.process(job(device))                                  // 정책이 모르는 사람 = 동의 없음
        policy.overrides["acc_a"] = AdvertisingConsent(true, false)
        policy.failure = IllegalStateException("legal down")
        worker.process(job(device))

        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a job picked up while consent holds is sent`() {
        val device = devices.add("acc_a")
        policy.overrides["acc_a"] = AdvertisingConsent(send = true, night = false)

        worker.process(job(device))

        assertEquals(1, sender.sent.size)
        assertEquals(listOf(device.id), devices.successes)
    }

    @Test
    fun `a job whose type became advertising while it waited is sent with the advertising mark`() {
        val device = devices.add("acc_a")
        policy.overrides["acc_a"] = AdvertisingConsent(send = true, night = false)
        val plain = PushJob(device.id, device.accountId, PushMessage("evt_1", "promo.tips", "새 기능", createdAt = noon, expiresAt = noon.plusSeconds(86_400)))

        worker.process(plain)

        assertEquals("(광고) 새 기능", sender.sent.single().second.title)
    }

    @Test
    fun `a job picked up early at night without the night consent is held again instead of sent`() {
        val device = devices.add("acc_a")
        policy.overrides["acc_a"] = AdvertisingConsent(send = true, night = false)
        clock.now = night

        worker.process(job(device))

        assertTrue(sender.sent.isEmpty())
        val (held, runAt) = tasks.jobs.single()
        assertEquals(Instant.parse("2026-10-31T23:00:00Z"), runAt)
        assertEquals(device.id, held.deviceId)
        assertTrue(held.advertising)
    }

    @Test
    fun `a job that gained the night consent while it waited is sent at night`() {
        val device = devices.add("acc_a")
        policy.overrides["acc_a"] = AdvertisingConsent(send = true, night = true)
        clock.now = night

        worker.process(job(device))

        assertEquals(1, sender.sent.size)
    }

    @Test
    fun `a job of an advertising type is judged as advertising even if it says it is not`() {
        val device = devices.add("acc_a")

        worker.process(job(device, advertising = false))

        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `an advertising job in an app that has no advertising configuration is dropped`() {
        val device = devices.add("acc_a")
        val plain = PushDeliveryWorker(devices, mutes, { listOf(sender) }, clock)

        plain.process(job(device))

        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `informational jobs never ask the policy`() {
        val device = devices.add("acc_a")

        worker.process(job(device, advertising = false, type = "comment.created"))

        assertEquals(1, sender.sent.size)
        assertTrue(policy.calls.isEmpty())
    }
}
