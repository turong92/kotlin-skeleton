package dev.sumin.skeleton.app.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest(properties = ["spring.config.import=classpath:test-seeds.yml"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AuthLoginIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `login with the seed user issues a bearer token that opens auth me`() {
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.accessToken") { isNotEmpty() }
            jsonPath("$.value.tokenType") { value("Bearer") }
        }.andReturn().response.contentAsString
        val token = com.jayway.jsonpath.JsonPath.read<String>(login, "$.value.accessToken")

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.accountId") { value("acc_user") }
        }
    }

    @Test
    fun `protected routes reject anonymous requests`() {
        mockMvc.get("/api/v1/auth/me").andExpect {
            status { isUnauthorized() }
        }
    }
}
