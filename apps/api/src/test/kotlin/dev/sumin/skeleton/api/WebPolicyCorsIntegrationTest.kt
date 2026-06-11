package dev.sumin.skeleton.api

import dev.sumin.skeleton.TestcontainersConfiguration
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.options

@SpringBootTest(
    properties = [
        "skeleton.web.cors.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WebPolicyCorsIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `cors preflight is handled when cors is enabled`() {
        mockMvc.options("/api/v1/examples/items") {
            header("Origin", "http://localhost:5173")
            header("Access-Control-Request-Method", "GET")
            header(
                "Access-Control-Request-Headers",
                "Authorization, Idempotency-Key, X-Dev-Email, X-Break-Glass-Secret, traceparent",
            )
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "http://localhost:5173") }
            header { string("Access-Control-Allow-Methods", containsString("GET")) }
            header { string("Access-Control-Allow-Headers", containsString("Authorization")) }
            header { string("Access-Control-Allow-Headers", containsString("Idempotency-Key")) }
            header { string("Access-Control-Allow-Headers", containsString("X-Dev-Email")) }
            header { string("Access-Control-Allow-Headers", containsString("X-Break-Glass-Secret")) }
            header { string("Access-Control-Allow-Headers", containsString("traceparent")) }
        }
    }
}
