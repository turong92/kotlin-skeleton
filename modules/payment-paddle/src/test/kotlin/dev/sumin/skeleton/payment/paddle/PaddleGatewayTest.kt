package dev.sumin.skeleton.payment.paddle

import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.HostedSubscriptionRequest
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.TransactionRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PaddleGatewayTest {
    @Test
    fun `the transaction is created by the server with the price and quantity it chose and its own ids`() {
        FakePaddleApi().use { api ->
            api.responseBody = """{"data":{"id":"txn_abc","status":"draft"}}"""
            val id = gateway(api.baseUrl).createTransaction(TransactionRequest("cheer", 2, mapOf("orderId" to "ord_1", "accountId" to "acc_1")))
            assertThat(id).isEqualTo("txn_abc")
            val request = api.requests.single()
            assertThat(request.path).isEqualTo("/transactions")
            assertThat(request.auth).isEqualTo("Bearer pdl_sdbx_apikey_x")
            assertThat(request.body).contains("\"price_id\":\"pri_cheer\"").contains("\"quantity\":2").contains("\"orderId\":\"ord_1\"")
        }
    }

    @Test
    fun `no transaction is created for a product without a configured price, and a refusal throws`() {
        FakePaddleApi().use { api ->
            assertThatThrownBy { gateway(api.baseUrl).createTransaction(TransactionRequest("unknown", 1, emptyMap())) }.isInstanceOf(IllegalStateException::class.java)
            assertThat(api.requests).isEmpty()
            api.status = 403
            assertThatThrownBy { gateway(api.baseUrl).createTransaction(TransactionRequest("cheer", 1, emptyMap())) }.isInstanceOf(Exception::class.java)
        }
    }

    @Test
    fun `the price preview setup carries the public client token, the environment and only the configured price ids`() {
        val setup = gateway(props = properties(prices = mapOf("basic" to "pri_basic", "pro" to " "))).pricePreview()!!
        assertThat(setup.clientToken).isEqualTo("test_clienttoken")
        assertThat(setup.environment).isEqualTo("sandbox")
        assertThat(setup.prices).isEqualTo(mapOf("basic" to "pri_basic"))
        assertThat(gateway(props = properties().copy(clientToken = "")).pricePreview()).isNull()
    }

    @Test
    fun `the checkout carries only the transaction id - no price and no custom data from the browser`() {
        val gateway = gateway()
        assertThat(gateway.mode).isEqualTo(PaymentMode.TEST)
        val checkout = gateway.checkout(CheckoutOrder("ord_1", "Cheer", PaymentAmount(1000, "KRW"), "cheer", "acc_1", "txn_1"))
        assertThat(checkout.kind).isEqualTo("PADDLE_CHECKOUT")
        assertThat(checkout.clientKey).isEqualTo("test_clienttoken")
        assertThat(checkout.attributes).containsOnlyKeys("environment", "transactionId")
        assertThat(checkout.attributes["transactionId"]).isEqualTo("txn_1")
        val setup = gateway.subscriptionSetup(HostedSubscriptionRequest("basic", "acc_1", "sub_ours", "cus_x", "US", "txn_2"))!!
        assertThat(setup.attributes["transactionId"]).isEqualTo("txn_2")
        assertThat(gateway.subscriptionSetup(HostedSubscriptionRequest("basic", "acc_1", "sub_ours", "cus_x", "US", null))).isNull()
    }

    @Test
    fun `cancel at period end posts effective_from next_billing_period with the API key, and a refusal is a failure`() {
        FakePaddleApi().use { api ->
            gateway(api.baseUrl).cancel("sub_1", atPeriodEnd = true)
            val request = api.requests.single()
            assertThat(request.path).isEqualTo("/subscriptions/sub_1/cancel")
            assertThat(request.auth).isEqualTo("Bearer pdl_sdbx_apikey_x")
            assertThat(request.body).contains("\"effective_from\":\"next_billing_period\"")
            api.status = 400
            api.responseBody = """{"error":{"code":"subscription_update_when_canceled"}}"""
            assertThatThrownBy { gateway(api.baseUrl).cancel("sub_1", atPeriodEnd = false) }.isInstanceOf(Exception::class.java)
        }
    }

    @Test
    fun `the default base url follows the environment`() {
        assertThat(properties().resolvedBaseUrl).isEqualTo("https://sandbox-api.paddle.com")
        assertThat(properties().copy(environment = "live").resolvedBaseUrl).isEqualTo("https://api.paddle.com")
    }
}
