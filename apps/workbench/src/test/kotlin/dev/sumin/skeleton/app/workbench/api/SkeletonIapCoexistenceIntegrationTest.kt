package dev.sumin.skeleton.app.workbench.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.workbench.TestcontainersConfiguration
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 인앱결제 모듈(payment-iap · payment-appstore · payment-googleplay · auth-web-handoff)이 다른 모든 모듈과 한 앱에 공존해 뜨는지 본다 - 클래스패스에 있으면 기본은 꺼져 있고(다른 시험이 그대로 뜬다),
 * 앱 yml 이 켜면 키 없이도 뜨며 `store` 는 `null` 이다. auth-web-handoff 는 계정 저장소가 있어야 켤 수 있어 여기서는 켜지 않는다(워크벤치는 시드 계정). 흐름(확인 → 응원 → 환불)은 apps/sample 의 통합 시험이 본다.
 */
@SpringBootTest(properties = ["skeleton.payment-iap.enabled=true"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SkeletonIapCoexistenceIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `with the iap modules on, the app boots without store keys and the config has no store`() {
        mockMvc.get("/api/v1/payments/iap/config") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            param("platform", "ios")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.store") { value(null) }
            jsonPath("$.value.channel") { value("IAP_ONLY") }
        }
    }

    private fun loginAccessToken(): String {
        val response = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return JsonPath.read<String>(response, "$.value.accessToken").also { assertTrue(it.isNotBlank()) }
    }
}
