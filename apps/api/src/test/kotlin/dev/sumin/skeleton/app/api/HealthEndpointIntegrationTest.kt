package dev.sumin.skeleton.app.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * 컨테이너 헬스체크용 정직한 신호: `/health` 는 인증 없이 열려 있고(401 이 아니다), 앱과 DB 가 떠 있을 때만 200 `{"status":"UP"}` 이다
 * (DB 가 죽으면 액추에이터가 503 — `scripts/test-deploy-contract.sh` 가 DB 컨테이너를 멈춰 확인한다). 세부 정보는 싣지 않고, 나머지 액추에이터는 열지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HealthEndpointIntegrationTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `health is public, 200 UP and carries no details`() {
        mvc.get("/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.components") { doesNotExist() }
        }
    }

    @Test
    fun `the rest of the actuator stays closed`() {
        mvc.get("/env").andExpect { status { is4xxClientError() } }
        mvc.get("/beans").andExpect { status { is4xxClientError() } }
        mvc.get("/actuator/health").andExpect { status { is4xxClientError() } }
    }
}
