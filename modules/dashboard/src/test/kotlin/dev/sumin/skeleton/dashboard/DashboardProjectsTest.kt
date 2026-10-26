package dev.sumin.skeleton.dashboard

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.dashboardtest.DashboardTestApplication
import dev.sumin.skeleton.dashboardtest.FakeMetric
import dev.sumin.skeleton.dashboardtest.ProjectEchoFakes
import dev.sumin.skeleton.dashboardtest.operator
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 격리: 캐시는 프로젝트마다 따로 — A 의 Host 로 받은 숫자가 B 의 Host 에 보이지 않고, 같은 프로젝트의 반복 질의는 기여를 한 번만 부른다 */
@SpringBootTest(
    classes = [DashboardTestApplication::class],
    properties = [
        "skeleton.dashboard.admin.enabled=true",
        "skeleton.authz.roles.VIEWER=dashboard.read",
        "skeleton.projects.alpha.hosts=alpha.test",
        "skeleton.projects.alpha.schema=alpha",
        "skeleton.projects.beta.hosts=beta.test",
        "skeleton.projects.beta.schema=beta",
    ],
)
@AutoConfigureMockMvc
@Import(ProjectEchoFakes::class)
class DashboardProjectsTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var echo: FakeMetric

    private val viewer = operator("acc_viewer", "VIEWER")

    private fun amountOn(host: String): Int {
        val body = mvc.perform(get("http://$host/api/v1/admin/dashboard?from=2026-10-20&to=2026-10-26").principal(viewer))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        return JsonPath.read<Number>(body, "$.value.metrics[0].value[0].amount").toInt()
    }

    @Test
    fun `a project never sees another project's cached number and repeats cost one call`() {
        echo.valueCalls.set(0)
        assertEquals(111, amountOn("alpha.test"))
        assertEquals(222, amountOn("beta.test"))
        repeat(5) {
            assertEquals(111, amountOn("alpha.test"))
            assertEquals(222, amountOn("beta.test"))
        }
        assertEquals(2, echo.valueCalls.get())
    }
}
