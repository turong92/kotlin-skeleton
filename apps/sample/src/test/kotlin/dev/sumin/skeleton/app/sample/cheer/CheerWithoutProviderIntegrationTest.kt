package dev.sumin.skeleton.app.sample.cheer

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.TestcontainersConfiguration
import dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 토스를 켜지 않은 기본 기동(키 없음, 결제사 빈 없음, 설정은 이 앱의 `application.yml` 그대로) — 앱은 예전처럼 뜨고, 결제 화면은 「지금은 결제할 수 없어요」로 갈 수 있게
 * `GET /payments/config` 가 `available: false` 를 말한다. 주문 만들기는 503 `PAYMENT.UNAVAILABLE`, 금액 목록은 그대로 나가고, 웹훅 경로는 받을 결제사가 없어 404.
 */
@SpringBootTest(properties = ["spring.config.import=classpath:test-seeds.yml"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, SampleIntegrationTest.FakeStorageConfiguration::class)
class CheerWithoutProviderIntegrationTest {
    @Autowired lateinit var mvc: MockMvc

    private fun token(): String = JsonPath.read(
        mvc.post("/api/v1/auth/login") { contentType = MediaType.APPLICATION_JSON; content = """{"email":"user@example.com","password":"password"}""" }.andReturn().response.contentAsString,
        "$.value.accessToken",
    )

    @Test
    fun `the app starts without any payment provider and says payments cannot be taken`() {
        val bearer = token()
        mvc.get("/api/v1/payments/config") { header("Authorization", "Bearer $bearer") }
            .andExpect { status { isOk() }; jsonPath("$.value.available") { value(false) }; jsonPath("$.value.testMode") { value(false) } }
        mvc.post("/api/v1/payments/orders") {
            header("Authorization", "Bearer $bearer"); header("Idempotency-Key", "k-1"); contentType = MediaType.APPLICATION_JSON
            content = """{"itemType":"cheer","itemRef":"cheer-3000","quantity":1}"""
        }.andExpect { status { isServiceUnavailable() }; jsonPath("$.code") { value("PAYMENT.UNAVAILABLE") } }
        val options = mvc.get("/api/v1/cheers/options") { header("Authorization", "Bearer $bearer") }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals(3, JsonPath.read<List<Any>>(options, "$.values").size)
        mvc.get("/api/v1/cheers/me") { header("Authorization", "Bearer $bearer") }.andExpect { status { isOk() } }
        mvc.post("/api/v1/payments/webhooks/toss/some-token-some-token-some-token") { contentType = MediaType.APPLICATION_JSON; content = "{}" }.andExpect { status { isNotFound() } }
    }
}
