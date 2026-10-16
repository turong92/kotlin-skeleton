package dev.sumin.skeleton.payment.kakaopay

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
import dev.sumin.skeleton.payment.kakaopay.support.FakeKakaoPayServer
import dev.sumin.skeleton.payment.kakaopay.support.FakeKakaoPayServer.Fail
import dev.sumin.skeleton.payment.kakaopay.support.checkoutOrder
import dev.sumin.skeleton.payment.kakaopay.support.kakaoProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

/**
 * 카카오페이 한 빈의 승인 · 환불 · 조회 · 결제창 값 - 엄격한 가짜 카카오페이 서버(`FakeKakaoPayServer`)와 실제 HTTP 로 말한다.
 * 지키는 것: 금액이 다른 승인은 거절 · 같은 결제는 두 번 승인되지 않음 · 남은 금액을 넘는 환불은 거절 · 응답이 유실돼도 조회가 사실을 말함 · 개인정보 · 키는 어디에도 안 나옴.
 */
class KakaoPayProviderTest {
    private val server = FakeKakaoPayServer()
    private val provider = kakaoProvider(server)
    private val json = JsonMapper.builder().build()

    @AfterTest
    fun tearDown() = server.close()

    private fun confirmRequest(tid: String, token: String?, amount: Long = 3_000, orderId: String = "ord_abcdef123456") =
        PaymentConfirmRequest(
            providerPaymentId = tid, amount = PaymentAmount(amount, "KRW"), merchantReferenceId = orderId,
            idempotencyKey = IdempotencyKey(orderId), approvalToken = token, accountId = "acc_1",
        )

    /** 결제창을 열고(ready) 구매자가 인증을 마친 상태 - 승인 토큰을 돌려준다. */
    private fun readied(amount: Long = 3_000): Pair<String, String> {
        val tid = checkNotNull(provider.checkout(checkoutOrder(amount = amount)).providerPaymentId)
        return tid to server.userApproves(tid)
    }

    private fun approved(): String {
        val (tid, token) = readied()
        assertEquals(PaymentOperationStatus.CONFIRMED, provider.confirm(confirmRequest(tid, token)).status)
        return tid
    }

    @Test
    fun `the checkout calls ready on the server with the server's own amount, the order ids and the return addresses, and hands back the tid and the window addresses`() {
        val instruction = provider.checkout(checkoutOrder())

        val ready = server.requests.single()
        assertEquals("/online/v1/payment/ready", ready.path)
        assertEquals("SECRET_KEY ${server.secretKey}", ready.header("authorization"))
        val body = json.readValue(ready.body, Map::class.java)
        assertEquals("TC0ONETIME", body["cid"])
        assertEquals("ord_abcdef123456", body["partner_order_id"])
        assertEquals("acc_1", body["partner_user_id"])
        assertEquals(3000, body["total_amount"])
        assertEquals("https://app.example.test/payments/return?kind=KAKAOPAY_REDIRECT&provider=kakaopay&order=ord_abcdef123456&result=success", body["approval_url"])
        assertEquals("https://app.example.test/payments/return?kind=KAKAOPAY_REDIRECT&provider=kakaopay&order=ord_abcdef123456&result=cancel", body["cancel_url"])
        assertEquals("https://app.example.test/payments/return?kind=KAKAOPAY_REDIRECT&provider=kakaopay&order=ord_abcdef123456&result=fail", body["fail_url"])

        assertEquals("KAKAOPAY_REDIRECT", instruction.kind)
        assertTrue(instruction.testMode)
        assertNull(instruction.clientKey)
        val tid = checkNotNull(instruction.providerPaymentId)
        assertEquals("${server.baseUrl}/pay/$tid/info", instruction.attributes["pcUrl"])
        assertEquals("${server.baseUrl}/pay/$tid/mInfo", instruction.attributes["mobileUrl"])
    }

    @Test
    fun `a purchase in another currency than KRW is never sent to ready - 503 and no request`() {
        val usd = checkoutOrder().copy(amount = PaymentAmount(3_000, "USD"))

        assertFailsWith<dev.sumin.skeleton.payment.PaymentUnavailableException> { provider.checkout(usd) }

        assertEquals(0, server.requests.size)
    }

    @Test
    fun `the approval token shape is judged before the claim - missing and malformed are refused, a real looking one is accepted`() {
        assertFalse(provider.acceptsApprovalToken(null))
        assertFalse(provider.acceptsApprovalToken(""))
        assertFalse(provider.acceptsApprovalToken("a b/c"))
        assertTrue(provider.acceptsApprovalToken("pgtok_abc123"))
    }

