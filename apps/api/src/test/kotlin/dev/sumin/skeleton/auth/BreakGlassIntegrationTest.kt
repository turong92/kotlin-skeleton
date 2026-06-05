package dev.sumin.skeleton.auth

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest(
    properties = [
        "spring.profiles.active=prod",
        "skeleton.auth.break-glass.enabled=true",
        "skeleton.auth.break-glass.secret=test-break-glass-secret",
        "skeleton.auth.break-glass.allowed-account-ids[0]=acc_admin",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class BreakGlassIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `GET me with break glass headers authenticates allowed admin account`() {
        mockMvc.get("/api/v1/auth/me") {
            header("X-Break-Glass-Secret", "test-break-glass-secret")
            header("X-Break-Glass-Reason", "production support")
            header("X-Break-Glass-Account-Id", "acc_admin")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.accountId") { value("acc_admin") }
            jsonPath("$.username") { value("admin") }
            jsonPath("$.email") { value("admin@example.com") }
            jsonPath("$.roles") { value(hasItem("ADMIN")) }
        }
    }

    @Test
    fun `GET me with missing break glass reason returns unauthorized ApiError`() {
        mockMvc.get("/api/v1/auth/me") {
            header("X-Break-Glass-Secret", "test-break-glass-secret")
            header("X-Break-Glass-Account-Id", "acc_admin")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(401) }
            jsonPath("$.title") { value("Unauthorized") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
            jsonPath("$.timestamp") { isNotEmpty() }
        }
    }

    @Test
    fun `GET me with wrong break glass secret returns unauthorized ApiError`() {
        mockMvc.get("/api/v1/auth/me") {
            header("X-Break-Glass-Secret", "wrong-secret")
            header("X-Break-Glass-Reason", "production support")
            header("X-Break-Glass-Account-Id", "acc_admin")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(401) }
            jsonPath("$.title") { value("Unauthorized") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
            jsonPath("$.timestamp") { isNotEmpty() }
        }
    }

    @Test
    fun `GET me with account outside break glass allowlist returns forbidden ApiError`() {
        mockMvc.get("/api/v1/auth/me") {
            header("X-Break-Glass-Secret", "test-break-glass-secret")
            header("X-Break-Glass-Reason", "production support")
            header("X-Break-Glass-Account-Id", "acc_user")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isForbidden() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(403) }
            jsonPath("$.title") { value("Forbidden") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
            jsonPath("$.timestamp") { isNotEmpty() }
        }
    }

    @Test
    fun `GET me ignores arbitrary break glass roles header`() {
        mockMvc.get("/api/v1/auth/me") {
            header("X-Break-Glass-Secret", "test-break-glass-secret")
            header("X-Break-Glass-Reason", "production support")
            header("X-Break-Glass-Account-Id", "acc_admin")
            header("X-Break-Glass-Roles", "SUPERUSER")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.accountId") { value("acc_admin") }
            jsonPath("$.roles") { value(contains("USER", "ADMIN")) }
            jsonPath("$.roles") { value(not(hasItem("SUPERUSER"))) }
        }
    }

    @Test
    fun `GET me with break glass headers and bearer token keeps break glass principal`() {
        val loginResponse = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        val accessToken = JsonPath.read<String>(loginResponse, "$.accessToken")

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $accessToken")
            header("X-Break-Glass-Secret", "test-break-glass-secret")
            header("X-Break-Glass-Reason", "production support")
            header("X-Break-Glass-Account-Id", "acc_admin")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.accountId") { value("acc_admin") }
            jsonPath("$.username") { value("admin") }
            jsonPath("$.roles") { value(hasItem("ADMIN")) }
        }
    }
}
