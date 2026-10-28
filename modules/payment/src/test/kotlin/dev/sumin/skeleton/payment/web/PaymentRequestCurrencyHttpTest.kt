package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.SubscriptionPlan
import dev.sumin.skeleton.payment.SubscriptionPlans
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asUser
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/** 이 요청의 통화: `GET /config` 가 알리고, 플랜 목록은 그 통화의 플랜만 낸다(그 통화 플랜이 없으면 전부). 플랜 응답은 상품 코드 · 정가를 싣는다. */
@SpringBootTest(
    classes = [PaymentWebTestApplication::class],
    properties = [
        "skeleton.payment.subscription.enabled=true",
        "skeleton.payment.routing.country-providers.KR=toss",
        "skeleton.payment.routing.default-currency=USD",
        "skeleton.web.client-ip.mode=cloudflare",
        "skeleton.web.client-ip.trusted-proxies=127.0.0.1/32",
    ],
)
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class, PaymentRequestCurrencyHttpTest.MixedPlans::class)
class PaymentRequestCurrencyHttpTest {
    @TestConfiguration(proxyBeanMethods = false)
    class MixedPlans {
        @Bean fun subscriptionPlans(): SubscriptionPlans = object : SubscriptionPlans {
            override fun list() = listOf(
                SubscriptionPlan("basic", "Basic", PaymentAmount(3_900, "KRW"), "P1M", 1, productCode = "basic", regularAmount = 4_900),
                SubscriptionPlan("basic-yearly", "Basic yearly", PaymentAmount(39_000, "KRW"), "P1Y", 1, productCode = "basic"),
                SubscriptionPlan("basic-usd", "Basic", PaymentAmount(300, "USD"), "P1M", 1, productCode = "basic"),
            )
        }

        @Bean fun textEncryptor(): dev.sumin.skeleton.crypto.TextEncryptor = dev.sumin.skeleton.payment.testing.ReversibleTestEncryptor
    }

    @Autowired lateinit var mvc: MockMvc

    private val base = "/api/v1/payments"

    @Test
    fun `a request routed to a KRW provider sees KRW plans only, and the plan lines carry the product code and the regular amount`() {
        mvc.perform(get("$base/config").asUser("acc_cur_1").header("CF-IPCountry", "US")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.currency").value("KRW"))
        mvc.perform(get("$base/subscriptions/plans").asUser("acc_cur_1").header("CF-IPCountry", "US")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.plans.length()").value(2))
            .andExpect(jsonPath("$.value.plans[0].code").value("basic"))
            .andExpect(jsonPath("$.value.plans[0].productCode").value("basic"))
            .andExpect(jsonPath("$.value.plans[0].regularAmount").value(4_900))
            .andExpect(jsonPath("$.value.plans[1].code").value("basic-yearly"))
            .andExpect(jsonPath("$.value.plans[1].regularAmount").value(null as Any?))
    }

    @Test
    fun `a request with no provider takes the default currency, and the plans follow`() {
        mvc.perform(get("$base/config").asUser("acc_cur_2").header("CF-IPCountry", "KR")).andExpect(jsonPath("$.value.currency").value("USD"))
        mvc.perform(get("$base/subscriptions/plans").asUser("acc_cur_2").header("CF-IPCountry", "KR")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.plans.length()").value(1))
            .andExpect(jsonPath("$.value.plans[0].code").value("basic-usd"))
    }
}
