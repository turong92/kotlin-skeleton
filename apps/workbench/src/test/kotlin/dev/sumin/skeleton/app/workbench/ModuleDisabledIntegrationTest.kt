package dev.sumin.skeleton.app.workbench

import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertFalse

/**
 * 모듈을 enabled=false 로 끄면 그 모듈의 빈이 통째로 빠지고, 앱은 그대로 뜬다.
 * 컨트롤러가 스캔으로 따로 등록되면 서비스 빈이 없어 기동이 실패한다 — 모듈은 AutoConfiguration 으로만 등록한다.
 */
@SpringBootTest(properties = ["skeleton.notification.sse.enabled=false"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ModuleDisabledIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `context boots with notification-sse disabled and none of its beans exist`() {
        assertFalse(context.containsBean("notificationSseController"))
        assertFalse(context.containsBean("notificationSseService"))
    }

    @Test
    fun `module catalog reports notification-sse as disabled and its endpoint is gone`() {
        val token = login()
        mockMvc.get("/api/v1/skeleton/modules") {
            header("Authorization", "Bearer $token")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.values[?(@.id == 'notification-sse')].status") { value(hasItem("DISABLED")) }
            jsonPath("$.values[?(@.id == 'notification')].status") { value(hasItem("ACTIVE")) }
        }
        mockMvc.get("/api/v1/notifications/sse") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("COMMON.NOT_FOUND") }
        }
    }

    private fun login(): String =
        JsonPath.read(
            mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                accept = MediaType.APPLICATION_JSON
                content = """{"email":"user@example.com","password":"password"}"""
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
            "$.value.accessToken",
        )
}
