package dev.sumin.skeleton.payment.iap

import dev.sumin.skeleton.iaptest.IapLedgerBeans
import dev.sumin.skeleton.iaptest.IapWebTestApplication
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 구매 확인의 요청 수 한도 - 주문 만들기와 같은 `skeleton.payment.http.order-rate-limit` 이다(스토어 API 할당량을 지킨다). 넘으면 429 와 Retry-After. */
@SpringBootTest(
    classes = [IapWebTestApplication::class],
    properties = [
        "skeleton.payment-iap.enabled=true",
        "skeleton.payment-iap.products[0].item-type=cheer",
        "skeleton.payment-iap.products[0].item-ref=cheer-3000",
        "skeleton.payment-iap.products[0].ios-product-id=com.example.cheer3000",
        "skeleton.payment.http.order-rate-limit.account-capacity=2",
        "skeleton.payment.http.order-rate-limit.ip-capacity=100",
    ],
)
@AutoConfigureMockMvc
@Import(IapLedgerBeans::class)
class IapRateLimitHttpTest {
    @Autowired lateinit var mvc: MockMvc

    private fun confirm(account: String) = mvc.perform(
        post("/api/v1/payments/iap/appstore/purchases").principal(UsernamePasswordAuthenticationToken(account, "n/a", emptyList())).contentType(MediaType.APPLICATION_JSON).content("""{"transactionId":"404"}"""),
    )

    @Test
    fun `an account past its limit gets 429 with Retry-After before the store is asked, and another account is not affected`() {
        val account = "acc_iap_rl_${System.nanoTime()}"

        confirm(account).andExpect(status().isNotFound)
        confirm(account).andExpect(status().isNotFound)
        confirm(account).andExpect(status().isTooManyRequests).andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.code").value("COMMON.TOO_MANY_REQUESTS"))

        confirm("acc_iap_rl_other_${System.nanoTime()}").andExpect(status().isNotFound)
    }
}
