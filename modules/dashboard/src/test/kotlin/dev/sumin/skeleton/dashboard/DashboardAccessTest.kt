package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.dashboardtest.DashboardFakes
import dev.sumin.skeleton.dashboardtest.DashboardTestApplication
import dev.sumin.skeleton.dashboardtest.operator
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private const val BASE = "/api/v1/admin/dashboard"
private const val DAY = "from=2026-10-20&to=2026-10-26"

/** 권한: `dashboard.read` 없으면 모든 대시보드 경로 403, 익명 401, 있으면 200 */
@SpringBootTest(
    classes = [DashboardTestApplication::class],
    properties = ["skeleton.dashboard.admin.enabled=true", "skeleton.dashboard.currency=KRW", "skeleton.authz.roles.ADMIN=*", "skeleton.authz.roles.VIEWER=dashboard.read"],
)
@AutoConfigureMockMvc
@Import(DashboardFakes::class)
class DashboardAccessTest {
    @Autowired lateinit var mvc: MockMvc

    private val viewer = operator("acc_viewer", "VIEWER")
    private val admin = operator("acc_admin", "ADMIN")
    private val other = operator("acc_other", "USER")

    private val paths = listOf("$BASE?$DAY", "$BASE/rankings/fake.pages?$DAY", "$BASE/rankings/nope?$DAY")

    @Test
    fun `anonymous is 401 on every dashboard path`() {
        paths.forEach { mvc.perform(get(it)).andExpect(status().isUnauthorized) }
    }

    @Test
    fun `an operator without dashboard read is 403 on every path, even for a ranking that does not exist`() {
        paths.forEach { path ->
            mvc.perform(get(path).principal(other)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"))
        }
    }

    @Test
    fun `dashboard read opens the overview and a ranking, and the full-access role always has it`() {
        val response = mvc.perform(get("$BASE?$DAY&compare=true").principal(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.range.interval").value("DAY"))
            .andExpect(jsonPath("$.value.previous.from").value("2026-10-13"))
            .andExpect(jsonPath("$.value.metrics[0].key").value("fake.count"))
            .andExpect(jsonPath("$.value.metrics[0].value[0].amount").value(7))
            .andExpect(jsonPath("$.value.metrics[0].previousValue[0].amount").value(7))
            .andExpect(jsonPath("$.value.metrics[0].freshness.kind").value("LIVE"))
            // 설정 통화가 먼저, 나머지는 금액이 큰 순 · 비교 불가 지표는 직전 기간이 null
            .andExpect(jsonPath("$.value.metrics[1].value[0].currency").value("KRW"))
            .andExpect(jsonPath("$.value.metrics[1].value[1].currency").value("JPY"))
            .andExpect(jsonPath("$.value.metrics[1].previousValue").value(null as Any?))
            // 기여가 터져도 그 카드만 ERROR, 응답은 200 — 예외 메시지(비밀)는 응답에 없다
            .andExpect(jsonPath("$.value.metrics[2].status").value("ERROR"))
            .andExpect(jsonPath("$.value.funnel.status").value("OK"))
            .andExpect(jsonPath("$.value.funnel.steps[1].rate").value(0.5))
            .andReturn().response.contentAsString
        assertFalse("secret-value" in response, "a failing contributor's message must not reach the response")
        mvc.perform(get("$BASE/rankings/fake.pages?$DAY&limit=1").principal(admin))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.rows.length()").value(1))
    }

    @Test
    fun `a bad range is 400 and an unknown ranking is 404`() {
        mvc.perform(get("$BASE?from=2026-10-26&to=2026-10-20").principal(viewer))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DASHBOARD.RANGE_INVALID"))
        mvc.perform(get("$BASE/rankings/nope?$DAY").principal(viewer))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("DASHBOARD.RANKING_NOT_FOUND"))
    }
}

/** 스위치가 꺼져 있으면(기본) 길이 없다 — 권한이 있어도 404 */
@SpringBootTest(classes = [DashboardTestApplication::class], properties = ["skeleton.authz.roles.VIEWER=dashboard.read"])
@AutoConfigureMockMvc
@Import(DashboardFakes::class)
class DashboardDisabledTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `the admin switch off is 404 for everyone`() {
        mvc.perform(get(BASE).principal(operator("acc_viewer", "VIEWER"))).andExpect(status().isNotFound)
        mvc.perform(get(BASE)).andExpect(status().isNotFound)
    }
}
