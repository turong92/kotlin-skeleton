package dev.sumin.skeleton.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WebPolicyIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `sample public endpoint remains permit all through public endpoint policy`() {
        mockMvc.get("/api/v1/hello") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.message") { value("Hello from Kotlin backend!") }
        }
    }

    @Test
    fun `non public endpoint still requires authentication`() {
        mockMvc.get("/api/v1/examples/items") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.status") { value(401) }
        }
    }

    @Test
    fun `forwarded headers affect created Location`() {
        mockMvc.post("/api/v1/examples/items") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            header("Idempotency-Key", "web-policy-forwarded-location")
            header("X-Forwarded-Proto", "https")
            header("X-Forwarded-Host", "api.example.com")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"name":"sample"}"""
        }.andExpect {
            status { isCreated() }
            header { string("Location", "https://api.example.com/api/v1/examples/items/item-1") }
        }
    }

    @Test
    fun `standard security headers are attached`() {
        mockMvc.get("/api/v1/hello") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            header { string("X-Content-Type-Options", "nosniff") }
            header { string("X-Frame-Options", "DENY") }
            header { string("Referrer-Policy", "no-referrer") }
            header { exists("Permissions-Policy") }
        }
    }

    private fun loginAccessToken(): String {
        val response = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        return JsonPath.read(response, "$.value.accessToken")
    }
}
