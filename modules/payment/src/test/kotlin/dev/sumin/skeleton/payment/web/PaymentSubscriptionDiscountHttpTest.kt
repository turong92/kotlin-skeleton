package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.discount.DiscountSource
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.testing.FakeCouponSource
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asUser
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.read
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import dev.sumin.skeleton.paymenttest.SubscriptionBeans
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** C-P 구독: 시작 본문의 `discounts`, 구독 응답의 `discount`, 오류 코드. */
@SpringBootTest(
    classes = [PaymentWebTestApplication::class],
    properties = [
        "skeleton.payment.subscription.enabled=true",
        "skeleton.payment.http.order-rate-limit.account-capacity=100000",
        "skeleton.payment.http.order-rate-limit.ip-capacity=100000",
    ],
)
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class, SubscriptionBeans::class, PaymentSubscriptionDiscountHttpTest.Coupons::class)
class PaymentSubscriptionDiscountHttpTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Coupons {
        @Bean fun coupons(): DiscountSource = FakeCouponSource().apply {
            add("free1half3", listOf(DiscountStage(DiscountStageType.FREE, 0, null, 1), DiscountStage(DiscountStageType.PERCENT, 50, null, 3)), "1 free then half")
            add("forever10", DiscountStage(DiscountStageType.PERCENT, 10), "10% forever")
        }
    }

    @Autowired lateinit var mvc: MockMvc

    private val base = "/api/v1/payments"
    private var n = 0
    private fun account() = "acc_subd_${System.nanoTime()}_${n++}"

    private fun start(account: String, coupon: String?) = mvc.perform(
        post("$base/subscriptions").asUser(account).header("Idempotency-Key", "k-${System.nanoTime()}").contentType(MediaType.APPLICATION_JSON)
            .content("""{"planCode":"basic"${coupon?.let { ""","discounts":[{"type":"coupon","ref":"$it"}]""" } ?: ""}}"""),
    )

    private fun activate(account: String, id: String) = mvc.perform(post("$base/subscriptions/$id/activate").asUser(account).contentType(MediaType.APPLICATION_JSON).content("""{"providerPayload":{"authKey":"auth_1"}}"""))

    @Test
    fun `a free first period is ACTIVE after the card, and me shows the next amount, the stages and the remaining periods`() {
        val account = account()
        val id = read(start(account, "free1half3").andExpect(status().isCreated).andReturn().response.contentAsString)["value"]["subscription"]["id"].asString()

        activate(account, id).andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("ACTIVE"))
            .andExpect(jsonPath("$.value.discount.label").value("1 free then half"))
            .andExpect(jsonPath("$.value.discount.periodsUsed").value(1))
            .andExpect(jsonPath("$.value.discount.nextAmount").value(1950))
            .andExpect(jsonPath("$.value.discount.remainingPeriods").value(3))
            .andExpect(jsonPath("$.value.discount.stages[0].type").value("FREE"))
            .andExpect(jsonPath("$.value.discount.stages[1].type").value("PERCENT"))
            .andExpect(jsonPath("$.value.discount.stages[1].value").value(50))
        mvc.perform(get("$base/subscriptions/me").asUser(account)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.subscription.discount.nextAmount").value(1950))
            .andExpect(jsonPath("$.value.orders[0].settlement").value("FREE"))
            .andExpect(jsonPath("$.value.orders[0].originalAmount").value(3900))
            .andExpect(jsonPath("$.value.subscription.amount.amount").value(3900))
    }

    @Test
    fun `a forever discount has a null remaining count, and a plain subscription has a null discount`() {
        val account = account()
        val id = read(start(account, "forever10").andExpect(status().isCreated).andReturn().response.contentAsString)["value"]["subscription"]["id"].asString()
        activate(account, id).andExpect(jsonPath("$.value.discount.remainingPeriods").value(null as Any?)).andExpect(jsonPath("$.value.discount.nextAmount").value(3510))

        val other = account()
        start(other, null).andExpect(status().isCreated).andExpect(jsonPath("$.value.subscription.discount").value(null as Any?))
    }

    @Test
    fun `a coupon that does not exist is 400 DISCOUNT_NOT_APPLICABLE`() {
        start(account(), "ghost").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PAYMENT.DISCOUNT_NOT_APPLICABLE"))
    }
}
