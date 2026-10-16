package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentFailureOutcome
import dev.sumin.skeleton.payment.PaymentFailures
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.ProviderPaymentStatus
import dev.sumin.skeleton.payment.naverpay.support.FakeNaverPayServer
import dev.sumin.skeleton.payment.naverpay.support.FakeNaverPayServer.Fail
import dev.sumin.skeleton.payment.naverpay.support.checkoutOrder
import dev.sumin.skeleton.payment.naverpay.support.naverProvider
import dev.sumin.skeleton.payment.naverpay.support.payAtWindow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 네이버페이 한 빈의 결제창 값 · 승인 · 환불 · 조회 - 엄격한 가짜 네이버페이 서버(`FakeNaverPayServer`)와 실제 HTTP 로 말한다.
 * 지키는 것: 같은 결제는 두 번 승인되지 않음(멱등 키) · 남은 금액을 넘는 환불은 거절 · 응답이 유실돼도 조회가 사실을 말함 · 승인 응답은 있는 그대로(금액 · 주문 번호) 읽어 장부가 대조함 · 개인정보 · 키는 어디에도 안 나옴.
 */
class NaverPayProviderTest {
    private val server = FakeNaverPayServer()
    private val provider = naverProvider(server)

    @AfterTest
    fun tearDown() = server.close()

    private fun confirmRequest(paymentId: String, amount: Long = 3_000, orderId: String = "ord_abcdef123456") =
        PaymentConfirmRequest(providerPaymentId = paymentId, amount = PaymentAmount(amount, "KRW"), merchantReferenceId = orderId, idempotencyKey = IdempotencyKey(orderId), accountId = "acc_1")

    private fun approved(amount: Long = 3_000): String {
        val paymentId = payAtWindow(server, provider, amount = amount)
        assertEquals(PaymentOperationStatus.CONFIRMED, provider.confirm(confirmRequest(paymentId, amount)).status)
        return paymentId
    }

    @Test
    fun `the checkout reserves the payment on the server with the server's own amount and order id, and hands the browser only the window address`() {
        val instruction = provider.checkout(checkoutOrder())

        val reserve = server.requests.single()
        assertEquals("/naverpay-partner/naverpay/payments/v2/reserve", reserve.path)
        assertEquals(server.clientId, reserve.header("x-naver-client-id"))
        assertEquals(server.clientSecret, reserve.header("x-naver-client-secret"))
        assertEquals(server.chainId, reserve.header("x-naverpay-chain-id"))
        assertTrue(Regex("[A-Za-z0-9-]{1,64}").matches(reserve.header("x-naverpay-idempotency-key").orEmpty()))
        val body = tools.jackson.databind.json.JsonMapper.builder().build().readValue(reserve.body, Map::class.java)
        assertEquals("ord_abcdef123456", body["merchantPayKey"])
        assertEquals(3000, body["totalPayAmount"])
        assertEquals(3000, body["taxScopeAmount"])
        assertEquals(0, body["taxExScopeAmount"])
        assertEquals("https://app.example.test/payments/return?kind=NAVERPAY_REDIRECT&provider=naverpay&order=ord_abcdef123456", body["returnUrl"])
        assertFalse(reserve.body.contains("merchantUserKey") || reserve.body.contains("purchaser"), "no buyer information is sent")

        assertEquals("NAVERPAY_REDIRECT", instruction.kind)
        assertTrue(instruction.testMode)
        assertNull(instruction.clientKey, "the ids are not for the browser any more")
        assertNull(instruction.providerPaymentId, "the payment id comes back to the browser at the return address")
        val reservation = server.reservations().single()
        assertEquals("https://test-m.pay.naver.com/payments/${reservation.reserveId}", instruction.attributes["windowUrl"])
        assertEquals(setOf("windowUrl"), instruction.attributes.keys)
        assertFalse(server.clientSecret in instruction.toString())
    }

    @Test
    fun `the buyer cannot lower the amount - the window address carries only the reserve id and the reservation holds the ledger's amount`() {
        val instruction = provider.checkout(checkoutOrder(amount = 5_000))

        assertEquals(5_000, server.reservations().single().total)
        assertFalse("5000" in instruction.attributes.getValue("windowUrl"))
    }

