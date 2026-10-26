package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.dashboardtest.DashboardFakes
import dev.sumin.skeleton.dashboardtest.DashboardTestApplication
import dev.sumin.skeleton.dashboardtest.operator
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private const val LAYOUT = "/api/v1/admin/dashboard/layout"

@TestConfiguration(proxyBeanMethods = false)
class MemoryLayoutStore {
    @Bean fun layoutStore(): DashboardLayoutStore = object : DashboardLayoutStore {
        private val rows = ConcurrentHashMap<String, String>()
        override fun find(accountId: String) = rows[accountId]
        override fun save(accountId: String, json: String) { rows[accountId] = json }
        override fun delete(accountId: String) { rows.remove(accountId) }
    }
}

/** 권한: 화면 설정은 호출자 자기 것만 (경로에 계정 id 가 없다), `dashboard.read` 없으면 403 */
@SpringBootTest(
    classes = [DashboardTestApplication::class],
    properties = ["skeleton.dashboard.admin.enabled=true", "skeleton.authz.roles.VIEWER=dashboard.read"],
)
@AutoConfigureMockMvc
@Import(DashboardFakes::class, MemoryLayoutStore::class)
class DashboardLayoutTest {
    @Autowired lateinit var mvc: MockMvc

    private val a = operator("acc_a", "VIEWER")
    private val b = operator("acc_b", "VIEWER")

    private fun save(who: org.springframework.security.authentication.TestingAuthenticationToken, body: String) =
        mvc.perform(put(LAYOUT).principal(who).contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `what one operator saves is not seen by another - the other gets the empty default`() {
        save(a, """{"hidden":["fake.count"],"order":["funnel","tiles"]}""").andExpect(status().isOk)

        mvc.perform(get(LAYOUT).principal(a)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.hidden[0]").value("fake.count")).andExpect(jsonPath("$.value.order[0]").value("funnel"))
        mvc.perform(get(LAYOUT).principal(b)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.hidden.length()").value(0)).andExpect(jsonPath("$.value.order.length()").value(0))
    }

    @Test
    fun `without dashboard read it is 403, anonymous 401`() {
        val other = operator("acc_other", "USER")
        mvc.perform(get(LAYOUT)).andExpect(status().isUnauthorized)
        mvc.perform(get(LAYOUT).principal(other)).andExpect(status().isForbidden)
        save(other, """{"hidden":[],"order":[]}""").andExpect(status().isForbidden)
    }

    @Test
    fun `unknown keys, duplicates, an order with a tile key and a too large body are 400 LAYOUT_INVALID`() {
        listOf(
            """{"hidden":["no.such.card"],"order":[]}""",
            """{"hidden":["fake.count","fake.count"],"order":[]}""",
            """{"hidden":[],"order":["fake.count"]}""",
            """{"hidden":[${(1..200).joinToString(",") { "\"fake.count\"" }}],"order":[]}""",
        ).forEach { body -> save(a, body).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DASHBOARD.LAYOUT_INVALID")) }
    }
}

/** 저장소(dashboard-jdbc)가 없으면 404 LAYOUT_UNAVAILABLE — 프런트는 브라우저 저장으로 물러난다 */
@SpringBootTest(classes = [DashboardTestApplication::class], properties = ["skeleton.dashboard.admin.enabled=true", "skeleton.authz.roles.VIEWER=dashboard.read"])
@AutoConfigureMockMvc
@Import(DashboardFakes::class)
class DashboardLayoutUnavailableTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `no store is 404 with its own code`() {
        mvc.perform(get(LAYOUT).principal(operator("acc_a", "VIEWER"))).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("DASHBOARD.LAYOUT_UNAVAILABLE"))
    }
}
