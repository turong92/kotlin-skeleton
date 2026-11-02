package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.pushtest.PushWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [PushWebTestApplication::class],
    properties = [
        "skeleton.notification.push.enabled=true",
        "skeleton.notification.push.types=comment.created",
        "skeleton.notification.push.open-report.secret=0123456789abcdef0123456789abcdef0123",
    ],
)
@AutoConfigureMockMvc
@Import(PushOpenWebTest.Fixtures::class)
class PushOpenWebTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun pushDeviceStore(): PushDeviceStore = FakePushDeviceStore()
        @Bean fun pushTypeMuteStore(): PushTypeMuteStore = FakePushTypeMuteStore()
        @Bean fun pushTasks(): PushTasks = RecordingPushTasks()
        @Bean fun pushOpenStore(): PushOpenStore = FakePushOpenStore()
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var devices: PushDeviceStore
    @Autowired lateinit var store: PushOpenStore

    private fun report(body: String) =
        mockMvc.perform(post("/api/v1/push/opened").contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `the report path needs no login - a good token is 204 once or twice and a forged one is 400`() {
        val device = (devices as FakePushDeviceStore).add("acc_a")
        val token = PushOpenTokens("0123456789abcdef0123456789abcdef0123").issue("evt_1", device)
        val body = """{"id":"evt_1","open":"$token"}"""

        report(body).andExpect(status().isNoContent)
        report(body).andExpect(status().isNoContent)
        assertEquals(setOf("evt_1" to "acc_a"), (store as FakePushOpenStore).rows)

        report("""{"id":"evt_1","open":"${token.dropLast(2)}AA"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PUSH.INVALID_OPEN_TOKEN"))
        report("""{"id":"evt_1"}""").andExpect(status().isBadRequest)
    }
}
