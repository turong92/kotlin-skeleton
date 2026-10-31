package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.notification.InMemoryNotificationBroker
import dev.sumin.skeleton.notification.InMemoryNotificationInboxRepository
import dev.sumin.skeleton.notification.InMemoryNotificationMuteRepository
import dev.sumin.skeleton.notification.NotificationEvent
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 광고 종류의 발송 규칙 - 동의 우회가 없어야 한다(미동의 · 철회 · 조회 실패 → 작업 없음, 야간 → 보류, 표기는 앱 factory 가 잊어도 붙는다) */
class PushAdvertisingChannelTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val noon = Instant.parse("2026-10-31T03:00:00Z")      // 서울 12:00
    private val night = Instant.parse("2026-10-31T14:30:00Z")     // 서울 23:30
    private val clock = MutableTime(noon)
    private val broker = InMemoryNotificationBroker(InMemoryNotificationInboxRepository(), muteRepository = InMemoryNotificationMuteRepository())
    private val devices = FakePushDeviceStore()
    private val mutes = FakePushTypeMuteStore()
    private val tasks = RecordingPushTasks()
    private val web = FakePushSender(PushKinds.WEBPUSH)
    private val fcm = FakePushSender(PushKinds.FCM)
    private val policy = FakeAdvertisingPolicy(
        mapOf(
            "acc_ok" to AdvertisingConsent(send = true, night = false),
            "acc_night" to AdvertisingConsent(send = true, night = true),
            "acc_withdrawn" to AdvertisingConsent(send = false, night = false),
        ),
    )
    private val properties = PushProperties(
        enabled = true, types = listOf("comment.created"), advertisingTypes = listOf("promo.tips"),
        advertising = PushProperties.Advertising(fallbackZone = "Asia/Seoul", senderName = "Example Co."),
    )

    private fun channel(
        props: PushProperties = properties,
        factory: PushMessageFactory = DefaultPushMessageFactory(props),
        gatePolicy: AdvertisingConsentPolicy? = policy,
    ) = PushNotificationChannel(
        broker, props, factory, devices, mutes, tasks, { listOf(web, fcm) },
        AdvertisingGate(props, gatePolicy, null, clock),
    )

    private fun ad(vararg recipients: String, type: String = "promo.tips", title: String? = "신기능 안내") = NotificationEvent(
        topic = "promo", type = type, recipientIds = recipients.toSet(), title = title, message = "새 기능이 나왔어요", link = "/news", createdAt = clock.now,
    )

    @Test
    fun `no consent, withdrawn consent and unknown recipients create no job on any kind of device`() {
        devices.add("acc_withdrawn", "inst_w_0123456789abcd")
        devices.add("acc_withdrawn", "inst_w2_0123456789abcd", kind = PushKinds.FCM)
        devices.add("acc_never", "inst_n_0123456789abcd")
        devices.add("acc_never", "inst_n2_0123456789abcd", kind = PushKinds.FCM)
        channel().use { broker.publish(ad("acc_withdrawn", "acc_never")) }

        assertEquals(emptyList(), tasks.jobs.toList())
    }

    @Test
    fun `a consenting recipient gets one advertising job per device, marked, with the mark in the title`() {
        devices.add("acc_ok", "inst_a_0123456789abcd")
        devices.add("acc_ok", "inst_b_0123456789abcd", kind = PushKinds.FCM)
        devices.add("acc_withdrawn", "inst_c_0123456789abcd")
        channel().use { broker.publish(ad("acc_ok", "acc_withdrawn")) }

        assertEquals(2, tasks.jobs.size)
        assertTrue(tasks.jobs.all { (job, runAt) -> job.advertising && job.recipientId == "acc_ok" && runAt == null })
        assertEquals("(광고) 신기능 안내", tasks.jobs[0].first.message.title)
        assertEquals("새 기능이 나왔어요", tasks.jobs[0].first.message.body)
    }

    @Test
    fun `a lookup that throws sends nothing to that recipient and the channel keeps working`() {
        devices.add("acc_ok", "inst_a_0123456789abcd")
        channel().use {
            policy.failure = IllegalStateException("legal down")
            broker.publish(ad("acc_ok"))
            policy.failure = null
            broker.publish(ad("acc_ok"))
        }

        assertEquals(1, tasks.jobs.size)
    }

    @Test
    fun `at night without the night consent the job is held until the next 08 00 and with it the job goes at once`() {
        devices.add("acc_ok", "inst_a_0123456789abcd")
        devices.add("acc_night", "inst_b_0123456789abcd")
        clock.now = night
        channel().use { broker.publish(ad("acc_ok", "acc_night")) }

        val byRecipient = tasks.jobs.associate { (job, runAt) -> job.recipientId to runAt }
        assertEquals(Instant.parse("2026-10-31T23:00:00Z"), byRecipient["acc_ok"])   // 서울 11/1 08:00
        assertNull(byRecipient["acc_night"])
        assertTrue(tasks.jobs.all { it.first.advertising })
    }

    @Test
    fun `a held job still carries the 24 hour life, so the morning send is not expired`() {
        devices.add("acc_ok", "inst_a_0123456789abcd")
        clock.now = night
        channel().use { broker.publish(ad("acc_ok")) }

        val (job, runAt) = tasks.jobs.single()
        assertTrue(runAt!!.isBefore(job.message.expiresAt))
    }

    @Test
    fun `an app factory that forgets the mark still gets it, and the default factory shows an advertising text even when the lock screen hides`() {
        devices.add("acc_ok", "inst_a_0123456789abcd")
        val plain = PushMessageFactory { event -> PushMessage(event.id, event.type, "프로모션", createdAt = event.createdAt) }
        channel(factory = plain).use { broker.publish(ad("acc_ok")) }
        assertEquals("(광고) 프로모션", tasks.jobs.single().first.message.title)

        tasks.jobs.clear()
        val hidden = properties.copy(lockScreen = LockScreen.HIDDEN)
        channel(props = hidden).use {
            broker.publish(ad("acc_ok"))
            broker.publish(ad("acc_ok", type = "comment.created", title = "비밀 제목"))
        }
        assertEquals("(광고) 신기능 안내", tasks.jobs[0].first.message.title)
        assertEquals("새 알림이 있습니다", tasks.jobs[1].first.message.title, "informational types still hide")
        assertTrue(!tasks.jobs[1].first.advertising)
    }

    @Test
    fun `informational types never consult the advertising policy, and an advertising type never goes through the informational list`() {
        devices.add("acc_withdrawn", "inst_a_0123456789abcd")
        val both = properties.copy(types = listOf("comment.created", "promo.tips"))
        channel(props = both).use {
            broker.publish(ad("acc_withdrawn", type = "comment.created", title = "댓글"))
            broker.publish(ad("acc_withdrawn", type = "promo.tips"))
        }

        assertEquals(listOf("comment.created"), tasks.jobs.map { it.first.message.type })
        assertEquals(emptyList(), policy.calls.filter { false })
        assertEquals(1, policy.calls.size, "only the advertising event asked the policy")
    }

    @Test
    fun `an advertising type with no policy bean is never pushed`() {
        devices.add("acc_ok", "inst_a_0123456789abcd")
        channel(gatePolicy = null).use { broker.publish(ad("acc_ok")) }

        assertEquals(emptyList(), tasks.jobs.toList())
    }

    @Test
    fun `a broadcast advertisement without recipients is not pushed`() {
        devices.add("acc_ok", "inst_a_0123456789abcd")
        channel().use { broker.publish(ad()) }

        assertEquals(emptyList(), tasks.jobs.toList())
    }
}
