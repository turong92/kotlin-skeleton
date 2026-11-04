package dev.sumin.skeleton.payment.googleplay

import dev.sumin.skeleton.common.http.ExternalHttpException
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.PushedEvents
import dev.sumin.skeleton.payment.PushedItem
import dev.sumin.skeleton.payment.PushedPayment
import dev.sumin.skeleton.payment.PushedRefund
import dev.sumin.skeleton.payment.PushedRefundKind
import dev.sumin.skeleton.payment.PushedRefundStatus
import dev.sumin.skeleton.payment.PushedSubscription
import dev.sumin.skeleton.payment.PushedSubscriptionStatus
import dev.sumin.skeleton.payment.WebhookDelivery
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class GooglePlayWebhookTest {
    private val account = TestServiceAccount()
    private val oidc = TestOidc()
    private val server = FakePlayServer(account, oidc)
    private val gateway = provider(server, account)
    private val token = "sub-tok.1"
    private val order = "GPA.3300-0000-0000-00010"

    @AfterEach
    fun stop() = server.close()

    private fun delivery(body: String, bearer: String? = oidc.token()) =
        WebhookDelivery(if (bearer == null) emptyMap() else mapOf("Authorization" to "Bearer $bearer"), body.toByteArray())

    private fun rejected(body: String, bearer: String?) =
        assertThatThrownBy { gateway.parse(delivery(body, bearer)) }.isInstanceOf(PaymentWebhookRejectedException::class.java)

    private val body = pushBody(subscriptionNotification(2, token))

    // --- Pub/Sub push 인증 (OIDC) - 틀리면 거절, 아무것도 저장하지 않는다 ---

    @Test
    fun `a push without a token, with a token for another audience, another account or an unverified email is rejected`() {
        rejected(body, null)
        rejected(body, oidc.token(audience = "https://other.example.com/hook"))
        rejected(body, oidc.token(email = "someone-else@sample-project.iam.gserviceaccount.com"))
        rejected(body, oidc.token(email = null))
        rejected(body, oidc.token(emailVerified = false))
        rejected(body, oidc.token(emailVerified = null))
    }

    @Test
    fun `an expired token, another issuer and a token signed by a key Google does not publish are rejected`() {
        rejected(body, oidc.token(expires = NOW.minus(Duration.ofMinutes(10))))
        rejected(body, oidc.token(issuer = "https://evil.example.com"))
        rejected(body, oidc.token(signer = TestOidc("test-key-1")))
        rejected(body, oidc.token(kid = "unknown-kid"))
        rejected(body, oidc.token(kid = null))
    }

    @Test
    fun `garbage in place of the token is rejected`() {
        for (bearer in listOf("", "a.b.c", "not-a-jwt")) rejected(body, bearer)
        assertThatThrownBy { gateway.parse(WebhookDelivery(mapOf("Authorization" to "Basic abc"), body.toByteArray())) }.isInstanceOf(PaymentWebhookRejectedException::class.java)
    }

    @Test
    fun `both spellings of Google's issuer are accepted`() {
        for (issuer in listOf("accounts.google.com", "https://accounts.google.com")) {
            assertThat(gateway.parse(delivery(body, oidc.token(issuer = issuer))).resourceType).isEqualTo(PushedEvents.RESOURCE_TYPE)
        }
    }

    @Test
    fun `the public keys are fetched once and unknown key ids do not make a fetch per request`() {
        repeat(3) { gateway.parse(delivery(body)) }
        assertThat(server.certFetches).isEqualTo(1)
        repeat(5) { rejected(body, oidc.token(kid = "unknown-kid")) }
        assertThat(server.certFetches).isLessThanOrEqualTo(2)
    }

    @Test
    fun `a body that is not a Pub-Sub push is rejected even with a good token`() {
        for (text in listOf("", "not json", "{}", """{"message":{}}""", """{"message":{"data":"@@@","messageId":"1"}}""", """{"message":{"data":"e30=","messageId":""}}""")) {
            assertThatThrownBy { gateway.parse(delivery(text)) }.`as`(text).isInstanceOf(PaymentWebhookRejectedException::class.java)
        }
    }

    // --- 봉투 ---

    @Test
    fun `the message id is the event key, and the same delivery twice is the same envelope`() {
        val first = gateway.parse(delivery(pushBody(subscriptionNotification(2, token), "7000000000042")))
        val second = gateway.parse(delivery(pushBody(subscriptionNotification(2, token), "7000000000042")))
        assertThat(first.eventKey).isEqualTo("7000000000042")
        assertThat(first.eventType).isEqualTo("SUBSCRIPTION_RENEWED")
        assertThat(first.resourceType).isEqualTo(PushedEvents.RESOURCE_TYPE)
        assertThat(first.resourceId).isEqualTo(sha256Hex(token))
        assertThat(second).isEqualTo(first)
        assertThat(first.toString()).doesNotContain(token)
    }

    @Test
    fun `a notification for another package, a test notification and the kinds the ledger does not follow are ignored`() {
        val ignored = listOf(
            subscriptionNotification(2, token, packageName = "com.example.other"),
            """{"version":"1.0","packageName":"$PACKAGE","eventTimeMillis":"${NOW.toEpochMilli()}","testNotification":{"version":"1.0"}}""",
            subscriptionNotification(4, token), // 첫 구매는 앱이 확인받는다
            subscriptionNotification(8, token), // 가격 변경 동의
            """{"version":"1.0","packageName":"$PACKAGE","eventTimeMillis":"${NOW.toEpochMilli()}","oneTimeProductNotification":{"version":"1.0","notificationType":1,"purchaseToken":"$token","sku":"cheer3000"}}""",
            voidedNotification(token, order, packageName = "com.example.other"),
        )
        for (notification in ignored) {
            assertThat(gateway.parse(delivery(pushBody(notification))).resourceType).`as`(notification).isEqualTo("UNSUPPORTED")
        }
    }

    // --- 해석: 락 밖에서 API 를 다시 읽는다 ---

    private fun event(notification: String) = gateway.interpret(gateway.parse(delivery(pushBody(notification))).storedPayload!!)

    @Test
    fun `a renewal is read again from Google and becomes a subscription payment for the latest successful order`() {
        server.subscription(token, subscriptionJson(latestOrderId = order))
        server.order(order, orderJson(order, "KRW", 9900))
        val payment = event(subscriptionNotification(2, token)) as PushedPayment
        assertThat(payment.eventId).isEqualTo("7000000000001")
        assertThat(payment.occurredAt).isEqualTo(NOW)
        assertThat(payment.providerPaymentId).isEqualTo(order)
        assertThat(payment.providerSubscriptionId).isEqualTo(sha256Hex(token))
        assertThat(payment.total).isEqualTo(PaymentAmount(9900, "KRW"))
        assertThat(payment.periodEnd).isEqualTo(NOW.plus(Duration.ofDays(30)))
        assertThat(payment.items).containsExactly(PushedItem("premium", 1))
        assertThat(payment.mode).isEqualTo(PaymentMode.LIVE)
        assertThat(payment.ourOrderId).isNull()
        assertThat(server.pathsCalled()).anyMatch { it.endsWith("/purchases/subscriptionsv2/tokens/$token") }
    }

    @Test
    fun `a test subscription renewal is a TEST event`() {
        server.subscription(token, subscriptionJson(test = true, latestOrderId = null))
        val payment = event(subscriptionNotification(2, token)) as PushedPayment
        assertThat(payment.mode).isEqualTo(PaymentMode.TEST)
        assertThat(payment.providerPaymentId).isEqualTo("pt:" + sha256Hex(token))
    }

    @Test
    fun `state changes become subscription events with Google's state read now`() {
        server.order(order, orderJson(order))
        val cases = listOf(
            Triple(3, "SUBSCRIPTION_STATE_CANCELED", PushedSubscriptionStatus.CANCELING),
            Triple(7, "SUBSCRIPTION_STATE_ACTIVE", PushedSubscriptionStatus.ACTIVE),
            Triple(6, "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", PushedSubscriptionStatus.ACTIVE),
            Triple(5, "SUBSCRIPTION_STATE_ON_HOLD", PushedSubscriptionStatus.PAST_DUE),
            Triple(10, "SUBSCRIPTION_STATE_PAUSED", PushedSubscriptionStatus.PAST_DUE),
            Triple(13, "SUBSCRIPTION_STATE_EXPIRED", PushedSubscriptionStatus.ENDED),
            Triple(12, "SUBSCRIPTION_STATE_EXPIRED", PushedSubscriptionStatus.ENDED),
        )
        for ((type, state, expected) in cases) {
            server.subscription(token, subscriptionJson(state = state, latestOrderId = order))
            val subscription = event(subscriptionNotification(type, token)) as PushedSubscription
            assertThat(subscription.status).`as`("$type/$state").isEqualTo(expected)
            assertThat(subscription.providerSubscriptionId).isEqualTo(sha256Hex(token))
            assertThat(subscription.items).containsExactly(PushedItem("premium", 1))
        }
    }

    @Test
    fun `a notification for a token Google no longer knows is not handled`() {
        assertThat(event(subscriptionNotification(2, token))).isNull()
    }

    @Test
    fun `an API that is unavailable while reading is a transient failure so the inbox row is retried and never dead`() {
        server.subscription(token, subscriptionJson())
        server.failNext("/subscriptionsv2/tokens/$token", 503)
        assertThatThrownBy { event(subscriptionNotification(2, token)) }.isInstanceOf(ExternalHttpException::class.java)
        server.order(order, orderJson(order))
        server.failNext("/orders/", 503)
        assertThatThrownBy { event(subscriptionNotification(2, token)) }.isInstanceOf(ExternalHttpException::class.java)
    }

    // --- 환불 ---

    @Test
    fun `a voided one-time purchase is a full refund of that order`() {
        server.product("otp", productJson())
        server.order("GPA.3300-0000-0000-00001", orderJson("GPA.3300-0000-0000-00001", "KRW", 3000))
        val refund = event(voidedNotification("otp", "GPA.3300-0000-0000-00001")) as PushedRefund
        assertThat(refund.kind).isEqualTo(PushedRefundKind.REFUND)
        assertThat(refund.status).isEqualTo(PushedRefundStatus.APPROVED)
        assertThat(refund.providerPaymentId).isEqualTo("GPA.3300-0000-0000-00001")
        assertThat(refund.providerRefundId).isEqualTo("void-GPA.3300-0000-0000-00001")
        assertThat(refund.amount).isEqualTo(PaymentAmount(3000, "KRW"))
        assertThat(refund.providerSubscriptionId).isNull()
        assertThat(refund.mode).isEqualTo(PaymentMode.LIVE)
    }

    @Test
    fun `a voided subscription order is a refund that names the subscription, and a test purchase is a TEST refund`() {
        server.subscription(token, subscriptionJson(test = true))
        server.order(order, orderJson(order, "KRW", 9900))
        val refund = event(voidedNotification(token, order, productType = 1)) as PushedRefund
        assertThat(refund.providerSubscriptionId).isEqualTo(sha256Hex(token))
        assertThat(refund.mode).isEqualTo(PaymentMode.TEST)
        assertThat(refund.amount).isEqualTo(PaymentAmount(9900, "KRW"))
    }

    @Test
    fun `a partial refund by quantity is not guessed - it needs a person`() {
        server.product("otp", productJson())
        server.order("GPA.3300-0000-0000-00001", orderJson("GPA.3300-0000-0000-00001"))
        assertThatThrownBy { event(voidedNotification("otp", "GPA.3300-0000-0000-00001", refundType = 2)) }
            .isInstanceOf(IllegalStateException::class.java)
            .isNotInstanceOf(ExternalHttpException::class.java)
    }
}
