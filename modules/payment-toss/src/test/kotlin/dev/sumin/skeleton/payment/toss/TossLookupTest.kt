package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpNetworkException
import dev.sumin.skeleton.common.http.ExternalHttpTimeoutException
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCard
import dev.sumin.skeleton.payment.PaymentFailureOutcome
import dev.sumin.skeleton.payment.PaymentFailures
import dev.sumin.skeleton.payment.PaymentLookupGateway
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.ProviderPaymentStatus
import dev.sumin.skeleton.payment.ProviderRefund
import dev.sumin.skeleton.payment.toss.support.FakeTossServer
import dev.sumin.skeleton.payment.toss.support.FakeTossServer.Reply
import dev.sumin.skeleton.payment.toss.support.TossFixtures
import dev.sumin.skeleton.payment.toss.support.tossProvider
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 조회(`PaymentLookupGateway`) — 가짜 토스 서버와 진짜 HTTP 클라이언트로 (스펙 §6.6 대응표 · §4.1). */
class TossLookupTest {
    private val server = FakeTossServer()
    private val lookup: PaymentLookupGateway = tossProvider(server)

    @AfterTest
    fun tearDown() = server.close()

    private fun byKey(key: String = "pay_key_1") = PaymentLookupQuery(providerPaymentId = key, orderId = null)

    private fun byOrder(orderId: String = "ord_abcdef123456") = PaymentLookupQuery(providerPaymentId = null, orderId = orderId)

