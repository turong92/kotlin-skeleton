package dev.sumin.skeleton.payment.paddle

import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.HostedSubscriptionRequest
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentUnavailableException
import dev.sumin.skeleton.payment.ProviderPriceRefs
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
            assertThatThrownBy { gateway(api.baseUrl).createTransaction(TransactionRequest("unknown", 1, emptyMap())) }.isInstanceOf(PaymentUnavailableException::class.java)
            assertThat(api.requests).isEmpty()
            api.status = 403
            assertThatThrownBy { gateway(api.baseUrl).createTransaction(TransactionRequest("cheer", 1, emptyMap())) }.isInstanceOf(Exception::class.java)
        }
    }

    private fun refs(vararg links: Pair<String, String>) = object : ProviderPriceRefs {
        override fun priceIdOf(provider: String, itemRef: String): String? = links.toMap()[itemRef]?.takeIf { provider == "paddle" }

        override fun all(provider: String): Map<String, String> = if (provider == "paddle") links.toMap() else emptyMap()
    }

    @Test
    fun `a price link beats the configured map, the map is the fallback, and a price in neither is not sold`() {
        FakePaddleApi().use { api ->
            api.responseBody = """{"data":{"id":"txn_abc","status":"draft"}}"""
            val gateway = gateway(api.baseUrl, properties(api.baseUrl, prices = mapOf("basic" to "pri_yml", "cheer" to "pri_cheer")), refs("basic" to "pri_db", "basic-usd" to "pri_usd"))

            assertThat(gateway.priceIdOf("basic")).isEqualTo("pri_db")
            assertThat(gateway.priceIdOf("cheer")).isEqualTo("pri_cheer")
            assertThat(gateway.priceIdOf("basic-usd")).isEqualTo("pri_usd")
            assertThat(gateway.priceIdOf("unknown")).isNull()
            gateway.createTransaction(TransactionRequest("basic", 1, emptyMap()))
            assertThat(api.requests.single().body).contains("\"price_id\":\"pri_db\"")
            assertThatThrownBy { gateway.createTransaction(TransactionRequest("unknown", 1, emptyMap())) }.isInstanceOf(PaymentUnavailableException::class.java)
            assertThat(api.requests).hasSize(1)
        }
    }

    @Test
    fun `the price preview carries the links over the configured map, links first`() {
        val setup = gateway(props = properties(prices = mapOf("basic" to "pri_yml", "cheer" to "pri_cheer")), refs = refs("basic" to "pri_db", "basic-usd" to "pri_usd")).pricePreview()!!

        assertThat(setup.prices).isEqualTo(mapOf("basic" to "pri_db", "cheer" to "pri_cheer", "basic-usd" to "pri_usd"))
    }

    @Test
    fun `the buyer's address country is read from the address with the API key`() {
        FakePaddleApi().use { api ->
            api.responseBody = """{"data":{"id":"add_1","country_code":"kr"}}"""
            assertThat(gateway(api.baseUrl).buyerCountry("ctm_1", "add_1")).isEqualTo("KR")
            val request = api.requests.single()
            assertThat(request.method).isEqualTo("GET")
            assertThat(request.path).isEqualTo("/customers/ctm_1/addresses/add_1")
            assertThat(request.auth).isEqualTo("Bearer pdl_sdbx_apikey_x")
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
