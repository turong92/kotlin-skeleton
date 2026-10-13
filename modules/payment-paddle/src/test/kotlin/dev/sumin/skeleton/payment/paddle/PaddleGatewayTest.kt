package dev.sumin.skeleton.payment.paddle

import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.HostedSubscriptionRequest
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaddleGatewayTest {
    @Test
    fun `sandbox is TEST mode and the checkout carries the price and our ids as custom data`() {
        val gateway = gateway()
        assertThat(gateway.mode).isEqualTo(PaymentMode.TEST)
        val checkout = gateway.checkout(CheckoutOrder("ord_1", "Cheer", PaymentAmount(1000, "KRW"), "cheer", "acc_1"))
        assertThat(checkout.kind).isEqualTo("paddle")
        assertThat(checkout.clientKey).isEqualTo("test_clienttoken")
        assertThat(checkout.attributes["priceId"]).isEqualTo("pri_cheer")
        assertThat(checkout.attributes["customData"]).contains("\"orderId\":\"ord_1\"").contains("\"accountId\":\"acc_1\"")
    }

    @Test
    fun `subscription setup needs a price for the plan`() {
        val request = HostedSubscriptionRequest("basic", "acc_1", "sub_ours", "cus_x", "US")
        val setup = gateway().subscriptionSetup(request)!!
        assertThat(setup.attributes["priceId"]).isEqualTo("pri_basic")
        assertThat(setup.attributes["customData"]).contains("\"subscriptionId\":\"sub_ours\"")
        assertThat(gateway().subscriptionSetup(request.copy(planCode = "unknown"))).isNull()
    }

    @Test
    fun `cancel at period end posts effective_from next_billing_period with the API key`() {
        FakePaddleApi().use { api ->
            gateway(api.baseUrl).cancel("sub_1", atPeriodEnd = true)
            val request = api.requests.single()
            assertThat(request.path).isEqualTo("/subscriptions/sub_1/cancel")
            assertThat(request.auth).isEqualTo("Bearer pdl_sdbx_apikey_x")
            assertThat(request.body).contains("\"effective_from\":\"next_billing_period\"")
        }
    }

    @Test
    fun `the default base url follows the environment`() {
        assertThat(properties().resolvedBaseUrl).isEqualTo("https://sandbox-api.paddle.com")
        assertThat(properties().copy(environment = "live").resolvedBaseUrl).isEqualTo("https://api.paddle.com")
    }
}
