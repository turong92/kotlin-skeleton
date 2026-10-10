package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.WebhookDelivery
import dev.sumin.skeleton.payment.toss.support.TossFixtures
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.sumin.skeleton.common.http.ExternalHttpClient
import java.lang.reflect.Proxy

/** 결제창 값 · 모드 · 웹훅 해석 — HTTP 가 필요 없는 부분. */
class TossGatewaySurfaceTest {
    private val noHttp: ExternalHttpClient =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ExternalHttpClient::class.java)) { _, _, _ -> error("no HTTP expected") } as ExternalHttpClient

    private fun provider(secretKey: String = "test_sk_secret_part", clientKey: String = "test_ck_client_part") =
        TossPaymentProvider(noHttp, TossPaymentProperties(enabled = true, secretKey = secretKey, clientKey = clientKey))

    @Test
    fun `mode comes from the secret key prefix`() {
        assertEquals(PaymentMode.TEST, provider("test_sk_x").mode)
        assertEquals(PaymentMode.LIVE, provider("live_sk_x").mode)
        assertEquals(PaymentMode.TEST, provider("test_gsk_x").mode)
        assertEquals(PaymentMode.UNKNOWN, provider("sk_without_mode").mode)
        assertEquals(PaymentMode.UNKNOWN, provider("anything").mode)
    }

    @Test
    fun `checkout hands the browser the public values - client key, mode, order - and never the secret key`() {
        val order = CheckoutOrder("ord_abcdef123456", "응원하기 3,000원", PaymentAmount(3_000, "KRW"))

        val instruction = provider().checkout(order)

        assertEquals("toss", instruction.provider)
        assertEquals("TOSS_PAYMENT_WINDOW", instruction.kind)
        assertTrue(instruction.testMode)
        assertEquals(PaymentAmount(3_000, "KRW"), instruction.amount)
        assertEquals("test_ck_client_part", instruction.clientKey)
        assertFalse(instruction.toString().contains("secret_part"))
    }

    @Test
    fun `checkout hands out only a value that is shaped like a client key - a secret key put in the wrong place is never sent to the browser`() {
        val order = CheckoutOrder("ord_abcdef123456", "x", PaymentAmount(1_000, "KRW"))

        assertNull(provider(clientKey = "test_sk_secret_part").checkout(order).clientKey, "secret key in the client key place")
        assertNull(provider(clientKey = "test_gck_widget_part").checkout(order).clientKey, "widget client key")
        assertFalse(provider(clientKey = "test_sk_secret_part").canOpenCheckout())
        assertFalse(provider("live_sk_x", "test_ck_client_part").canOpenCheckout(), "a test client key with a live secret")
        assertTrue(provider().canOpenCheckout())
    }

    @Test
    fun `a payment status webhook yields the payment key and order id only, keyed by the transmission id`() {
        val envelope = provider().parse(
            WebhookDelivery(
                headers = mapOf("tosspayments-webhook-transmission-id" to "tx-id-0001"),
                body = TossFixtures.webhook(status = "CANCELED").toByteArray(),
            ),
        )

        assertEquals("tx-id-0001", envelope.eventKey)
        assertEquals("pay_key_1", envelope.resourceId)
        assertEquals("ord_abcdef123456", envelope.orderId)
        assertFalse(envelope.toString().contains("CANCELED"), "the status in the body is not used")
        assertFalse(envelope.toString().contains("9999999"), "the amount in the body is not used")
    }

    @Test
    fun `without a transmission id the event key is the sha256 of the body`() {
        val body = TossFixtures.webhook().toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

        assertEquals(expected, provider().parse(WebhookDelivery(emptyMap(), body)).eventKey)
    }

    @Test
    fun `a body that is not a Toss payment event is refused without quoting it`() {
        listOf(
            "not json",
            """{"eventType":"PAYMENT_STATUS_CHANGED","data":{"paymentKey":"has spaces ${TossFixtures.PLANTED.NAME}"}}""",
        ).forEach { body ->
            val error = assertFailsWith<PaymentWebhookRejectedException>(body) { provider().parse(WebhookDelivery(emptyMap(), body.toByteArray())) }
            assertFalse(error.message.orEmpty().contains(TossFixtures.PLANTED.NAME))
            assertFalse(error.message.orEmpty().contains("not json"))
        }
    }
}