    @Test
    fun `every checkout is its own reservation with its own key, and when NaverPay cannot reserve the checkout is unavailable (503)`() {
        provider.checkout(checkoutOrder())
        provider.checkout(checkoutOrder())
        assertEquals(2, server.reservations().size)
        assertEquals(2, server.requests.map { it.header("x-naverpay-idempotency-key") }.toSet().size)

        server.failNext("reserve", Fail.ERROR)
        assertFailsWith<dev.sumin.skeleton.payment.PaymentUnavailableException> { provider.checkout(checkoutOrder()) }
        server.failNext("reserve", Fail.DECLINE)
        assertFailsWith<dev.sumin.skeleton.payment.PaymentUnavailableException> { provider.checkout(checkoutOrder()) }
    }

    @Test
    fun `a purchase in another currency than KRW is never reserved - 503 and no request`() {
        val usd = checkoutOrder().copy(amount = PaymentAmount(3_000, "USD"))

        assertFailsWith<dev.sumin.skeleton.payment.PaymentUnavailableException> { provider.checkout(usd) }

        assertEquals(0, server.requests.size)
    }

    @Test
    fun `a return address that already has a query gets the pieces appended with ampersands, and one that is not an http address closes the window`() {
        val withQuery = naverProvider(server, returnUrl = "https://app.example.test/pay?lang=ko")
        withQuery.checkout(checkoutOrder())
        val body = tools.jackson.databind.json.JsonMapper.builder().build().readValue(server.requests.single().body, Map::class.java)
        assertEquals("https://app.example.test/pay?lang=ko&kind=NAVERPAY_REDIRECT&provider=naverpay&order=ord_abcdef123456", body["returnUrl"])

        server.clearRequests()
        val relative = naverProvider(server, returnUrl = "/payments/return")
        assertFalse(relative.canOpenCheckout())
        assertFailsWith<dev.sumin.skeleton.payment.PaymentUnavailableException> { relative.checkout(checkoutOrder()) }
        assertEquals(0, server.requests.size)
    }

