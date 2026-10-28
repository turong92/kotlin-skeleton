package dev.sumin.skeleton.point

import dev.sumin.skeleton.pointtest.FakeWallet
import dev.sumin.skeleton.pointtest.PointFakes
import dev.sumin.skeleton.pointtest.PointTestApplication
import dev.sumin.skeleton.pointtest.RecordingAudit
import dev.sumin.skeleton.pointtest.operator
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private const val BASE = "/api/v1/admin/points"

/** 운영 경로: `point.manage` 없으면 403, 익명 401, 조정이 운영 기록 `point.adjusted` 를 남기고, 잔액보다 큰 차감은 409 */
@SpringBootTest(
    classes = [PointTestApplication::class],
    properties = ["skeleton.point.admin.enabled=true", "skeleton.authz.roles.ADMIN=*", "skeleton.authz.roles.CS=point.manage"],
)
@AutoConfigureMockMvc
@Import(PointFakes::class)
class PointAdminWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var wallet: FakeWallet
    @Autowired lateinit var audit: RecordingAudit

    private val cs = operator("acc_cs", "CS")
    private val admin = operator("acc_admin", "ADMIN")
    private val other = operator("acc_other", "USER")
    private val body = """{"delta": 100, "reason": "event reward", "expiresInDays": 30}"""

    @BeforeTest
    fun reset() {
        wallet.entries.clear()
        audit.actions.clear()
    }

    @Test
    fun `anonymous is 401 and an operator without point manage is 403 on every admin path`() {
        mvc.perform(get("$BASE/a1")).andExpect(status().isUnauthorized)
        mvc.perform(post("$BASE/a1/adjustments").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized)
        mvc.perform(get("$BASE/a1").principal(other)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"))
        mvc.perform(post("$BASE/a1/adjustments").principal(other).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
        assertEquals(0, wallet.entries.size)
    }

    @Test
    fun `an adjustment is 201, shows up with the balance, and is recorded for the audit log by id only`() {
        mvc.perform(post("$BASE/a1/adjustments").principal(cs).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.delta").value(100))
            .andExpect(jsonPath("$.value.kind").value("ADJUST"))
            .andExpect(jsonPath("$.value.reason").value("event reward"))

        mvc.perform(get("$BASE/a1").principal(admin))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.balance").value(100))
            .andExpect(jsonPath("$.value.entries.length()").value(1))
        val recorded = audit.actions.single()
        assertEquals("point.adjusted", recorded.action)
        assertEquals("acc_cs", recorded.actorId)
        assertEquals(wallet.entries.single().id, recorded.targetId)
        assertEquals(emptyMap(), recorded.detail)   // 사유 원문 · 계정 id 는 싣지 않는다
    }

    @Test
    fun `a deduction bigger than the balance is 409 POINT_INSUFFICIENT with nothing recorded`() {
        wallet.earn("a1", 10, null, "GRANT", "g1")

        mvc.perform(post("$BASE/a1/adjustments").principal(cs).contentType(MediaType.APPLICATION_JSON).content("""{"delta": -50, "reason": "oops"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("POINT.INSUFFICIENT"))
        assertEquals(0, audit.actions.size)
        assertEquals(10, wallet.balance("a1").balance)
    }

    @Test
    fun `a bad adjustment body is 400`() {
        listOf(
            """{"reason": "no delta"}""",
            """{"delta": 0, "reason": "zero"}""",
            """{"delta": 5}""",
            """{"delta": 5, "reason": "   "}""",
            """{"delta": -5, "reason": "x", "expiresInDays": 3}""",
            """{"delta": 5, "reason": "x", "expiresInDays": 0}""",
        ).forEach {
            mvc.perform(post("$BASE/a1/adjustments").principal(cs).contentType(MediaType.APPLICATION_JSON).content(it)).andExpect(status().isBadRequest)
        }
        assertEquals(0, wallet.entries.size)
    }
}
