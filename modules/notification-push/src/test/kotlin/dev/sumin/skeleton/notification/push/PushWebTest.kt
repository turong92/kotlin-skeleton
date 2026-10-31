package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.pushtest.PushWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [PushWebTestApplication::class],
    properties = [
        "skeleton.notification.push.enabled=true",
        "skeleton.notification.push.types=NOTE_UPDATED,comment.created",
        "skeleton.notification.push.advertising-types=promo.tips",
        "skeleton.notification.push.advertising.fallback-zone=Asia/Seoul",
        "skeleton.notification.push.advertising.sender-name=Example Co.",
        "skeleton.notification.preferences.types=comment.created",
    ],
)
@AutoConfigureMockMvc
@Import(PushWebTest.Fixtures::class)
class PushWebTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun pushDeviceStore(): PushDeviceStore = FakePushDeviceStore()
        @Bean fun pushTypeMuteStore(): PushTypeMuteStore = FakePushTypeMuteStore()
        @Bean fun pushTasks(): PushTasks = RecordingPushTasks()
        @Bean fun webSender(): PushSender = StrictWebSender()
        @Bean fun advertisingPolicy(): AdvertisingConsentPolicy = AdvertisingConsentPolicy { _, _ -> AdvertisingConsent(send = false, night = false) }
    }

    /** endpoint 에 `bad` 가 들어 있으면 거절하는 웹 푸시 전달 수단 */
    class StrictWebSender : PushSender {
        override val kind = PushKinds.WEBPUSH
        override val publicKey = "BPublicKeyForTests"
        override fun validate(registration: PushRegistration) = if ("bad" in registration.address) "endpoint_host_not_allowed" else null
        override fun send(device: PushDevice, message: PushMessage) = PushResult.Delivered
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var devices: PushDeviceStore
    @Autowired lateinit var tasks: PushTasks
    @Autowired lateinit var mutes: PushTypeMuteStore

    private fun caller(accountId: String) = TestingAuthenticationToken(accountId, "n/a", "ROLE_USER")
    private val web = """{"kind":"WEBPUSH","endpoint":"https://push.example.com/send/abc","keys":{"p256dh":"BP","auth":"AU"},"label":"Chrome · macOS"}"""

    private fun register(account: String, installation: String, body: String = web): ResultActions =
        mockMvc.perform(put("/api/v1/push/devices/$installation").with(authentication(caller(account))).contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `config exposes the public key and the pushable types`() {
        mockMvc.perform(get("/api/v1/push/config").with(authentication(caller("acc_c"))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.webPush.publicKey").value("BPublicKeyForTests"))
            .andExpect(jsonPath("$.value.fcm").value(false))
            .andExpect(jsonPath("$.value.types[0]").value("NOTE_UPDATED"))
            .andExpect(jsonPath("$.value.advertisingTypes[0]").value("promo.tips"))
    }

    @Test
    fun `register stores the device for the caller, list shows only mine, and a rejected endpoint is 400 with a reason`() {
        register("acc_1", "inst_aaaaaaaaaaaaaaaa").andExpect(status().isOk)
            .andExpect(jsonPath("$.value.installationId").value("inst_aaaaaaaaaaaaaaaa"))
            .andExpect(jsonPath("$.value.address").doesNotExist())
        register("acc_2", "inst_bbbbbbbbbbbbbbbb").andExpect(status().isOk)

        mockMvc.perform(get("/api/v1/push/devices").with(authentication(caller("acc_1"))))
            .andExpect(jsonPath("$.value.items.length()").value(1))
            .andExpect(jsonPath("$.value.items[0].label").value("Chrome · macOS"))

        register("acc_1", "inst_cccccccccccccccc", web.replace("send/abc", "bad/abc")).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PUSH.INVALID_DEVICE"))
            .andExpect(jsonPath("$.data.reason").value("endpoint_host_not_allowed"))
        register("acc_1", "short").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PUSH.INVALID_DEVICE"))
        register("acc_1", "inst_dddddddddddddddd", """{"kind":"FCM","token":"tok","platform":"android"}""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PUSH.KIND_DISABLED"))
    }

    @Test
    fun `deleting someone else's installation id removes nothing`() {
        register("acc_3", "inst_eeeeeeeeeeeeeeee").andExpect(status().isOk)

        mockMvc.perform(delete("/api/v1/push/devices/inst_eeeeeeeeeeeeeeee").with(authentication(caller("acc_attacker")))).andExpect(status().isNoContent)
        assertEquals(1, devices.listByAccount("acc_3").size)

        mockMvc.perform(delete("/api/v1/push/devices/inst_eeeeeeeeeeeeeeee").with(authentication(caller("acc_3")))).andExpect(status().isNoContent)
        assertEquals(0, devices.listByAccount("acc_3").size)
    }

    @Test
    fun `preferences list the pushable types, the toggle is per caller and idempotent, and other types are 404`() {
        mockMvc.perform(get("/api/v1/push/preferences").with(authentication(caller("acc_p"))))
            .andExpect(jsonPath("$.value.items.length()").value(2))
            .andExpect(jsonPath("$.value.items[0].type").value("NOTE_UPDATED"))
            .andExpect(jsonPath("$.value.items[0].push").value(true))

        repeat(2) {
            mockMvc.perform(put("/api/v1/push/preferences/NOTE_UPDATED").with(authentication(caller("acc_p"))).contentType(MediaType.APPLICATION_JSON).content("""{"enabled":false}"""))
                .andExpect(status().isOk).andExpect(jsonPath("$.value.push").value(false))
        }
        assertEquals(setOf("NOTE_UPDATED"), mutes.mutedTypes("acc_p"))
        assertEquals(emptySet(), mutes.mutedTypes("acc_q"))

        for (type in listOf("SECURITY_ALERT", "promo.tips")) {
            mockMvc.perform(put("/api/v1/push/preferences/$type").with(authentication(caller("acc_p"))).contentType(MediaType.APPLICATION_JSON).content("""{"enabled":false}"""))
                .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PUSH.UNKNOWN_TYPE"))
        }
    }

    @Test
    fun `a type muted for all channels shows allChannelsMuted so the screen can lock the push switch`() {
        // comment.created 는 skeleton.notification.preferences.types 에 있는 「끌 수 있는 종류」다 — 모든 채널 끄기는 알림 모듈의 표(기본 인메모리)에 있다
        val muteRepository = mockMvcContext.getBean(dev.sumin.skeleton.notification.NotificationMuteRepository::class.java)
        muteRepository.setMuted("acc_m", "comment.created", true)

        mockMvc.perform(get("/api/v1/push/preferences").with(authentication(caller("acc_m"))))
            .andExpect(jsonPath("$.value.items[1].type").value("comment.created"))
            .andExpect(jsonPath("$.value.items[1].allChannelsMuted").value(true))
            .andExpect(jsonPath("$.value.items[0].allChannelsMuted").value(false))
    }

    @Autowired lateinit var mockMvcContext: org.springframework.context.ApplicationContext

    @Test
    fun `test push enqueues one job for my device, is throttled to once a minute, and refuses other people's devices`() {
        register("acc_t", "inst_tttttttttttttttt").andExpect(status().isOk)
        val recording = tasks as RecordingPushTasks

        mockMvc.perform(post("/api/v1/push/devices/inst_tttttttttttttttt/test").with(authentication(caller("acc_t")))).andExpect(status().isAccepted)
        mockMvc.perform(post("/api/v1/push/devices/inst_tttttttttttttttt/test").with(authentication(caller("acc_t"))))
            .andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("PUSH.TEST_TOO_SOON"))
        mockMvc.perform(post("/api/v1/push/devices/inst_tttttttttttttttt/test").with(authentication(caller("acc_other")))).andExpect(status().isNotFound)

        assertEquals(1, recording.jobs.count { it.first.recipientId == "acc_t" })
        assertTrue(recording.jobs.none { it.first.recipientId == "acc_other" })
    }

    @Test
    fun `without an authenticated caller every path is 401`() {
        mockMvc.perform(get("/api/v1/push/config")).andExpect(status().isUnauthorized)
        mockMvc.perform(put("/api/v1/push/devices/inst_zzzzzzzzzzzzzzzz").contentType(MediaType.APPLICATION_JSON).content(web)).andExpect(status().isUnauthorized)
    }
}
