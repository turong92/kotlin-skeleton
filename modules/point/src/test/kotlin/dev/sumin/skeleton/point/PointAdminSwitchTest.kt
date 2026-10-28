package dev.sumin.skeleton.point

import dev.sumin.skeleton.pointtest.FakeWallet
import dev.sumin.skeleton.pointtest.PointFakes
import dev.sumin.skeleton.pointtest.PointTestApplication
import dev.sumin.skeleton.pointtest.operator
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 스위치: 운영 경로는 기본 꺼짐(404). 사용자 경로는 붙이면 켜지고 `http.enabled=false` 로 닫힌다 */
@SpringBootTest(classes = [PointTestApplication::class], properties = ["skeleton.authz.roles.ADMIN=*"])
@AutoConfigureMockMvc
@Import(PointFakes::class)
class PointAdminSwitchTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var wallet: FakeWallet

    @BeforeTest
    fun reset() = wallet.entries.clear()

    @Test
    fun `the admin paths are 404 until the switch is turned on, even for the all-access role`() {
        mvc.perform(get("/api/v1/admin/points/a1").principal(operator("acc_admin", "ADMIN"))).andExpect(status().isNotFound)
    }

    @Test
    fun `my balance and history are open to a signed-in user, 401 to anonymous, and only mine`() {
        wallet.earn("acc_me", 70, null, "GRANT", "g1")
        wallet.earn("acc_you", 5, null, "GRANT", "g2")

        mvc.perform(get("/api/v1/points")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/points").principal(operator("acc_me")))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.balance").value(70))
        mvc.perform(get("/api/v1/points/history").principal(operator("acc_me")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(1))
            .andExpect(jsonPath("$.values[0].delta").value(70))
            .andExpect(jsonPath("$.cursor.hasNext").value(false))
        mvc.perform(get("/api/v1/points/history?cursor=not-a-cursor").principal(operator("acc_me"))).andExpect(status().isBadRequest)
    }
}
