package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.DeliveryOutcome
import dev.sumin.skeleton.notification.NotificationDeliveryHooks
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushDeliveryWorkerTest {
    private val now = Instant.parse("2026-10-31T01:00:00Z")
    private val devices = FakePushDeviceStore()
    private val mutes = FakePushTypeMuteStore()
    private val sender = FakePushSender()
    private val worker = PushDeliveryWorker(devices, mutes, { listOf(sender) }, TimeProvider.fixed(now))

    private fun job(device: PushDevice, recipient: String = device.accountId, expiresAt: Instant? = now.plusSeconds(3600), type: String = "comment.created") =
        PushJob(device.id, recipient, PushMessage(eventId = "evt_1", type = type, title = "t", createdAt = now, expiresAt = expiresAt))

    @Test
    fun `a job whose device changed owner is not sent`() {
        val device = devices.add("acc_new")

        worker.process(job(device, recipient = "acc_old"))

        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `an expired job and a job for a type muted while it waited are not sent`() {
        val device = devices.add("acc_a")
        mutes.setMuted("acc_a", "comment.muted", true)

        worker.process(job(device, expiresAt = now))                       // 정확히 만료 시각
        worker.process(job(device, type = "comment.muted"))                // 큐에서 기다리는 동안 푸시만 끔

        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a missing device ends the job quietly`() {
        worker.process(PushJob("pd_gone", "acc_a", PushMessage("evt", "comment.created", "t", createdAt = now, expiresAt = now.plusSeconds(60))))

        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `Delivered records the success and keeps the device`() {
        val device = devices.add("acc_a")

        worker.process(job(device))

        assertEquals(listOf(device.id), devices.successes)
        assertEquals(1, devices.devices.size)
    }

    @Test
    fun `Gone deletes only that device row`() {
        val device = devices.add("acc_a")
        val other = devices.add("acc_a", "inst_other_0123456789")
        sender.result = PushResult.Gone

        worker.process(job(device))

        assertNull(devices.find(device.id))
        assertEquals(other, devices.find(other.id))
    }

    @Test
    fun `Retry is thrown for the queue and Failed keeps the row and is permanent`() {
        val device = devices.add("acc_a")

        sender.result = PushResult.Retry(Duration.ofSeconds(30))
        assertEquals(Duration.ofSeconds(30), assertFailsWith<PushRetryException> { worker.process(job(device)) }.after)

        sender.result = PushResult.Failed("unauthorized")
        assertFailsWith<PushPermanentFailure> { worker.process(job(device)) }
        assertEquals(1, devices.devices.size)
    }

    @Test
    fun `a halted event is dropped before the sender is reached, and the other outcomes are reported once each`() {
        val hook = RecordingDeliveryHook()
        val observed = PushDeliveryWorker(devices, mutes, { listOf(sender) }, TimeProvider.fixed(now), hooks = NotificationDeliveryHooks(hook))
        val device = devices.add("acc_a")

        hook.haltedIds += "evt_1"
        observed.process(job(device))                                   // 멈춘 공지: 큐에서 기다리던 푸시도 안 나간다
        assertTrue(sender.sent.isEmpty())
        hook.haltedIds.clear()

        observed.process(job(device))                                   // 전달
        observed.process(job(device, recipient = "acc_other"))          // 주인이 바뀜 -> 건너뜀
        sender.result = PushResult.Gone
        observed.process(job(device))                                   // 기기 사라짐 -> 실패
        observed.process(job(device))                                   // 지워진 기기 -> 건너뜀(보내지 않음)

        assertEquals(
            listOf(DeliveryOutcome.DROPPED, DeliveryOutcome.DELIVERED, DeliveryOutcome.DROPPED, DeliveryOutcome.FAILED, DeliveryOutcome.DROPPED),
            hook.outcomes.map { it.third },
        )
        assertTrue(hook.outcomes.all { it.first == "evt_1" && it.second == "push" })
    }
}