    @Test
    fun `a return address that already has a query gets the pieces appended with ampersands`() {
        val withQuery = kakaoProvider(server, returnUrl = "https://app.example.test/pay?lang=ko")

        withQuery.checkout(checkoutOrder())

        val approval = json.readValue(server.requests.single().body, Map::class.java)["approval_url"] as String
        assertEquals("https://app.example.test/pay?lang=ko&kind=KAKAOPAY_REDIRECT&provider=kakaopay&order=ord_abcdef123456&result=success", approval)
    }

    @Test
    fun `an account id that looks like an email is not sent to the provider - the order id stands in, the same on ready and approve`() {
        val tid = checkNotNull(provider.checkout(checkoutOrder(accountId = "someone@example.test")).providerPaymentId)
        val token = server.userApproves(tid)

        provider.confirm(confirmRequest(tid, token).copy(accountId = "someone@example.test"))

        val sent = server.requests.map { json.readValue(it.body, Map::class.java)["partner_user_id"] }
        assertEquals(listOf("ord_abcdef123456", "ord_abcdef123456"), sent)
        assertFalse(server.requests.any { "someone@example.test" in it.body })
    }

    @Test
    fun `when the provider cannot prepare the payment the checkout is unavailable (503) and carries no tid`() {
        server.failNext("ready", Fail.ERROR)

        assertFailsWith<dev.sumin.skeleton.payment.PaymentUnavailableException> { provider.checkout(checkoutOrder()) }
    }

    @Test
    fun `approve sends the tid, the buyer's token and the ledger's amount - and reads the paid amount from the answer`() {
        val (tid, token) = readied()

        val result = provider.confirm(confirmRequest(tid, token))

        val body = json.readValue(server.requests.last().body, Map::class.java)
        assertEquals(mapOf("tid" to tid, "partner_order_id" to "ord_abcdef123456", "partner_user_id" to "acc_1", "pg_token" to token, "total_amount" to 3000, "cid" to "TC0ONETIME"), body)
        assertEquals(PaymentOperationStatus.CONFIRMED, result.status)
        assertEquals(PaymentAmount(3_000, "KRW"), result.amount)
        assertEquals(tid, result.providerPaymentId)
        assertEquals("ord_abcdef123456", result.trace.metadata["orderId"])
        assertNotNull(result.paidAt)
    }