    @Test
    fun `apply sends only the payment id as a form with the three auth headers and a key derived from the payment id - and reads the paid figures from the answer`() {
        val paymentId = payAtWindow(server, provider)

        val result = provider.confirm(confirmRequest(paymentId))

        val apply = server.requests.last()
        assertEquals("/naverpay-partner/naverpay/payments/v2.2/apply/payment", apply.path)
        assertEquals(server.clientId, apply.header("x-naver-client-id"))
        assertEquals(server.clientSecret, apply.header("x-naver-client-secret"))
        assertEquals(server.chainId, apply.header("x-naverpay-chain-id"))
        assertEquals("apply-$paymentId", apply.header("x-naverpay-idempotency-key"))
        assertTrue(apply.header("content-type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals(mapOf("paymentId" to paymentId), apply.form)
        assertEquals(PaymentOperationStatus.CONFIRMED, result.status)
        assertEquals(PaymentAmount(3_000, "KRW"), result.amount)
        assertEquals(paymentId, result.providerPaymentId)
        assertEquals("ord_abcdef123456", result.trace.metadata["orderId"])
        assertNotNull(result.paidAt)
    }

    private val alerts = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Boolean>>()
    private val guarded = naverProvider(server, mismatchAlert = { _, orderId, cancelled -> alerts += orderId to cancelled })

    @Test
    fun `an approval that is not this order's - another order's payment id - is cancelled in full at once with a key, refused for good, and the owner is told`() {
        val others = payAtWindow(server, guarded, orderId = "ord_someone_else")

        val failure = assertFailsWith<Exception> { guarded.confirm(confirmRequest(others)) }

        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(failure), "nothing is paid for this order")
        assertEquals("ORDER_MISMATCH", PaymentFailures.providerCodeOf(failure))
        assertEquals(0, server.paymentOf(others)!!.available, "the money is back")
        assertEquals(listOf(3_000L), server.paymentOf(others)!!.cancels.map { it.second })
        val cancel = server.requests.last { it.path.endsWith("/v1/cancel") }
        assertEquals("mismatch-$others", cancel.header("x-naverpay-idempotency-key"))
        assertEquals(listOf("ord_abcdef123456" to true), alerts)
    }

    @Test
    fun `when the cancel of a mismatching approval cannot be confirmed the answer is unknown (the money is still held), the owner is told it failed, and a lookup that shows it refunded makes it final`() {
        val others = payAtWindow(server, guarded, orderId = "ord_someone_else")
        server.failNext("cancel", Fail.ERROR)

        val failure = assertFailsWith<Exception> { guarded.confirm(confirmRequest(others)) }

        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(failure))
        assertEquals(3_000, server.paymentOf(others)!!.available)
        assertEquals(listOf("ord_abcdef123456" to false), alerts)

        // 취소 응답이 유실됐어도 취소는 적용됐다 - 조회가 전액 환불을 보여 주면 확정이다
        alerts.clear()
        val lost = payAtWindow(server, guarded, orderId = "ord_someone_else")
        server.failNext("cancel", Fail.LOSE)
        val settled = assertFailsWith<Exception> { guarded.confirm(confirmRequest(lost)) }
        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(settled))
        assertEquals(listOf("ord_abcdef123456" to true), alerts)
    }

    @Test
    fun `a CancelNotComplete answer is pending - not a cancel, not a refusal - and nothing was cancelled`() {
        val paymentId = approved()
        server.failNext("cancel", Fail.NOT_COMPLETE)

        val result = provider.refund(PaymentRefundRequest(paymentId, PaymentAmount(1_000, "KRW"), idempotencyKey = IdempotencyKey("rfd_1")))

        assertEquals(PaymentOperationStatus.PENDING, result.status)
        assertNull(result.remaining)
        assertEquals(3_000, server.paymentOf(paymentId)!!.available)
    }

    @Test
    fun `a refusal the documentation says nothing moved for (no balance) is definitive even inside a 200, a card failure the module cannot be sure of stays unknown`() {
        val paymentId = payAtWindow(server, provider)
        server.failNext("apply", Fail.BALANCE)

        val refused = assertFailsWith<Exception> { provider.confirm(confirmRequest(paymentId)) }

        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(refused))
        assertEquals("NotEnoughAccountBalance", PaymentFailures.providerCodeOf(refused))
        assertEquals(dev.sumin.skeleton.payment.ConfirmDeclineCategory.LIMIT_OR_BALANCE, provider.confirmDeclineCategory("NotEnoughAccountBalance"))
        assertFalse(server.paymentOf(paymentId)!!.applied)
    }

    @Test
    fun `a payment id applied twice stays one payment - the second apply is an answer the ledger settles by lookup, never a definitive refusal`() {
        val paymentId = approved()

        // 결제 번호에서 만든 키가 같은 재요청이라 네이버페이는 적용하지 않고 처음 응답을 409 로 돌려준다
        val replay = assertFailsWith<Exception> { provider.confirm(confirmRequest(paymentId)) }
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(replay))
        assertEquals(1, server.payments().count { it.applied })
        assertEquals(PaymentAmount(3_000, "KRW"), provider.lookup(PaymentLookupQuery(paymentId, "ord_abcdef123456"))!!.total)
    }

    @Test
    fun `a lost apply answer is unknown, a resend with the same key is not applied again, and the lookup then says the payment is paid`() {
        val paymentId = payAtWindow(server, provider)
        server.failNext("apply", Fail.LOSE)

        val failure = assertFailsWith<Exception> { provider.confirm(confirmRequest(paymentId)) }

        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(failure))
        val resend = assertFailsWith<Exception> { provider.confirm(confirmRequest(paymentId)) }
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(resend))
        assertEquals(1, server.payments().count { it.applied })
        val snapshot = checkNotNull(provider.lookup(PaymentLookupQuery(paymentId, "ord_abcdef123456")))
        assertEquals(ProviderPaymentStatus.PAID, snapshot.status)
        assertEquals(PaymentAmount(3_000, "KRW"), snapshot.total)
        assertEquals("ord_abcdef123456", snapshot.orderId)
    }

    @Test
    fun `an apply without a well-formed payment id never reaches the provider`() {
        val calls = server.requests.size

        assertFailsWith<NaverPayIdentifierRejectedException> { provider.confirm(confirmRequest("2026/../x")) }
        assertFailsWith<NaverPayIdentifierRejectedException> { provider.confirm(confirmRequest("20261017NP1", orderId = "a b")) }
        assertFalse(provider.acceptsPaymentKey("a b/c"))
        assertTrue(provider.acceptsPaymentKey("20261017NP00000001"))
        assertEquals(calls, server.requests.size)
    }

    @Test
    fun `a refund sends the amount with the tax split that adds up, the fixed reason and the refund's key - and reads what is left from the answer`() {
        val paymentId = approved()

        val first = provider.refund(PaymentRefundRequest(paymentId, PaymentAmount(2_000, "KRW"), reason = "customer request", idempotencyKey = IdempotencyKey("rfd_1")))

        val cancel = server.requests.last()
        assertEquals("/naverpay-partner/naverpay/payments/v1/cancel", cancel.path)
        assertEquals("rfd_1", cancel.header("x-naverpay-idempotency-key"))
        assertEquals(mapOf("paymentId" to paymentId, "cancelAmount" to "2000", "taxScopeAmount" to "2000", "taxExScopeAmount" to "0", "cancelReason" to "customer request", "cancelRequester" to "2"), cancel.form)
        assertEquals(PaymentOperationStatus.REFUNDED, first.status)
        assertEquals(PaymentAmount(2_000, "KRW"), first.amount)
        assertEquals(PaymentAmount(1_000, "KRW"), first.remaining)
        assertEquals("true", first.attributes["partial"])
    }

    @Test
    fun `a refund of more than what is left is refused - the money stays where it was, and the ledger settles it by lookup`() {
        val paymentId = approved()
        provider.refund(PaymentRefundRequest(paymentId, PaymentAmount(2_000, "KRW"), idempotencyKey = IdempotencyKey("rfd_1")))

        val failure = assertFailsWith<Exception> { provider.refund(PaymentRefundRequest(paymentId, PaymentAmount(2_000, "KRW"), idempotencyKey = IdempotencyKey("rfd_2"))) }

        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(failure))
        assertEquals(1_000, server.paymentOf(paymentId)!!.available)
        assertEquals(listOf(2_000L), server.paymentOf(paymentId)!!.cancels.map { it.second })
    }

    @Test
    fun `a refund request sent again with the same key is not cancelled twice`() {
        val paymentId = approved()
        provider.refund(PaymentRefundRequest(paymentId, PaymentAmount(1_000, "KRW"), idempotencyKey = IdempotencyKey("rfd_1")))

        assertFailsWith<Exception> { provider.refund(PaymentRefundRequest(paymentId, PaymentAmount(1_000, "KRW"), idempotencyKey = IdempotencyKey("rfd_1"))) }

        assertEquals(listOf(1_000L), server.paymentOf(paymentId)!!.cancels.map { it.second })
    }

    @Test
    fun `refunding the rest ends the payment, and the lookup lists every cancel with the amount left`() {
        val paymentId = approved()
        provider.refund(PaymentRefundRequest(paymentId, PaymentAmount(1_000, "KRW")))
        val last = provider.refund(PaymentRefundRequest(paymentId, PaymentAmount(2_000, "KRW")))

        assertEquals(PaymentAmount(0, "KRW"), last.remaining)
        val snapshot = checkNotNull(provider.lookup(PaymentLookupQuery(paymentId, null)))
        assertEquals(ProviderPaymentStatus.REFUNDED, snapshot.status)
        assertEquals(listOf(1_000L, 2_000L), snapshot.refunds.map { it.amount.amount })
        assertEquals(PaymentAmount(0, "KRW"), snapshot.remaining)
    }

    @Test
    fun `the lookup knows nothing before the apply, asks nothing when only an order number is known, and reads a card refusal as failed`() {
        val paymentId = payAtWindow(server, provider)

        assertNull(provider.lookup(PaymentLookupQuery(paymentId, null)), "the buyer paid in the window but nobody applied: no money moved")
        val calls = server.requests.size
        assertNull(provider.lookup(PaymentLookupQuery(null, "ord_abcdef123456")))
        assertEquals(calls, server.requests.size)

        server.declinedAtIssuer(paymentId)
        assertEquals(ProviderPaymentStatus.FAILED, provider.lookup(PaymentLookupQuery(paymentId, null))!!.status)
    }

    @Test
    fun `a lookup that answers for another order is unreadable, never taken as this order's payment`() {
        val paymentId = approved()

        val failure = assertFailsWith<Exception> { provider.lookup(PaymentLookupQuery(paymentId, "ord_someone_else")) }

        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(failure))
    }

    @Test
    fun `wrong keys are a definitive refusal on apply, but never on a lookup`() {
        val paymentId = payAtWindow(server, provider)
        val impostor = NaverPayProvider(
            dev.sumin.skeleton.payment.naverpay.support.realHttpClient(),
            NaverPayProperties(enabled = true, clientId = server.clientId, clientSecret = "not-the-secret", chainId = server.chainId, baseUrl = server.baseUrl, returnUrl = dev.sumin.skeleton.payment.naverpay.support.RETURN_URL),
        )

        val apply = assertFailsWith<Exception> { impostor.confirm(confirmRequest(paymentId)) }
        val lookup = assertFailsWith<Exception> { impostor.lookup(PaymentLookupQuery(paymentId, null)) }

        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(apply))
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(lookup))
        assertFalse(server.paymentOf(paymentId)!!.applied)
    }

    @Test
    fun `a business refusal the module cannot be sure of is unknown with the code only, and the planted personal data appears nowhere`() {
        val paymentId = payAtWindow(server, provider)
        server.failNext("apply", Fail.DECLINE)

        val failure = assertFailsWith<Exception> { provider.confirm(confirmRequest(paymentId)) }

        val text = generateSequence<Throwable>(failure) { it.cause }.joinToString(" | ") { "${it.javaClass.name}: ${it.message}" }
        FakeNaverPayServer.PLANTED.ALL.forEach { assertFalse(it in text, "leaked $it in: $text") }
        assertFalse(server.clientSecret in text)
        assertEquals("Fail", PaymentFailures.providerCodeOf(failure))
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(failure))
    }

    @Test
    fun `the mode comes from the setting - development is TEST, production is LIVE`() {
        assertEquals(PaymentMode.TEST, provider.mode)
        assertEquals(PaymentMode.LIVE, NaverPayProperties(mode = NaverPayMode.PRODUCTION).paymentMode)
    }

    @Test
    fun `the api address follows the mode unless it is set - development and production hosts of the documentation`() {
        assertEquals("https://dev-pay.paygate.naver.com", NaverPayProperties(mode = NaverPayMode.DEVELOPMENT).apiBaseUrl)
        assertEquals("https://pay.paygate.naver.com", NaverPayProperties(mode = NaverPayMode.PRODUCTION).apiBaseUrl)
        assertEquals("http://localhost:9", NaverPayProperties(mode = NaverPayMode.PRODUCTION, baseUrl = " http://localhost:9 ").apiBaseUrl)
    }

    @Test
    fun `the properties never print the client secret or the ids`() {
        val properties = NaverPayProperties(enabled = true, clientId = "CLIENTIDVALUE", clientSecret = "SECRETVALUE", chainId = "CHAINIDVALUE", returnUrl = "https://x.test/r")

        assertFalse("SECRETVALUE" in properties.toString())
        assertFalse("CLIENTIDVALUE" in properties.toString())
        assertFalse("CHAINIDVALUE" in properties.toString())
    }

    @Test
    fun `the error mapper trusts a refusal as definitive only when the request was refused outright with a 4xx and the code is on the allow list`() {
        val mapper = NaverPayErrorMapper("naverpay")

        assertTrue(mapper.exceptionFor(code = "TimeExpired", upstreamStatus = 400, traceId = null, clientName = "c", method = "POST", uri = java.net.URI.create("http://x")).providerError.definitive)
        assertFalse(mapper.exceptionFor(code = "AlreadyComplete", upstreamStatus = 400, traceId = null, clientName = "c", method = "POST", uri = java.net.URI.create("http://x")).providerError.definitive)
        assertFalse(mapper.exceptionFor(code = "OverRemainAmount", upstreamStatus = 400, traceId = null, clientName = "c", method = "POST", uri = java.net.URI.create("http://x")).providerError.definitive)
        assertFalse(mapper.exceptionFor(code = "Fail", upstreamStatus = 400, traceId = null, clientName = "c", method = "POST", uri = java.net.URI.create("http://x")).providerError.definitive)
        assertFalse(mapper.exceptionFor(code = "TimeExpired", upstreamStatus = 200, traceId = null, clientName = "c", method = "POST", uri = java.net.URI.create("http://x")).providerError.definitive, "a refusal inside a 200 is not trusted as definitive")
    }
}
