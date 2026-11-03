package dev.sumin.skeleton.app.sample.broadcast

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest
import dev.sumin.skeleton.notification.push.PushDevice
import dev.sumin.skeleton.notification.push.PushMessage
import dev.sumin.skeleton.notification.push.PushResult
import dev.sumin.skeleton.notification.push.PushSender
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * 공지 한 바퀴를 견본 앱 설정(application.yml)과 함께: 운영자(admin)가 전체에게 공지를 만들어 보내면 단계 작업이 돌아
 * 알림함 3줄(시드 3명)이 생기고, 푸시는 등록된 기기 수만큼 **가짜 보내기**로 나간다. 한 명이 읽으면 통계 `read = 1`,
 * 푸시의 클릭 보고를 두 번 해도 `opened = 1`. 진짜 푸시 서비스는 부르지 않는다 - 전달 수단은 시험 소스의 [Recorder] 다.
 */
@Import(BroadcastJourneyIntegrationTest.Fakes::class)
class BroadcastJourneyIntegrationTest : SampleIntegrationTest() {
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

    @BeforeEach
    fun clean() {
        listOf("broadcast_channel_stats", "broadcasts", "push_opens", "push_devices").forEach { jdbc.sql("delete from $it").update() }
        recorder.sent.clear()
    }

    private fun register(token: String) {
        val installationId = "inst-" + UUID.randomUUID().toString().replace("-", "").take(20)
        mockMvc.put("/api/v1/push/devices/$installationId") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"WEBPUSH","endpoint":"https://fcm.googleapis.com/fcm/send/$installationId","keys":{"p256dh":"BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM","auth":"tBHItJI5svbpez7KI4CCXg"}}"""
        }.andExpect { status { isOk() } }
    }

    private fun detail(admin: String, id: String): String =
        mockMvc.get("/api/v1/admin/broadcasts/$id") { header("Authorization", "Bearer $admin") }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString

    @Test
    fun `a broadcast to everyone reaches every inbox and every registered device, and read and click are counted once`() {
        val moderator = login("moderator@example.com")
        register(user)
        register(other)

        val id = mockMvc.post("/api/v1/admin/broadcasts") {
            header("Authorization", "Bearer $other")
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"notice.service","title":"점검 안내","body":"오늘 밤 점검이 있어요","channels":["push"],"audience":{"kind":"all"}}"""
        }.andExpect { status { isOk() } }.andReturn().readString("$.value.id")

        mockMvc.post("/api/v1/admin/broadcasts/$id/send") {
            header("Authorization", "Bearer $other")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isNoContent() } }

        await { JsonPath.read<String>(detail(other, id), "$.value.status").takeIf { it == "SENT" } }

        assertEquals(3, jdbc.sql("select count(*) from notification_inbox where event_id = :id").param("id", id).query(Int::class.java).single())
        await { recorder.sent.takeIf { it.size >= 2 } }
        assertEquals(2, recorder.sent.size, "한 기기에 하나씩 - 기기가 없는 moderator 에게는 푸시가 없다")

        // 알림함 읽음 - 한 명
        mockMvc.patch("/api/v1/notifications/$id/read") { header("Authorization", "Bearer $moderator") }
            .andExpect { status { is2xxSuccessful() } }
        assertEquals(1, JsonPath.read<Number>(detail(other, id), "$.value.stats.inbox.read").toInt())

        // 푸시 클릭 보고 - 같은 보고를 두 번 해도 한 번
        val message = recorder.sent.first().second
        val open = assertNotNull(message.openToken, "견본은 open-report 를 켠다")
        repeat(2) {
            mockMvc.post("/api/v1/push/opened") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"id":"${message.eventId}","open":"$open"}"""
            }.andExpect { status { isNoContent() } }
        }
        assertEquals(1, JsonPath.read<Number>(detail(other, id), "$.value.stats.channels.push.opened").toInt())
    }
}
