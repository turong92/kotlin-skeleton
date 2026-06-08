package dev.sumin.skeleton.api

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
        "skeleton.web.rate-limit.enabled=true",
        "skeleton.web.rate-limit.capacity=1",
        "skeleton.web.rate-limit.window=1m",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WebPolicyRateLimitIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `rate limit rejects over limit requests with ApiError`() {
        mockMvc.get("/api/v1/hello") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            header { string("X-RateLimit-Limit", "1") }
            header { string("X-RateLimit-Remaining", "0") }
            header { exists("X-RateLimit-Reset") }
        }

        mockMvc.get("/api/v1/hello") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isTooManyRequests() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            header { string("X-RateLimit-Limit", "1") }
            header { string("X-RateLimit-Remaining", "0") }
            header { exists("X-RateLimit-Reset") }
            header { exists("Retry-After") }
            jsonPath("$.status") { value(429) }
            jsonPath("$.title") { value("Too many requests") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
        }
    }
}
