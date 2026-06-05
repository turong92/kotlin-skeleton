package dev.sumin.skeleton.auth

import dev.sumin.skeleton.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(
    properties = [
        "spring.profiles.active=local",
        "skeleton.auth.dev-login.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class DevLoginIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `GET me with dev email resolves seed user`() {
        mockMvc.get("/api/v1/auth/me") {
            header("X-Dev-Email", "user@example.com")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.accountId") { value("acc_user") }
            jsonPath("$.username") { value("user") }
            jsonPath("$.email") { value("user@example.com") }
            jsonPath("$.roles[0]") { value("USER") }
        }
    }

    @Test
    fun `GET me with dev account id resolves seed admin roles`() {
        mockMvc.get("/api/v1/auth/me") {
            header("X-Dev-Account-Id", "acc_admin")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.accountId") { value("acc_admin") }
            jsonPath("$.roles") { value(org.hamcrest.Matchers.hasItem("ADMIN")) }
        }
    }

    @Test
    fun `GET me ignores arbitrary dev roles header`() {
        mockMvc.get("/api/v1/auth/me") {
            header("X-Dev-Email", "user@example.com")
            header("X-Dev-Roles", "ADMIN")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.accountId") { value("acc_user") }
            jsonPath("$.roles") { value(org.hamcrest.Matchers.contains("USER")) }
            jsonPath("$.roles") { value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("ADMIN"))) }
        }
    }
}

@SpringBootTest(
    properties = [
        "spring.profiles.active=local",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class DevLoginDisabledIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `GET me with dev header does not authenticate when dev login disabled`() {
        mockMvc.get("/api/v1/auth/me") {
            header("X-Dev-Email", "user@example.com")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(401) }
        }
    }
}
