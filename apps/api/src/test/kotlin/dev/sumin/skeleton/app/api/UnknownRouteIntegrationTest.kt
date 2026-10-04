package dev.sumin.skeleton.app.api

import dev.sumin.skeleton.common.TraceIdFilter
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
class UnknownRouteIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `unknown route returns the standard 404 ApiError with the caller's trace id`() {
        val traceId = "0123456789abcdef0123456789abcdef"
        val token = com.jayway.jsonpath.JsonPath.read<String>(
            mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"user@example.com","password":"password"}"""
            }.andReturn().response.contentAsString,
            "$.value.accessToken",
        )

        mockMvc.get("/api/v1/does-not-exist") {
            header(TraceIdFilter.HEADER_TRACEPARENT, "00-$traceId-abcdef0123456789-01")
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.status") { value(404) }
            jsonPath("$.code") { value("COMMON.NOT_FOUND") }
            jsonPath("$.traceId") { value(traceId) }
            jsonPath("$.timestamp") { isNotEmpty() }
        }
    }
}
