package dev.sumin.skeleton.app.api

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post

/** 견본 `multi` 프로필 그대로 — a.localhost · b.localhost 가 같은 경로에서 각자의 컨트롤러, 각자의 시드 계정, 각자의 CORS 출처를 가진다 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("multi")
@Import(TestcontainersConfiguration::class)
class MultiProfileIntegrationTest {
    @Autowired lateinit var mvc: MockMvc

    private fun host(h: String): (org.springframework.mock.web.MockHttpServletRequest) -> org.springframework.mock.web.MockHttpServletRequest = { it.serverName = h; it }

    @Test
    fun `the same path answers differently on each domain - and a port in the Host does not matter`() {
        mvc.get("/api/v1/project-hello") { with(host("a.localhost")) }.andExpect { status { isOk() }; jsonPath("$.value.project") { value("a") } }
        mvc.get("/api/v1/project-hello") { with(host("b.localhost")) }.andExpect { status { isOk() }; jsonPath("$.value.project") { value("b") } }
        mvc.get("/api/v1/project-hello") { with { it.serverName = "A.localhost"; it.serverPort = 5173; it } }.andExpect { jsonPath("$.value.project") { value("a") } }
    }

    @Test
    fun `each domain has its own seed account and signs its own token`() {
        fun login(h: String, email: String) = mvc.post("/api/v1/auth/login") { with(host(h)); contentType = MediaType.APPLICATION_JSON; content = """{"email":"$email","password":"password"}""" }
        login("a.localhost", "user@a.localhost").andExpect { status { isOk() } }
        login("b.localhost", "user@a.localhost").andExpect { status { isUnauthorized() } }
        val token = JsonPath.read<String>(login("b.localhost", "user@b.localhost").andReturn().response.contentAsString, "$.value.accessToken")
        mvc.get("/api/v1/auth/me") { with(host("a.localhost")); header("Authorization", "Bearer $token") }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `CORS allows the origin of the project the request came to, not the other project's`() {
        fun preflight(h: String, origin: String) = mvc.options("/api/v1/project-hello") {
            with(host(h)); header("Origin", origin); header("Access-Control-Request-Method", "GET")
        }
        preflight("a.localhost", "http://a.localhost:5173").andExpect { status { isOk() }; header { string("Access-Control-Allow-Origin", "http://a.localhost:5173") } }
        preflight("a.localhost", "http://b.localhost:5173").andExpect { status { isForbidden() } }
    }
}
