package dev.sumin.skeleton.notification.broadcast.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.broadcasttest.BroadcastWebTestApplication
import dev.sumin.skeleton.common.audience.AudienceSource
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.notification.NotificationChannelDeclaration
import dev.sumin.skeleton.notification.broadcast.BroadcastStatus
import dev.sumin.skeleton.notification.broadcast.BroadcastStore
import dev.sumin.skeleton.notification.broadcast.BroadcastTasks
import dev.sumin.skeleton.notification.broadcast.FakeBroadcastStore
import dev.sumin.skeleton.notification.broadcast.RecordingTasks
import dev.sumin.skeleton.notification.broadcast.StaticAudience
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class BroadcastTestBeans {
    @Bean fun store(): BroadcastStore = FakeBroadcastStore()
    @Bean fun tasks(): BroadcastTasks = RecordingTasks()
    @Bean fun everyone(): AudienceSource = StaticAudience("everyone", listOf("acc_1", "acc_2", "acc_3"))
    @Bean fun pushDeclaration() = NotificationChannelDeclaration("push")
    @Bean fun access(): OperatorAccess = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = GRANTS[ref.accountId] ?: emptySet()
    }
    @Bean fun audit(): OperatorAuditLog = OperatorAuditLog { ACTIONS += it }

    companion object {
        val GRANTS = ConcurrentHashMap<String, Set<String>>()
        val ACTIONS = CopyOnWriteArrayList<OperatorAction>()
    }
}

/** 운영자 경로: 권한(보기만으로는 보낼 수 없다) · 링크 검사 · 상세의 모양(화면이 가정한 `stats` 중첩) · 운영 기록 */
@SpringBootTest(
    classes = [BroadcastWebTestApplication::class],
    properties = [
        "skeleton.notification.broadcast.enabled=true", "skeleton.notification.broadcast.admin.enabled=true",
        "skeleton.notification.broadcast.types=notice.service",
    ],
)
@AutoConfigureMockMvc
@Import(BroadcastTestBeans::class)
class BroadcastAdminWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: BroadcastStore
    @Autowired lateinit var tasks: BroadcastTasks

    private val sender = TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER")
    private val viewer = TestingAuthenticationToken("acc_view", "n/a", "ROLE_USER")
    private val nobody = TestingAuthenticationToken("acc_none", "n/a", "ROLE_USER")
    private val json = """{"type":"notice.service","title":"Maintenance","body":"Tonight","link":"/notices/1","channels":["push"],"audience":{"kind":"everyone"}}"""

    @BeforeEach
    fun reset() {
        BroadcastTestBeans.GRANTS.clear()
        BroadcastTestBeans.GRANTS["acc_op"] = setOf("notification-broadcast.read", "notification-broadcast.send")
        BroadcastTestBeans.GRANTS["acc_view"] = setOf("notification-broadcast.read")
        BroadcastTestBeans.ACTIONS.clear()
        (store as FakeBroadcastStore).rows.clear()
        (tasks as RecordingTasks).steps.clear()
    }

    private fun create(body: String = json) =
        mvc.perform(post("/api/v1/admin/broadcasts").principal(sender).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun createdId(): String = JsonPath.read<String>(create().andReturn().response.contentAsString, "$.value.id")

    @Test
    fun `read alone cannot write, send, test or cancel, and nothing at all is 403 or 401`() {
        val id = createdId()
        mvc.perform(get("/api/v1/admin/broadcasts").principal(viewer)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/admin/broadcasts/$id").principal(viewer)).andExpect(status().isOk)
        listOf("test", "send", "cancel", "resume", "unschedule").forEach {
            mvc.perform(post("/api/v1/admin/broadcasts/$id/$it").principal(viewer)).andExpect(status().isForbidden)
        }
        mvc.perform(post("/api/v1/admin/broadcasts").principal(viewer).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/broadcasts").principal(nobody)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/broadcasts")).andExpect(status().isUnauthorized)
        assertEquals(BroadcastStatus.DRAFT, store.find(id)!!.status)
        assertEquals(0, (tasks as RecordingTasks).steps.size)
    }

    @Test
    fun `a link outside the app is refused`() {
        create(json.replace("/notices/1", "//evil.example")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BROADCAST.INVALID_LINK"))
    }

    @Test
    fun `sending starts the first step, is recorded, and the detail has the shape the screen reads`() {
        val id = createdId()
        mvc.perform(post("/api/v1/admin/broadcasts/$id/send").principal(sender).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent)
        mvc.perform(post("/api/v1/admin/broadcasts/$id/send").principal(sender)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("BROADCAST.INVALID_STATE"))
        assertEquals(1, (tasks as RecordingTasks).steps.size)
        assertEquals(listOf("notification-broadcast.notice.sent"), BroadcastTestBeans.ACTIONS.map { it.action })
        mvc.perform(get("/api/v1/admin/broadcasts/$id").principal(viewer))
            .andExpect(jsonPath("$.value.status").value("SENDING"))
            .andExpect(jsonPath("$.value.estimate").value(3))
            .andExpect(jsonPath("$.value.channels[0]").value("push"))
            .andExpect(jsonPath("$.value.audience.kind").value("everyone"))
            .andExpect(jsonPath("$.value.stats.channels.push.opened").value(0))
        mvc.perform(get("/api/v1/admin/broadcasts/options").principal(viewer))
            .andExpect(jsonPath("$.value.channels[0]").value("push"))
            .andExpect(jsonPath("$.value.audiences[0].param").value("NONE"))
    }
}
