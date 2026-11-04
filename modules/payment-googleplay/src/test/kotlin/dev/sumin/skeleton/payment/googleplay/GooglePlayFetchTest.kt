package dev.sumin.skeleton.payment.googleplay

import dev.sumin.skeleton.common.http.ExternalHttpException
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PushedSubscriptionStatus
import dev.sumin.skeleton.payment.StoreClaim
import dev.sumin.skeleton.payment.StorePurchaseKind
import dev.sumin.skeleton.payment.StorePurchaseState
import java.security.MessageDigest
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

internal fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

class GooglePlayFetchTest {
    private val account = TestServiceAccount()
    private val server = FakePlayServer(account)
    private val gateway = provider(server, account)
    private val token = "tok.ONE-time_1"

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `a live one-time purchase is read from productsv2 and its amount from the order`() {
        server.product(token, productJson(quantity = 2))
        server.order("GPA.3300-0000-0000-00001", orderJson("GPA.3300-0000-0000-00001", "KRW", 3000))
        val purchase = gateway.fetch(StoreClaim(token, "cheer3000"))!!
        assertThat(purchase.mode).isEqualTo(PaymentMode.LIVE)
        assertThat(purchase.kind).isEqualTo(StorePurchaseKind.ONE_TIME)
        assertThat(purchase.state).isEqualTo(StorePurchaseState.PURCHASED)
        assertThat(purchase.productId).isEqualTo("cheer3000")
        assertThat(purchase.providerPaymentId).isEqualTo("GPA.3300-0000-0000-00001")
        assertThat(purchase.accountToken).isEqualTo(ACCOUNT_TOKEN)
        assertThat(purchase.amount).isEqualTo(PaymentAmount(3000, "KRW"))
        assertThat(purchase.quantity).isEqualTo(2)
        assertThat(purchase.paidAt).isEqualTo(NOW.minusSeconds(3600))
        assertThat(server.requests.filter { it.path != "/token" }.map { it.auth }.distinct()).containsExactly("Bearer ya29.fake")
    }

    @Test
    fun `an amount in a currency with decimals keeps its minor units`() {
        server.product(token, productJson())
        server.order("GPA.3300-0000-0000-00001", orderJson("GPA.3300-0000-0000-00001", "USD", 4, 990_000_000))
        assertThat(gateway.fetch(StoreClaim(token))!!.amount).isEqualTo(PaymentAmount(499, "USD"))
    }

    @Test
    fun `a test purchase is TEST and without an order it is free with no currency and a hashed token as its payment id`() {
        server.product(token, productJson(test = true, orderId = null))
        val purchase = gateway.fetch(StoreClaim(token))!!
        assertThat(purchase.mode).isEqualTo(PaymentMode.TEST)
        assertThat(purchase.amount).isEqualTo(PaymentAmount(0, "XXX"))
        assertThat(purchase.providerPaymentId).isEqualTo("pt:" + sha256Hex(token))
    }

    @Test
    fun `a test purchase whose order Google does not know falls back the same way`() {
        server.product(token, productJson(test = true))
        val purchase = gateway.fetch(StoreClaim(token))!!
        assertThat(purchase.mode).isEqualTo(PaymentMode.TEST)
        assertThat(purchase.amount).isEqualTo(PaymentAmount(0, "XXX"))
        assertThat(purchase.providerPaymentId).isEqualTo("pt:" + sha256Hex(token))
    }

    @Test
    fun `a live purchase whose amount cannot be read is a transient failure and never a free purchase`() {
        server.product(token, productJson())
        server.order("GPA.3300-0000-0000-00001", orderJson("GPA.3300-0000-0000-00001"))
        server.failNext("/orders/", 503)
        assertThatThrownBy { gateway.fetch(StoreClaim(token)) }.isInstanceOf(ExternalHttpException::class.java)
        assertThat(gateway.fetch(StoreClaim(token))!!.amount).isEqualTo(PaymentAmount(3000, "KRW"))
    }

    @Test
    fun `a live purchase whose order Google does not know is a transient failure`() {
        server.product(token, productJson())
        assertThatThrownBy { gateway.fetch(StoreClaim(token)) }.isInstanceOf(ExternalHttpException::class.java)
    }