    @Test
    fun `an approve for another amount than the one prepared is refused by the provider and never reads as paid`() {
        val (tid, token) = readied(amount = 3_000)

        val failure = assertFailsWith<Exception> { provider.confirm(confirmRequest(tid, token, amount = 2_999)) }

        assertEquals("SUCCESS_PAYMENT" == server.paymentOf(tid)!!.status, false)
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(failure), "an approval failure is never a definitive 'no money moved' - the lookup decides")
    }

    @Test
    fun `a payment that is approved twice stays one payment, and the second approve is a failure the ledger settles by lookup`() {
        val tid = approved()
        val token = server.userApproves(tid)

        val failure = assertFailsWith<Exception> { provider.confirm(confirmRequest(tid, token)) }

        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(failure))
        assertEquals(1, server.payments().count { it.status == "SUCCESS_PAYMENT" })
        assertEquals(PaymentAmount(3_000, "KRW"), provider.lookup(PaymentLookupQuery(tid, "ord_abcdef123456"))!!.total)
    }

    @Test
    fun `a lost approve answer is unknown, and the lookup then says the payment is paid`() {
        val (tid, token) = readied()
        server.failNext("approve", Fail.LOSE)

        val failure = assertFailsWith<Exception> { provider.confirm(confirmRequest(tid, token)) }

        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(failure))
        val snapshot = checkNotNull(provider.lookup(PaymentLookupQuery(tid, "ord_abcdef123456")))
        assertEquals(ProviderPaymentStatus.PAID, snapshot.status)
        assertEquals(PaymentAmount(3_000, "KRW"), snapshot.total)
        assertEquals("ord_abcdef123456", snapshot.orderId)
    }

    @Test
    fun `an approve without the buyer's token or with a malformed one never reaches the provider`() {
        val (tid, _) = readied()
        val calls = server.requests.size

        assertFailsWith<KakaoPayIdentifierRejectedException> { provider.confirm(confirmRequest(tid, null)) }
        assertFailsWith<KakaoPayIdentifierRejectedException> { provider.confirm(confirmRequest(tid, "a b/c")) }
        assertFailsWith<KakaoPayIdentifierRejectedException> { provider.confirm(confirmRequest("T1/../x", "tok")) }
        assertEquals(calls, server.requests.size)
    }

    @Test
    fun `a refund of more than what is left is refused - the money stays where it was`() {
        val tid = approved()
        val first = provider.refund(PaymentRefundRequest(tid, PaymentAmount(2_000, "KRW"), idempotencyKey = IdempotencyKey("rfd_1")))
        assertEquals(PaymentOperationStatus.REFUNDED, first.status)
        assertEquals(PaymentAmount(1_000, "KRW"), first.remaining)
        assertEquals("true", first.attributes["partial"])

        val failure = assertFailsWith<Exception> { provider.refund(PaymentRefundRequest(tid, PaymentAmount(2_000, "KRW"), idempotencyKey = IdempotencyKey("rfd_2"))) }

        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(failure))
        assertEquals(1_000, server.paymentOf(tid)!!.available)
        assertEquals(listOf(2_000L), server.paymentOf(tid)!!.cancels.map { it.second })
    }

    @Test
    fun `refunding the rest ends the payment, and the lookup lists every cancel with the amount left`() {
        val tid = approved()
        provider.refund(PaymentRefundRequest(tid, PaymentAmount(1_000, "KRW")))
        val last = provider.refund(PaymentRefundRequest(tid, PaymentAmount(2_000, "KRW")))

        assertEquals(PaymentAmount(0, "KRW"), last.remaining)
        val snapshot = checkNotNull(provider.lookup(PaymentLookupQuery(tid, null)))
        assertEquals(ProviderPaymentStatus.REFUNDED, snapshot.status)
        assertEquals(listOf(1_000L, 2_000L), snapshot.refunds.map { it.amount.amount })
        assertEquals(PaymentAmount(0, "KRW"), snapshot.remaining)
    }

    @Test
    fun `the lookup maps the stages of an unfinished payment to in progress, and asks nothing when only an order number is known`() {
        val (tid, _) = readied()

        assertEquals(ProviderPaymentStatus.IN_PROGRESS, provider.lookup(PaymentLookupQuery(tid, null))!!.status)

        val calls = server.requests.size
        assertNull(provider.lookup(PaymentLookupQuery(null, "ord_abcdef123456")), "no tid, no payment: a buyer who never approved moved no money")
        assertEquals(calls, server.requests.size)
    }

    @Test
    fun `a lookup that answers for another order is unreadable, never taken as this order's payment`() {
        val tid = approved()

        val failure = assertFailsWith<Exception> { provider.lookup(PaymentLookupQuery(tid, "ord_someone_else")) }

        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(failure))
    }

    @Test
    fun `a wrong secret key is a definitive refusal on approve, but never on a lookup`() {
        val wrong = FakeKakaoPayServer(secretKey = "another-key").also { it.close() }
        val (tid, token) = readied()
        val impostor = dev.sumin.skeleton.payment.kakaopay.support.kakaoProvider(server).let {
            KakaoPayProvider(dev.sumin.skeleton.payment.kakaopay.support.realHttpClient(), KakaoPayProperties(enabled = true, secretKey = "not-the-key", cid = server.cid, baseUrl = server.baseUrl, returnUrl = dev.sumin.skeleton.payment.kakaopay.support.RETURN_URL))
        }

        val approve = assertFailsWith<Exception> { impostor.confirm(confirmRequest(tid, token)) }
        val lookup = assertFailsWith<Exception> { impostor.lookup(PaymentLookupQuery(tid, null)) }

        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(approve))
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(lookup))
        assertEquals("-401", PaymentFailures.providerCodeOf(approve))
        assertEquals(wrong.secretKey, "another-key")
    }

    @Test
    fun `an answer the module cannot read is unknown with a fixed code, and the planted personal data appears nowhere`() {
        val (tid, token) = readied()
        server.failNext("approve", Fail.DECLINE)

        val failure = assertFailsWith<Exception> { provider.confirm(confirmRequest(tid, token)) }

        val text = generateSequence<Throwable>(failure) { it.cause }.joinToString(" | ") { "${it.javaClass.name}: ${it.message}" }
        FakeKakaoPayServer.PLANTED.ALL.forEach { assertFalse(it in text, "leaked $it in: $text") }
        assertFalse(server.secretKey in text)
        assertEquals("-780", PaymentFailures.providerCodeOf(failure))
    }

    @Test
    fun `the mode comes from the merchant code - the documented test codes are TEST, anything else is LIVE`() {
        assertEquals(PaymentMode.TEST, provider.mode)
        assertEquals(PaymentMode.LIVE, KakaoPayMerchant.modeOf("1234567890"))
        assertEquals(PaymentMode.UNKNOWN, KakaoPayMerchant.modeOf(" "))
    }

    @Test
    fun `the properties never print the secret key or the merchant code authentication key`() {
        val properties = KakaoPayProperties(enabled = true, secretKey = "SECRETVALUE", cid = "TC0ONETIME", cidSecret = "CIDSECRETVALUE", returnUrl = "https://x.test/r")

        assertFalse("SECRETVALUE" in properties.toString())
        assertFalse("CIDSECRETVALUE" in properties.toString())
        assertFalse("SECRETVALUE" in confirmRequest("T1", "pg_token_value").toString().replace("pg_token_value", ""))
        assertFalse("pg_token_value" in confirmRequest("T1", "pg_token_value").toString())
    }
}
