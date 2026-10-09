package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 요청 수 한도(플랫폼의 `RateLimitStore` 위에, 호출자 IP 마다) — 주소를 확인하기 **전에** 센다: 비밀 조각을 짐작하는 쪽이 먼저 막힌다. 기본 경로를 바꿔도 따라간다. */
@SpringBootTest(
    classes = [PaymentWebTestApplication::class],
    properties = [
        "skeleton.payment.webhook.path-token=web-test-token-0123456789abcdef",
        "skeleton.payment.webhook.rate-limit.capacity=3",
        "skeleton.payment.webhook.rate-limit.window=10m",
        "skeleton.payment.http.base-path=/pay-hooks",
    ],
)
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class)
class PaymentWebhookRateLimitWebTest {
    @Autowired lateinit var mvc: MockMvc

    private fun send(path: String) = mvc.perform(post(path).contentType(MediaType.TEXT_PLAIN).content("eventType=DEPOSIT_CALLBACK\n"))

    @Test
    fun `past the limit the same caller gets 429 with a retry hint - even when it guesses a wrong token`() {
        val good = "/pay-hooks/webhooks/scripted/web-test-token-0123456789abcdef"
        repeat(2) { send(good).andExpect(status().isOk) }
        send("/pay-hooks/webhooks/scripted/wrong-token-0123456789").andExpect(status().isNotFound) // 셋째 요청: 아직 한도 안

        send(good).andExpect(status().isTooManyRequests).andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.code").value("COMMON.TOO_MANY_REQUESTS"))
        send("/pay-hooks/webhooks/scripted/wrong-token-0123456789").andExpect(status().isTooManyRequests)
    }
}