    @Test
    fun `an answer for another product than the app claimed is refused`() {
        server.product(token, productJson(productId = "cheer10000"))
        server.order("GPA.3300-0000-0000-00001", orderJson("GPA.3300-0000-0000-00001"))
        assertThat(gateway.fetch(StoreClaim(token, "cheer3000"))).isNull()
        assertThat(gateway.fetch(StoreClaim(token, "cheer10000"))).isNotNull()
    }

    @Test
    fun `a token Google does not know is null and an unsafe token is never sent`() {
        assertThat(gateway.fetch(StoreClaim(token))).isNull()
        assertThat(gateway.fetch(StoreClaim("../orders/x"))).isNull()
        assertThat(server.pathsCalled().none { it.contains("..") }).isTrue()
    }

    @Test
    fun `a pending or canceled one-time purchase is read without asking for an amount`() {
        server.product("p1", productJson(state = "PENDING", orderId = null))
        server.product("p2", productJson(state = "CANCELLED"))
        assertThat(gateway.fetch(StoreClaim("p1"))!!.state).isEqualTo(StorePurchaseState.PENDING)
        assertThat(gateway.fetch(StoreClaim("p2"))!!.state).isEqualTo(StorePurchaseState.REVOKED)
        assertThat(server.pathsCalled().none { it.contains("/orders/") }).isTrue()
    }

    @Test
    fun `a refunded order makes a purchase that still looks purchased revoked`() {
        server.product(token, productJson())
        server.order("GPA.3300-0000-0000-00001", orderJson("GPA.3300-0000-0000-00001", state = "REFUNDED"))
        assertThat(gateway.fetch(StoreClaim(token))!!.state).isEqualTo(StorePurchaseState.REVOKED)
    }

    @Test
    fun `a subscription token falls through to subscriptionsv2 and keeps the facts the ledger needs`() {
        server.subscription("sub-tok", subscriptionJson(linked = "old-tok"))
        server.order("GPA.3300-0000-0000-00010", orderJson("GPA.3300-0000-0000-00010", "KRW", 9900))
        val purchase = gateway.fetch(StoreClaim("sub-tok", "premium"))!!
        assertThat(purchase.kind).isEqualTo(StorePurchaseKind.SUBSCRIPTION)
        assertThat(purchase.productId).isEqualTo("premium")
        assertThat(purchase.basePlanId).isEqualTo("monthly")
        assertThat(purchase.providerSubscriptionId).isEqualTo(sha256Hex("sub-tok"))
        assertThat(purchase.replacesSubscriptionId).isEqualTo(sha256Hex("old-tok"))
        assertThat(purchase.providerPaymentId).isEqualTo("GPA.3300-0000-0000-00010")
        assertThat(purchase.accountToken).isEqualTo(ACCOUNT_TOKEN)
        assertThat(purchase.amount).isEqualTo(PaymentAmount(9900, "KRW"))
        assertThat(purchase.periodEnd).isEqualTo(NOW.plus(Duration.ofDays(30)))
        assertThat(purchase.state).isEqualTo(StorePurchaseState.PURCHASED)
        assertThat(purchase.subscriptionStatus).isEqualTo(PushedSubscriptionStatus.ACTIVE)
        assertThat(purchase.mode).isEqualTo(PaymentMode.LIVE)
    }

    @Test
    fun `the subscription state decides the status the ledger gets`() {
        server.order("GPA.3300-0000-0000-00010", orderJson("GPA.3300-0000-0000-00010"))
        val cases = mapOf(
            "SUBSCRIPTION_STATE_CANCELED" to PushedSubscriptionStatus.CANCELING,
            "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" to PushedSubscriptionStatus.ACTIVE,
            "SUBSCRIPTION_STATE_ON_HOLD" to PushedSubscriptionStatus.PAST_DUE,
            "SUBSCRIPTION_STATE_PAUSED" to PushedSubscriptionStatus.PAST_DUE,
            "SUBSCRIPTION_STATE_EXPIRED" to PushedSubscriptionStatus.ENDED,
        )
        for ((state, expected) in cases) {
            server.subscription(state, subscriptionJson(state = state))
            assertThat(gateway.fetch(StoreClaim(state))!!.subscriptionStatus).`as`(state).isEqualTo(expected)
        }
    }

