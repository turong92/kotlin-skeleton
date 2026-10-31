package dev.sumin.skeleton.app.sample.push

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.push.PushDevice
import dev.sumin.skeleton.notification.push.PushMessage
import dev.sumin.skeleton.notification.push.PushResult
import dev.sumin.skeleton.notification.push.PushSender
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/**
 * 푸시의 한 바퀴를 견본 앱 설정(application.yml)과 함께: 기기를 등록하고, 다른 사람이 내 글에 댓글을 달면 작업이 생겨 처리기가 **가짜 보내기**로 보낸다.
 * 광고 종류는 동의가 없으면 작업이 없고, 동의(광고 + 야간)하면 `(광고)` 표기로 나간다. 진짜 푸시 서비스는 부르지 않는다 - 전달 수단은 시험 소스의 [Recorder] 다.
 */
@Import(PushJourneyIntegrationTest.Fakes::class)
class PushJourneyIntegrationTest : SampleIntegrationTest() {
    class Recorder : PushSender {
        override val kind = "WEBPUSH"
        val sent = CopyOnWriteArrayList<Pair<PushDevice, PushMessage>>()
        override fun send(device: PushDevice, message: PushMessage): PushResult {
            sent += device to message
            return PushResult.Delivered
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fakes {
        @Bean
        fun recorder() = Recorder()
    }

    @Autowired lateinit var recorder: Recorder
    @Autowired lateinit var publisher: NotificationPublisher

    @BeforeEach
    fun cleanPush() {
        jdbc.sql("delete from push_devices").update()
        recorder.sent.clear()
    }

    private fun register(token: String, installationId: String = "inst-" + UUID.randomUUID().toString().replace("-", "").take(20)) {
        mockMvc.put("/api/v1/push/devices/$installationId") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"WEBPUSH","endpoint":"https://fcm.googleapis.com/fcm/send/$installationId","keys":{"p256dh":"BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM","auth":"tBHItJI5svbpez7KI4CCXg"}}"""
        }.andExpect { status { isOk() } }
    }

    private fun profileOf(token: String): String =
        mockMvc.get("/api/v1/author-profiles") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }.andReturn().let { JsonPath.read<String>(it.response.contentAsString, "$.values[0].id") }

    private fun jobCount(): Int = jdbc.sql("select count(*) from jobs where job_type = 'notification.push'").query(Int::class.java).single()

    @Test
    fun `a comment from someone else on my post pushes to my registered device, my own comment does not`() {
        register(user)
        val post = mockMvc.post("/api/v1/boards/general/posts") {
            header("Authorization", "Bearer $user"); header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON; content = """{"title":"푸시 받을 글","body":"본문","authorId":"${profileOf(user)}"}"""
        }.andExpect { status { isCreated() } }.andReturn().let { JsonPath.read<Number>(it.response.contentAsString, "$.value.id").toLong() }
        fun comment(token: String, text: String) = mockMvc.post("/api/v1/boards/general/posts/$post/comments") {
            header("Authorization", "Bearer $token"); header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON; content = """{"body":"$text","authorId":"${profileOf(token)}"}"""
        }.andExpect { status { isCreated() } }

        comment(user, "내 글에 내가")
        comment(other, "남이 단 댓글")

        val (device, message) = await { recorder.sent.firstOrNull() }
        assertEquals("acc_user", device.accountId)
        assertEquals("comment.created", message.type)
        assertTrue(message.title.contains("푸시 받을 글"), "lock-screen FULL: ${message.title}")
        assertEquals(1, recorder.sent.size)
    }

    @Test
    fun `an advertising type sends nothing without consent and goes out marked as an ad after consent`() {
        register(user)
        fun advertise() = publisher.publish(
            NotificationEvent(topic = "promo", type = "promo.notes-tips", recipientIds = setOf("acc_user"), title = "노트 쓰는 팁", message = "핀을 눌러 보세요"),
        )

        advertise()
        Thread.sleep(1500)
        assertEquals(0, jobCount(), "no consent = no job")
        assertTrue(recorder.sent.isEmpty())

        mockMvc.post("/api/v1/legal/consents") {
            header("Authorization", "Bearer $user")
            contentType = MediaType.APPLICATION_JSON
            content = """{"consents":[{"type":"marketing","version":"sample-1","locale":"ko"},{"type":"marketing-night","version":"sample-1","locale":"ko"}],"source":"push-settings"}"""
        }.andExpect { status { is2xxSuccessful() } }

        advertise()
        // 동의 자체의 변경 통지(정보성 advertising.consent.changed)도 푸시로 나간다 - 광고 알림만 골라 본다
        val (_, message) = await { recorder.sent.firstOrNull { it.second.type == "promo.notes-tips" } }
        assertTrue(message.title.startsWith("(광고)"), message.title)
        assertTrue(recorder.sent.any { it.second.type == "advertising.consent.changed" && !it.second.title.startsWith("(광고)") })
    }

    @Test
    fun `the night window uses the profile time zone and falls back to the configured zone`() {
        fun zone(token: String) = mockMvc.get("/api/v1/push/advertising") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }.andReturn().let { JsonPath.read<String>(it.response.contentAsString, "$.value.nightWindow.zone") }

        assertEquals("Asia/Seoul", zone(other), "no profile zone = the configured fallback")
        mockMvc.patch("/api/v1/account/me") {
            header("Authorization", "Bearer $other")
            contentType = MediaType.APPLICATION_JSON
            content = """{"timeZone":"America/New_York"}"""
        }.andExpect { status { isOk() } }
        assertEquals("America/New_York", zone(other))
    }
}
