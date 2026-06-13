package dev.sumin.skeleton.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class JsonContractIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `json document request and response preserve raw json shape`() {
        val token = loginAccessToken()

        mockMvc.post("/api/v1/skeleton/json/echo") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"z":2,"a":{"name":"sample","enabled":true}}"""
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.value.a.name") { value("sample") }
            jsonPath("$.value.a.enabled") { value(true) }
            jsonPath("$.value.z") { value(2) }
            jsonPath("$.value.node") { doesNotExist() }
            jsonPath("$.meta.traceId") { isNotEmpty() }
        }
    }

    @Test
    fun `versioned json document response exposes type version and raw payload`() {
        val token = loginAccessToken()

        mockMvc.get("/api/v1/skeleton/json/versioned") {
            header("Authorization", "Bearer $token")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.value.type") { value("skeleton.sample-json") }
            jsonPath("$.value.version") { value(1) }
            jsonPath("$.value.payload.name") { value("sample") }
            jsonPath("$.value.metadata.source") { value("workbench") }
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

        val token = JsonPath.read<String>(response, "$.value.accessToken")
        assertTrue(token.isNotBlank())
        return token
    }
}
