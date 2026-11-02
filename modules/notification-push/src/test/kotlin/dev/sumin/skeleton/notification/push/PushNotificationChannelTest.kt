package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.InMemoryNotificationBroker
import dev.sumin.skeleton.notification.InMemoryNotificationInboxRepository
import dev.sumin.skeleton.notification.InMemoryNotificationMuteRepository
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationInboxQuery
import dev.sumin.skeleton.notification.NotificationSeverity
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.notification.advertising.AdvertisingProperties
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushNotificationChannelTest {
    private val now = Instant.parse("2026-10-31T01:00:00Z")
    private val inbox = InMemoryNotificationInboxRepository()
    private val broker = InMemoryNotificationBroker(inbox, muteRepository = InMemoryNotificationMuteRepository())
    private val devices = FakePushDeviceStore()
    private val mutes = FakePushTypeMuteStore()
    private val tasks = RecordingPushTasks()
    private val webSender = FakePushSender(PushKinds.WEBPUSH)
    private val properties = PushProperties(enabled = true, types = listOf("comment.created"))

    private fun channel(
        props: PushProperties = properties,
        senders: List<PushSender> = listOf(webSender),
    ) = PushNotificationChannel(broker, props, DefaultPushMessageFactory(props, AdvertisingGate(AdvertisingProperties(), null, null, TimeProvider.systemUtc())), devices, mutes, tasks, { senders })

    private fun event(
        type: String = "comment.created",
        recipients: Set<String> = setOf("acc_a"),
        title: String? = "새 댓글",
        message: String? = "홍길동님이 댓글을 남겼어요",
    ) = NotificationEvent(
        topic = "comments", type = type, recipientIds = recipients, title = title, message = message,
        link = "/notes/1", createdAt = now, severity = NotificationSeverity.INFO,
    )

    @Test
    fun `one job per device of the recipients and none for other accounts`() {
        devices.add("acc_a", "inst_a1_0123456789")
        devices.add("acc_a", "inst_a2_0123456789")
        devices.add("acc_other", "inst_o1_0123456789")
        channel().use {
            broker.publish(event())
        }

        assertEquals(2, tasks.jobs.size)
        assertTrue(tasks.jobs.all { (job, _) -> job.recipientId == "acc_a" })
        assertEquals("새 댓글", tasks.jobs[0].first.message.title)
        assertEquals(now.plus(DEFAULT_TTL), tasks.jobs[0].first.message.expiresAt)
    }

    @Test
    fun `a broadcast, an untracked type and a push-muted recipient create no job while the inbox keeps the notification`() {
        devices.add("acc_a", "inst_a1_0123456789")
        devices.add("acc_b", "inst_b1_0123456789")
        mutes.setMuted("acc_a", "comment.created", true)
        channel().use {
            broker.publish(event(recipients = emptySet()))                       // 전체 공지
            broker.publish(event(type = "promo.something"))                       // push.types 밖
            broker.publish(event(recipients = setOf("acc_a", "acc_b")))           // a 는 푸시만 끔
        }

        assertEquals(listOf("acc_b"), tasks.jobs.map { it.first.recipientId })
        assertEquals(1, inbox.findByRecipient("acc_a", NotificationInboxQuery()).values.count { it.event.type == "comment.created" })
    }

    @Test
    fun `a failing queue does not drop the channel from the broker`() {
        devices.add("acc_a", "inst_a1_0123456789")
        channel().use {
            tasks.failure = IllegalStateException("queue down")
            broker.publish(event())
            tasks.failure = null
            broker.publish(event())
        }

        assertEquals(1, tasks.jobs.size)   // 두 번째 알림은 다시 푸시된다 — 브로커는 던진 구독자를 닫는다
    }

    @Test
    fun `a device subscribed with another server key and a kind without a sender get nothing`() {
        val rotated = FakePushSender(PushKinds.WEBPUSH, publicKey = "server-key-2")
        devices.add("acc_a", "inst_a1_0123456789", keyId = PushHashes.keyId("server-key-1"))
        val current = devices.add("acc_a", "inst_a2_0123456789", keyId = PushHashes.keyId("server-key-2"))
        devices.add("acc_a", "inst_a3_0123456789", kind = PushKinds.FCM)
        channel(senders = listOf(rotated)).use {
            broker.publish(event())
        }

        assertEquals(listOf(current.id), tasks.jobs.map { it.first.deviceId })
    }

    @Test
    fun `HIDDEN keeps the original title and body out of the job`() {
        devices.add("acc_a", "inst_a1_0123456789")
        val hidden = properties.copy(lockScreen = LockScreen.HIDDEN)
        channel(props = hidden).use {
            broker.publish(event(title = "비밀 제목", message = "비밀 본문"))
        }

        val message = tasks.jobs.single().first.message
        assertEquals("새 알림이 있습니다", message.title)
        assertEquals(null, message.body)
        assertEquals("/notes/1", message.link)
        assertFalse(message.toString().contains("비밀"))
    }
}