    @Test
    fun `lookup by payment key reads the payment with Basic auth and no body`() {
        server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment()))

        val snapshot = requireNotNull(lookup.lookup(byKey()))

        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/payments/pay_key_1", request.path)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("${server.secretKey}:".toByteArray()), request.header("authorization"))
        assertEquals("", request.body)
        assertEquals("pay_key_1", snapshot.providerPaymentId)
        assertEquals("ord_abcdef123456", snapshot.orderId)
        assertEquals(ProviderPaymentStatus.PAID, snapshot.status)
        assertEquals(PaymentAmount(3_000, "KRW"), snapshot.total)
        assertEquals(PaymentAmount(3_000, "KRW"), snapshot.remaining)
        assertEquals("CARD", snapshot.method)
        assertEquals(Instant.parse("2026-10-08T01:00:00Z"), snapshot.paidAt)
        assertEquals(emptyList(), snapshot.refunds)
    }

    @Test
    fun `lookup by order id uses the order path`() {
        server.on("GET", "/v1/payments/orders/ord_abcdef123456", Reply.Json(200, TossFixtures.payment()))

        val snapshot = requireNotNull(lookup.lookup(byOrder()))

        assertEquals("/v1/payments/orders/ord_abcdef123456", server.requests.single().path)
        assertEquals("pay_key_1", snapshot.providerPaymentId)
    }

    @Test
    fun `a payment key wins when the query carries both`() {
        server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment()))

        lookup.lookup(PaymentLookupQuery(providerPaymentId = "pay_key_1", orderId = "ord_abcdef123456"))

        assertEquals(listOf("/v1/payments/pay_key_1"), server.requests.map { it.path })
    }

    @Test
    fun `404 NOT_FOUND_PAYMENT means there is no payment - null, for both lookups`() {
        server.on("GET", "/v1/payments/pay_gone", TossFixtures.error(404, "NOT_FOUND_PAYMENT"))
        server.on("GET", "/v1/payments/orders/ord_gone", TossFixtures.error(404, "NOT_FOUND_PAYMENT"))

        assertNull(lookup.lookup(byKey("pay_gone")))
        assertNull(lookup.lookup(byOrder("ord_gone")))
    }

    @Test
    fun `any other error is not null - a 404 with another code, a 401, a 5xx all throw`() {
        server.on("GET", "/v1/payments/pay_a", TossFixtures.error(404, "NOT_FOUND_PAYMENT_SESSION"))
        server.on("GET", "/v1/payments/pay_b", TossFixtures.error(500, "FAILED_INTERNAL_SYSTEM_PROCESSING"))
        server.on("GET", "/v1/payments/pay_c", TossFixtures.error(404, "SOMETHING_ELSE"))

        assertEquals("NOT_FOUND_PAYMENT_SESSION", assertFailsWith<PaymentProviderException> { lookup.lookup(byKey("pay_a")) }.providerError.code)
        assertEquals(500, assertFailsWith<PaymentProviderException> { lookup.lookup(byKey("pay_b")) }.providerError.upstreamStatus)
        assertEquals("SOMETHING_ELSE", assertFailsWith<PaymentProviderException> { lookup.lookup(byKey("pay_c")) }.providerError.code)
    }

    @Test
    fun `every Toss status maps to one provider status`() {
        val table = mapOf(
            "READY" to ProviderPaymentStatus.IN_PROGRESS,
            "IN_PROGRESS" to ProviderPaymentStatus.IN_PROGRESS,
            "WAITING_FOR_DEPOSIT" to ProviderPaymentStatus.IN_PROGRESS,
            "DONE" to ProviderPaymentStatus.PAID,
            "PARTIAL_CANCELED" to ProviderPaymentStatus.PARTIALLY_REFUNDED,
            "CANCELED" to ProviderPaymentStatus.REFUNDED,
            "ABORTED" to ProviderPaymentStatus.FAILED,
            "EXPIRED" to ProviderPaymentStatus.EXPIRED,
        )
        table.forEach { (tossStatus, expected) ->
            // 환불 상태는 환불 목록이 같이 있어야 읽힌다(상태와 목록이 어긋나면 읽을 수 없는 응답이다)
            val cancels = if (tossStatus.endsWith("CANCELED")) listOf(TossFixtures.cancel("tx_c1", 1_000)) else null
            server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(status = tossStatus, cancels = cancels)))
            assertEquals(expected, requireNotNull(lookup.lookup(byKey())).status, tossStatus)
        }
    }

    @Test
    fun `a status this module does not know is an unreadable answer - never a guess`() {
        listOf("SOMETHING_NEW", "", "done").forEach { status ->
            server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(status = status)))
            val error = assertFailsWith<PaymentProviderException>(status) { lookup.lookup(byKey()) }
            assertEquals("UNREADABLE_RESPONSE", error.providerError.code)
            assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(error))
        }
    }

    @Test
    fun `total, remaining and only the cancels that are DONE become the refund list`() {
        val cancels = listOf(
            TossFixtures.cancel("tx_c1", 3_000, canceledAt = "2026-10-08T10:15:30+09:00"),
            TossFixtures.cancel("tx_c2", 500, status = "IN_PROGRESS"),
            TossFixtures.cancel("tx_c3", 700, status = "ABORTED"),
            TossFixtures.cancel("tx_c4", 1_000, canceledAt = "2026-10-08T11:00:00+09:00"),
        )
        server.on(
            "GET",
            "/v1/payments/pay_key_1",
            Reply.Json(200, TossFixtures.payment(status = "PARTIAL_CANCELED", total = 10_000, balance = 6_000, cancels = cancels)),
        )

        val snapshot = requireNotNull(lookup.lookup(byKey()))

        assertEquals(PaymentAmount(10_000, "KRW"), snapshot.total)
        assertEquals(PaymentAmount(6_000, "KRW"), snapshot.remaining)
        assertEquals(
            listOf(
                ProviderRefund("tx_c1", PaymentAmount(3_000, "KRW"), Instant.parse("2026-10-08T01:15:30Z")),
                ProviderRefund("tx_c4", PaymentAmount(1_000, "KRW"), Instant.parse("2026-10-08T02:00:00Z")),
            ),
            snapshot.refunds,
        )
    }

    @Test
    fun `a missing balance is total minus the cancels that are DONE`() {
        val cancels = listOf(TossFixtures.cancel("tx_c1", 3_000), TossFixtures.cancel("tx_c2", 500, status = "ABORTED"))
        server.on(
            "GET",
            "/v1/payments/pay_key_1",
            Reply.Json(200, TossFixtures.payment(status = "PARTIAL_CANCELED", total = 10_000, balance = null, cancels = cancels)),
        )

        assertEquals(PaymentAmount(7_000, "KRW"), requireNotNull(lookup.lookup(byKey())).remaining)
    }

    @Test
    fun `a DONE cancel without a transaction key or time is unreadable - a refund is never invented`() {
        val noKey = TossFixtures.cancel("x", 1_000).toMutableMap().apply { put("transactionKey", null) }
        val noTime = TossFixtures.cancel("tx", 1_000).toMutableMap().apply { put("canceledAt", null) }
        listOf(noKey, noTime).forEach { cancel ->
            server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(status = "PARTIAL_CANCELED", cancels = listOf(cancel))))
            val error = assertFailsWith<PaymentProviderException> { lookup.lookup(byKey()) }
            assertEquals("UNREADABLE_RESPONSE", error.providerError.code)
        }
    }

    @Test
    fun `card issuer code is translated and only the last four characters of the number are kept`() {
        fun cardOf(card: Map<String, Any?>?): PaymentCard? {
            server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(card = card)))
            return requireNotNull(lookup.lookup(byKey())).card
        }

        assertEquals(PaymentCard("HYUNDAI", "123*"), cardOf(TossFixtures.card(issuerCode = "61", number = "43301234****123*")))
        assertEquals(PaymentCard("SHINHAN", "1234"), cardOf(TossFixtures.card(issuerCode = "41", number = "4330123412341234")))
        assertEquals(PaymentCard("KOOKMIN", "****"), cardOf(TossFixtures.card(issuerCode = "11", number = "4330********")))
        assertEquals(PaymentCard("ZZ", "123*"), cardOf(TossFixtures.card(issuerCode = "ZZ", number = "43301234****123*")), "a code the table does not know is passed through")
        assertEquals(PaymentCard(null, "123*"), cardOf(TossFixtures.card(issuerCode = "<script>alert(1)</script>", number = "43301234****123*")), "an issuer code that is not a plain code is dropped")
        assertEquals(PaymentCard("HYUNDAI", null), cardOf(TossFixtures.card(issuerCode = "61", number = "4330-1234-12ab")), "a last four with letters is dropped")
        assertEquals(PaymentCard("HYUNDAI", null), cardOf(TossFixtures.card(issuerCode = "61", number = "123")), "fewer than four characters is dropped")
        assertEquals(PaymentCard("HYUNDAI", null), cardOf(TossFixtures.card(issuerCode = "61", number = null)))
        assertNull(cardOf(TossFixtures.card(issuerCode = null, number = null)))
        assertNull(cardOf(null), "not a card payment")
    }

    @Test
    fun `a lookup times out by its own lookup-timeout even when the client allows much longer`() {
        val quick = tossProvider(server, responseTimeout = Duration.ofSeconds(30), lookupTimeout = Duration.ofMillis(300))
        server.on("GET", "/v1/payments/pay_key_1", Reply.Hang)

        val error = assertFailsWith<ExternalHttpTimeoutException> { quick.lookup(byKey()) }

        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(error))
    }

    @Test
    fun `5xx, a dropped connection and a broken body are all unknown`() {
        server.on("GET", "/v1/payments/pay_5xx", TossFixtures.error(503, "FAILED_INTERNAL_SYSTEM_PROCESSING"))
        server.on("GET", "/v1/payments/pay_drop", Reply.Drop)
        server.on("GET", "/v1/payments/pay_broken", Reply.Broken())

        listOf("pay_5xx", "pay_drop", "pay_broken").forEach { key ->
            val error = assertFailsWith<Exception>(key) { lookup.lookup(byKey(key)) }
            assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(error), key)
        }
        assertIs<ExternalHttpNetworkException>(assertFailsWith<Exception> { lookup.lookup(byKey("pay_drop")) })
        val broken = assertFailsWith<PaymentProviderException> { lookup.lookup(byKey("pay_broken")) }
        assertEquals("UNREADABLE_RESPONSE", broken.providerError.code)
        assertTrue(broken.providerError.retryable)
    }

    @Test
    fun `a lookup error says nothing about whether money moved - 401, 403 and 400 are unknown, never definitive`() {
        listOf(401 to "UNAUTHORIZED_KEY", 403 to "FORBIDDEN_REQUEST", 400 to "INVALID_REQUEST", 404 to "NOT_FOUND_PAYMENT_SESSION", 403 to "REJECT_CARD_PAYMENT").forEach { (status, code) ->
            server.on("GET", "/v1/payments/pay_key_1", TossFixtures.error(status, code))

            val error = assertFailsWith<PaymentProviderException>("$status $code") { lookup.lookup(byKey()) }

            assertEquals(code, error.providerError.code)
            assertFalse(error.providerError.definitive, "$status $code")
            assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(error), "$status $code")
        }
    }

    @Test
    fun `the same wrong key is still a definitive refusal on a confirm - only lookups stop saying definitive`() {
        val wrong = TossPaymentProvider(
            httpClient = dev.sumin.skeleton.payment.toss.support.realHttpClient(),
            properties = tossPropertiesWithKey(server, "test_sk_not_the_servers"),
        )
        server.on("POST", "/v1/payments/confirm", Reply.Json(200, TossFixtures.payment()))

        val error = assertFailsWith<PaymentProviderException> {
            wrong.confirm(dev.sumin.skeleton.payment.PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456"))
        }

        assertEquals("UNAUTHORIZED_KEY", error.providerError.code)
        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(error))
    }

    @Test
    fun `a refunded status with no DONE cancel in the list contradicts itself and is unreadable`() {
        listOf("CANCELED", "PARTIAL_CANCELED").forEach { status ->
            server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(status = status, balance = 0, cancels = null)))
            val error = assertFailsWith<PaymentProviderException>(status) { lookup.lookup(byKey()) }
            assertEquals("UNREADABLE_RESPONSE", error.providerError.code)
        }
    }

    @Test
    fun `a paid payment that lists a DONE cancel contradicts itself and is unreadable`() {
        server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(status = "DONE", cancels = listOf(TossFixtures.cancel("tx_c1", 1_000)))))

        assertEquals("UNREADABLE_RESPONSE", assertFailsWith<PaymentProviderException> { lookup.lookup(byKey()) }.providerError.code)
    }

    @Test
    fun `a cancel without a status is unreadable - it is not silently left out`() {
        val noStatus = TossFixtures.cancel("tx_c1", 3_000).toMutableMap().apply { put("cancelStatus", null) }
        server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(status = "CANCELED", balance = 0, cancels = listOf(noStatus))))

        assertEquals("UNREADABLE_RESPONSE", assertFailsWith<PaymentProviderException> { lookup.lookup(byKey()) }.providerError.code)
    }

    @Test
    fun `a missing currency is not guessed from the configuration`() {
        server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(extra = mapOf("currency" to null))))

        assertEquals("UNREADABLE_RESPONSE", assertFailsWith<PaymentProviderException> { lookup.lookup(byKey()) }.providerError.code)
    }

    @Test
    fun `the unreadable answer keeps the real status of the answer and a short reason in the log, not the body`() {
        server.on("GET", "/v1/payments/pay_key_1", Reply.Json(202, TossFixtures.payment(status = "SOMETHING_NEW")))

        dev.sumin.skeleton.payment.toss.support.CapturedLogs().use { logs ->
            val error = assertFailsWith<PaymentProviderException> { lookup.lookup(byKey()) }

            assertEquals(202, error.providerError.upstreamStatus)
            val line = logs.lines().single { "could not be read" in it }
            assertTrue("status" in line, line)
            dev.sumin.skeleton.payment.toss.support.TossFixtures.PLANTED.ALL.forEach { assertFalse(it in line) }
            assertFalse("SOMETHING_NEW" in line, "the value is not logged: $line")
        }
    }

    @Test
    fun `the method becomes a neutral kind code through the table - an unknown or personal value becomes null`() {
        fun methodOf(method: String?): String? {
            server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(method = method)))
            return requireNotNull(lookup.lookup(byKey())).method
        }

        // 토스의 한글 라벨과 영문 값을 둘 다 받는다. 실제 값은 아직 못 쟀다(real-provider-setup.md §11.6).
        assertEquals("CARD", methodOf("카드"))
        assertEquals("EASY_PAY", methodOf("간편결제"))
        assertEquals("VIRTUAL_ACCOUNT", methodOf("가상계좌"))
        assertEquals("TRANSFER", methodOf("계좌이체"))
        assertEquals("MOBILE_PHONE", methodOf("휴대폰"))
        assertEquals("CARD", methodOf("CARD"))
        assertEquals("EASY_PAY", methodOf("EASY_PAY"))
        assertNull(methodOf("Kim Minsu"), "a name shaped value is not in the table")
        assertNull(methodOf("김민수"))
        assertNull(methodOf("상품권 어쩌고"))
        assertNull(methodOf("010-1234-5678"))
        assertNull(methodOf("01012345678"))
        assertNull(methodOf("<script>"))
        assertNull(methodOf(null))
    }

    private fun tossPropertiesWithKey(server: FakeTossServer, key: String) =
        TossPaymentProperties(enabled = true, secretKey = key, baseUrl = server.baseUrl)
}