    @Test
    fun `a test subscription without an order is TEST and free`() {
        server.subscription("sub-tok", subscriptionJson(test = true, latestOrderId = null))
        val purchase = gateway.fetch(StoreClaim("sub-tok"))!!
        assertThat(purchase.mode).isEqualTo(PaymentMode.TEST)
        assertThat(purchase.amount).isEqualTo(PaymentAmount(0, "XXX"))
        assertThat(purchase.providerPaymentId).isEqualTo("pt:" + sha256Hex("sub-tok"))
    }

    @Test
    fun `a pending subscription is pending`() {
        server.subscription("sub-tok", subscriptionJson(state = "SUBSCRIPTION_STATE_PENDING", latestOrderId = null))
        assertThat(gateway.fetch(StoreClaim("sub-tok"))!!.state).isEqualTo(StorePurchaseState.PENDING)
    }

    // --- 서비스 계정 토큰 ---

    @Test
    fun `the service account token is asked once and reused, signed RS256 for the fixed audience and scope`() {
        server.product(token, productJson(test = true, orderId = null))
        gateway.fetch(StoreClaim(token))
        gateway.fetch(StoreClaim(token))
        assertThat(server.tokenClaims).hasSize(1)
        val claims = server.tokenClaims.single()
        assertThat(claims["alg"]).isEqualTo("RS256")
        assertThat(claims["iss"]).isEqualTo(SA_EMAIL)
        assertThat(claims["scope"]).isEqualTo("https://www.googleapis.com/auth/androidpublisher")
        assertThat(claims["aud"].toString()).contains("${server.baseUrl}/token").doesNotContain("evil.example.com")
        assertThat(server.requests.none { it.path.contains("evil") }).isTrue()
    }

    @Test
    fun `a token Google refuses is a failure of this call and the next call asks again`() {
        server.product(token, productJson(test = true, orderId = null))
        server.failNext("/token", 503)
        assertThatThrownBy { gateway.fetch(StoreClaim(token)) }.isInstanceOf(ExternalHttpException::class.java)
        assertThat(gateway.fetch(StoreClaim(token))).isNotNull()
    }

    // --- 승인 통지 ---

    @Test
    fun `afterGrant acknowledges a one-time purchase and a subscription at the paths Google documents`() {
        server.product(token, productJson())
        server.order("GPA.3300-0000-0000-00001", orderJson("GPA.3300-0000-0000-00001"))
        gateway.afterGrant(gateway.fetch(StoreClaim(token))!!)
        server.subscription("sub-tok", subscriptionJson())
        server.order("GPA.3300-0000-0000-00010", orderJson("GPA.3300-0000-0000-00010"))
        gateway.afterGrant(gateway.fetch(StoreClaim("sub-tok"))!!)
        val acks = server.requests.filter { it.method == "POST" && it.path.endsWith(":acknowledge") }.map { it.path.removePrefix("/androidpublisher/v3/applications/$PACKAGE") }
        assertThat(acks).containsExactly("/purchases/products/cheer3000/tokens/$token:acknowledge", "/purchases/subscriptions/tokens/sub-tok:acknowledge")
    }

    @Test
    fun `a purchase Google already shows as acknowledged is not acknowledged again, and an already-acknowledged answer is normal`() {
        server.product(token, productJson(acknowledged = true))
        server.order("GPA.3300-0000-0000-00001", orderJson("GPA.3300-0000-0000-00001"))
        gateway.afterGrant(gateway.fetch(StoreClaim(token))!!)
        assertThat(server.requests.filter { it.path.endsWith(":acknowledge") }).isEmpty()

        server.product("tok2", productJson(orderId = "GPA.3300-0000-0000-00002"))
        server.order("GPA.3300-0000-0000-00002", orderJson("GPA.3300-0000-0000-00002"))
        val second = gateway.fetch(StoreClaim("tok2"))!!
        server.failNext(":acknowledge", 400)
        gateway.afterGrant(second)
        assertThat(server.requests.filter { it.path.endsWith(":acknowledge") }).hasSize(1)
    }

    @Test
    fun `an acknowledge that fails for another reason is thrown for the caller to log`() {
        server.product(token, productJson())
        server.order("GPA.3300-0000-0000-00001", orderJson("GPA.3300-0000-0000-00001"))
        val purchase = gateway.fetch(StoreClaim(token))!!
        server.failNext(":acknowledge", 503)
        assertThatThrownBy { gateway.afterGrant(purchase) }.isInstanceOf(ExternalHttpException::class.java)
    }
}
