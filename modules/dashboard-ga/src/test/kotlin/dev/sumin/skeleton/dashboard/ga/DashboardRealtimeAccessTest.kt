package dev.sumin.skeleton.dashboard.ga

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootApplication
class GaWebTestApplication

/** 권한: 현재 방문자 경로도 `dashboard.read` 가 있어야 한다 — 익명 401, 권한 없음 403, 키 없이는 200 에 NOT_CONNECTED */
@SpringBootTest(
    classes = [GaWebTestApplication::class],
    properties = ["skeleton.dashboard.admin.enabled=true", "skeleton.authz.roles.VIEWER=dashboard.read"],
)
@AutoConfigureMockMvc
class DashboardRealtimeAccessTest {
    @Autowired lateinit var mvc: MockMvc

    private val path = "/api/v1/admin/dashboard/realtime"

    @Test
    fun `anonymous is 401 and an operator without dashboard read is 403`() {
        mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc.perform(get(path).principal(TestingAuthenticationToken("acc_other", "n/a", "ROLE_USER")))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"))
    }

    @Test
    fun `with dashboard read it answers, and without keys it says not connected`() {
        mvc.perform(get(path).principal(TestingAuthenticationToken("acc_viewer", "n/a", "ROLE_VIEWER")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.status").value("NOT_CONNECTED"))
            .andExpect(jsonPath("$.value.activeUsers").value(0))
    }
}
