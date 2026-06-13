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
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "skeleton.payment-toss.enabled=true",
        "skeleton.payment-toss.secret-key=test_sk_123",
        "skeleton.payment-stripe.enabled=true",
        "skeleton.payment-stripe.secret-key=sk_test_123",
        "skeleton.payment.providers.toss.currencies=KRW",
        "skeleton.payment.providers.toss.countries=KR",
        "skeleton.payment.providers.stripe.currencies=USD",
        "skeleton.payment.providers.stripe.countries=US",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SkeletonPaymentRoutingIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `payment route endpoint previews domestic and international provider routing`() {
        val token = loginAccessToken()

        mockMvc.get("/api/v1/skeleton/payments/route") {
            header("Authorization", "Bearer $token")
            param("amount", "1000")
            param("currency", "KRW")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.provider") { value("toss") }
            jsonPath("$.value.currency") { value("KRW") }
            jsonPath("$.value.amount") { value(1000) }
        }

        mockMvc.get("/api/v1/skeleton/payments/route") {
            header("Authorization", "Bearer $token")
            param("amount", "2500")
            param("currency", "USD")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.provider") { value("stripe") }
            jsonPath("$.value.currency") { value("USD") }
            jsonPath("$.value.amount") { value(2500) }
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
