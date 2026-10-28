package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.discount.DiscountSource
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.testing.FakeCouponSource
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asUser
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/** C-P: 견적 · 주문 응답의 할인 칸, 정산 방식, 면제 권한, 오류 코드. 할인 수단 빈 하나(쿠폰 대역)와 `payment.exempt` 를 가진 역할 하나를 세운다. */
@SpringBootTest(
    classes = [PaymentWebTestApplication::class],
    properties = [
        "skeleton.payment.http.order-rate-limit.account-capacity=100000",
        "skeleton.payment.http.order-rate-limit.ip-capacity=100000",
        "skeleton.authz.roles.FREEBIE=payment.exempt",
    ],
)
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class, PaymentDiscountHttpTest.Coupons::class)
class PaymentDiscountHttpTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Coupons {
        @Bean fun coupons(): DiscountSource = FakeCouponSource().apply {
            add("c30", DiscountStage(DiscountStageType.PERCENT, 30), "30% off")
            add("full", DiscountStage(DiscountStageType.PERCENT, 100), "Free")
            add("held", DiscountStage(DiscountStageType.PERCENT, 10), "Held")
            heldBy("held", "ord_someone")
        }
    }

    @Autowired lateinit var mvc: MockMvc

    private val base = "/api/v1/payments"
    private var n = 0
    private fun account() = "acc_disc_${System.nanoTime()}_${n++}"

    private fun quote(account: String, coupon: String?, vararg roles: String): ResultActions =
        mvc.perform(
            post("$base/quote").asUser(account, *roles).contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemType":"cheer","itemRef":"cheer-5000"${coupon?.let { ""","discounts":[{"type":"coupon","ref":"$it"}]""" } ?: ""}}"""),
        )

    private fun order(account: String, coupon: String?, key: String = "k-${System.nanoTime()}", vararg roles: String): ResultActions =
        mvc.perform(
            post("$base/orders").asUser(account, *roles).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemType":"cheer","itemRef":"cheer-5000"${coupon?.let { ""","discounts":[{"type":"coupon","ref":"$it"}]""" } ?: ""}}"""),
        )

    @Test
    fun `a quote shows the original, the discounted amount, the lines and the settlement - and an order has the same numbers`() {
        quote(account(), "c30").andExpect(status().isOk)
            .andExpect(jsonPath("$.value.originalAmount").value(5000))
            .andExpect(jsonPath("$.value.amount").value(3500))
            .andExpect(jsonPath("$.value.currency").value("KRW"))
            .andExpect(jsonPath("$.value.discounts[0].type").value("coupon"))
            .andExpect(jsonPath("$.value.discounts[0].ref").value("c30"))
            .andExpect(jsonPath("$.value.discounts[0].label").value("30% off"))
            .andExpect(jsonPath("$.value.discounts[0].amount").value(1500))
            .andExpect(jsonPath("$.value.settlement").value("PROVIDER"))

        order(account(), "c30").andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.order.amount.amount").value(3500))
            .andExpect(jsonPath("$.value.order.originalAmount").value(5000))
            .andExpect(jsonPath("$.value.order.discountAmount").value(1500))
            .andExpect(jsonPath("$.value.order.discounts[0].amount").value(1500))
            .andExpect(jsonPath("$.value.order.settlement").value("PROVIDER"))
            .andExpect(jsonPath("$.value.checkout.amount.amount").value(3500))
    }

    @Test
    fun `a plain order has null original, zero discount, no lines and settlement PROVIDER`() {
        order(account(), null).andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.order.originalAmount").doesNotExist())
            .andExpect(jsonPath("$.value.order.discountAmount").value(0))
            .andExpect(jsonPath("$.value.order.discounts").isEmpty)
            .andExpect(jsonPath("$.value.order.settlement").value("PROVIDER"))
    }

    @Test
    fun `a free coupon makes a PAID order with no checkout and nothing to refund`() {
        val account = account()
        val body = order(account, "full").andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.order.status").value("PAID"))
            .andExpect(jsonPath("$.value.order.settlement").value("FREE"))
            .andExpect(jsonPath("$.value.order.refundableAmount").value(0))
            .andExpect(jsonPath("$.value.checkout").doesNotExist())
            .andReturn().response.contentAsString
        val id = PaymentHttpSupport.read(body)["value"]["order"]["id"].asString()

        mvc.perform(post("$base/orders/$id/refund-requests").asUser(account).header("Idempotency-Key", "r1").contentType(MediaType.APPLICATION_JSON).content("""{"reasonCode":"CUSTOMER_REQUEST"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.NOTHING_TO_REFUND"))
    }

    @Test
    fun `a caller with payment exempt gets an EXEMPT order and a quote that says so - without the role the same call opens a checkout`() {
        quote(account(), null, "FREEBIE").andExpect(jsonPath("$.value.settlement").value("EXEMPT")).andExpect(jsonPath("$.value.amount").value(0)).andExpect(jsonPath("$.value.originalAmount").value(5000))
        order(account(), "c30", roles = arrayOf("FREEBIE")).andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.order.status").value("PAID"))
            .andExpect(jsonPath("$.value.order.settlement").value("EXEMPT"))
            .andExpect(jsonPath("$.value.order.originalAmount").value(5000))
            .andExpect(jsonPath("$.value.order.discountAmount").value(0))
            .andExpect(jsonPath("$.value.checkout").doesNotExist())
        order(account(), null).andExpect(status().isCreated).andExpect(jsonPath("$.value.order.status").value("PENDING"))
    }

    @Test
    fun `the discount errors carry the contract codes and statuses`() {
        order(account(), "ghost").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PAYMENT.DISCOUNT_NOT_APPLICABLE"))
        order(account(), "held").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.DISCOUNT_UNAVAILABLE"))
        quote(account(), "held").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT.DISCOUNT_UNAVAILABLE"))
    }

    @Test
    fun `anonymous callers cannot quote`() {
        mvc.perform(post("$base/quote").contentType(MediaType.APPLICATION_JSON).content("""{"itemType":"cheer","itemRef":"cheer-5000"}""")).andExpect(status().isUnauthorized)
    }
}
