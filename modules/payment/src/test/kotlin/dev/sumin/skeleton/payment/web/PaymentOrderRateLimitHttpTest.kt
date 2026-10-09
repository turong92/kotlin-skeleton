package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asUser
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
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
import kotlin.test.Test

/** 주문 만들기의 요청 수 한도(계정당 · IP 당) — 넘으면 429 와 Retry-After. 계정당 주문 수가 웹훅 조회 유발의 전체 상한을 정한다. */
@SpringBootTest(
    classes = [PaymentWebTestApplication::class],
    properties = ["skeleton.payment.http.order-rate-limit.account-capacity=2", "skeleton.payment.http.order-rate-limit.ip-capacity=100"],
)
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class)
class PaymentOrderRateLimitHttpTest {
    @Autowired lateinit var mvc: MockMvc

    private fun create(account: String, n: Int) =
        mvc.perform(post("/api/v1/payments/orders").asUser(account).header("Idempotency-Key", "rl-$account-$n").contentType(MediaType.APPLICATION_JSON).content("""{"itemType":"cheer","itemRef":"cheer-3000"}"""))

    @Test
    fun `an account past its order limit gets 429 with Retry-After, and another account is not affected`() {
        val account = "acc_rl_${System.nanoTime()}"

        create(account, 1).andExpect(status().isCreated)
        create(account, 2).andExpect(status().isCreated)
        create(account, 3).andExpect(status().isTooManyRequests).andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.data.retryAfterSeconds").exists()).andExpect(jsonPath("$.code").value("COMMON.TOO_MANY_REQUESTS"))

        create("acc_rl_other_${System.nanoTime()}", 1).andExpect(status().isCreated)
    }
}
