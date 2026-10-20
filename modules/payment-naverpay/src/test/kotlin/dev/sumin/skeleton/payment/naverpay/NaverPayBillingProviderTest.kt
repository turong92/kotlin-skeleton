package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.payment.BillingChargeRequest
import dev.sumin.skeleton.payment.BillingPrepareRequest
import dev.sumin.skeleton.payment.ConfirmDeclineCategory
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentBillingGateway
import dev.sumin.skeleton.payment.PaymentFailureOutcome
import dev.sumin.skeleton.payment.PaymentFailures
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.PaymentOrderIds
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.ProviderPaymentStatus
import dev.sumin.skeleton.payment.SubscriptionStatus
import dev.sumin.skeleton.payment.naverpay.support.FakeNaverPayServer
import dev.sumin.skeleton.payment.naverpay.support.FakeNaverPayServer.Fail
import dev.sumin.skeleton.payment.naverpay.support.naverBillingProvider
import dev.sumin.skeleton.payment.naverpay.support.naverProvider
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 네이버페이 정기 결제 - 엄격한 가짜 서버와 실제 HTTP. 지키는 것: 정기 인증값이 없으면 구독 능력이 없다 · 등록은 돈이 움직이지 않고 돌아온 `reserveId` 가 예약한 것과 다르면 결제사를 부르지 않는다 ·
 * 예약 실패는 확정 거절(승인 호출 없음) · 승인이 모르면 `paymentId` 를 쥔 채 모름으로 돌려주고 조회가 맞춘다 · 해지는 멱등 · 정기 주문은 정기 인증값으로 묻고 환불한다 · 이메일 같은 값은 어디에도 남지 않는다.
 */
class NaverPayBillingProviderTest {
    private val server = FakeNaverPayServer()
    private val provider = naverBillingProvider(server)
    private val billing = provider as NaverPayBillingProvider

    @AfterTest
    fun tearDown() = server.close()

    private val subId = "sub_01HZZZZZZZZZZZZZZZZZZZZZZZ"

    private fun prepare(id: String = subId) =
        checkNotNull(billing.prepareBilling(BillingPrepareRequest(id, "ck_1", "Basic", PaymentAmount(3_900, "KRW"), PaymentOrderIds.initialOrderId(id), null, "acc_1")))

    /** 등록 예약 → 구매자가 창에서 마침 → 등록 완료 - recurrentId. */
    private fun registered(id: String = subId): String {
        val reserveId = checkNotNull(prepare(id).reference)
        val receipt = server.buyerRegisters(reserveId)
        return billing.issue("ck_1", mapOf("reserveId" to reserveId, "tempReceiptId" to receipt), reserveId).token
    }

    private fun charge(recurrentId: String, orderId: String = "ord_renewal0000000000000001") =
        billing.charge(BillingChargeRequest(recurrentId, "ck_1", orderId, "Basic", PaymentAmount(3_900, "KRW"), IdempotencyKey(orderId)))

    @Test
    fun `without the recurrent credentials the module has no subscription capability at all`() {
        assertFalse(naverProvider(server) is PaymentBillingGateway)
        assertTrue(provider is PaymentBillingGateway)
    }

    @Test
    fun `the configuration prints neither the recurrent secret nor the ids`() {
        val text = dev.sumin.skeleton.payment.naverpay.support.naverProperties(server, recurrent = true).toString()
        listOf(server.recurrentClientSecret, server.recurrentClientId, server.recurrentChainId).forEach { assertFalse(text.contains(it), text) }
    }

    @Test
    fun `preparing reserves the registration - no money, the subscription id as product code, the recurrent credentials, and the window address with the reserve id`() {
        val prepared = prepare()

        val request = server.requests.single { it.path.endsWith("/recurrent/regist/v1/reserve") }
        assertEquals(server.recurrentClientId, request.header("x-naver-client-id"))
        assertEquals(server.recurrentChainId, request.header("x-naverpay-chain-id"))
        assertTrue(request.body.contains("\"productCode\":\"$subId\""), request.body)
        assertTrue(request.body.contains("kind=NAVERPAY_BILLING_REDIRECT") && request.body.contains("sub=$subId"), request.body)
        assertEquals("NAVERPAY_BILLING_REDIRECT", prepared.setup.kind)
        assertEquals(prepared.reference, prepared.setup.attributes.getValue("windowUrl").substringAfterLast('/'))
        assertTrue(prepared.setup.attributes.getValue("windowUrl").contains("/payments/recurrent/"))
        assertTrue(server.payments().isEmpty(), "registration moves no money")
        assertEquals(0, server.requests.count { it.path.contains("/pay/") })
    }

    @Test
    fun `the registration token is the recurrent id, and the masked email and other planted values are nowhere`() {
        val reserveId = checkNotNull(prepare().reference)
        val receipt = server.buyerRegisters(reserveId)

        val issue = billing.issue("ck_1", mapOf("reserveId" to reserveId, "tempReceiptId" to receipt), reserveId)

        assertTrue(server.recurrents().getValue(issue.token))
        listOf(issue.toString(), issue.card.toString()).forEach { text -> assertFalse(text.contains(issue.token), text) }
        FakeNaverPayServer.PLANTED.ALL.forEach { planted -> assertFalse(issue.toString().contains(planted), planted) }
    }

    @Test
    fun `a returned reserve id that is not the one reserved for this subscription never reaches the provider`() {
        val mine = checkNotNull(prepare().reference)
        val others = checkNotNull(prepare("sub_01HYYYYYYYYYYYYYYYYYYYYYYY").reference)
        val receipt = server.buyerRegisters(others)
        server.clearRequests()

        assertFalse(billing.acceptsRegistration(mapOf("reserveId" to others, "tempReceiptId" to receipt), mine))
        assertFailsWith<NaverPayIdentifierRejectedException> { billing.issue("ck_1", mapOf("reserveId" to others, "tempReceiptId" to receipt), mine) }

        assertTrue(server.requests.isEmpty())
        assertTrue(server.recurrents().isEmpty())
        assertFalse(billing.acceptsRegistration(mapOf("reserveId" to mine), mine), "the receipt is needed")
        assertFalse(billing.acceptsRegistration(mapOf("reserveId" to mine, "tempReceiptId" to receipt), null))
    }

    @Test
    fun `a renewal reserves then approves with the recurrent credentials, the order id as merchant pay id, and the payment id is the payment key`() {
        val recurrentId = registered()

        val result = charge(recurrentId)

        assertEquals(PaymentOperationStatus.CONFIRMED, result.status)
        val payment = server.paymentOf(result.providerPaymentId)!!
        assertEquals("ord_renewal0000000000000001", payment.merchantPayKey)
        assertTrue(payment.recurring)
        val reserve = server.requests.single { it.path.endsWith("/recurrent/pay/v3/reserve") }
        assertEquals("reserve-ord_renewal0000000000000001", reserve.header("x-naverpay-idempotency-key"))
        assertEquals(server.recurrentClientSecret, reserve.header("x-naver-client-secret"))
        assertEquals("approve-${result.providerPaymentId}", server.requests.single { it.path.endsWith("/recurrent/pay/v3/approval") }.header("x-naverpay-idempotency-key"))
    }

    @Test
    fun `a registration number of the wrong shape is a definitive refusal before any call - no money moved, so it is not unknown`() {
        server.clearRequests()

        val refused = assertFailsWith<Exception> { charge("bad id with spaces") }

        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(refused))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `a failed reserve is a definitive refusal - the approval is never called and no money moved`() {
        val recurrentId = registered()
        billing.revoke("ck_1", recurrentId)

        val refused = assertFailsWith<Exception> { charge(recurrentId) }

        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(refused))
        assertEquals(NaverPayErrorMapper.RESERVE_FAILED, PaymentFailures.providerCodeOf(refused))
        assertEquals(ConfirmDeclineCategory.TEMPORARY, billing.billingDeclineCategory(PaymentFailures.providerCodeOf(refused)))
        assertEquals(0, server.requests.count { it.path.endsWith("/recurrent/pay/v3/approval") })
        assertTrue(server.payments().isEmpty())
    }

    @Test
    fun `an approval the provider refuses for balance is definitive, one it fails or loses is unknown and keeps the reserved payment id`() {
        val recurrentId = registered()
        server.failNext("pay-approval", Fail.BALANCE)
        val refused = assertFailsWith<Exception> { charge(recurrentId, "ord_renewal0000000000000001") }
        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(refused))

        server.failNext("pay-approval", Fail.LOSE) // 승인은 적용됐고 응답만 유실
        val lost = charge(recurrentId, "ord_renewal0000000000000002")
        assertEquals(PaymentOperationStatus.UNKNOWN, lost.status)
        assertNotNull(lost.providerPaymentId)
        val found = billing.lookup(PaymentLookupQuery(lost.providerPaymentId, null, recurring = true))
        assertEquals(ProviderPaymentStatus.PAID, assertNotNull(found).status)

        server.failNext("pay-approval", Fail.DECLINE) // 「Fail」 - 확신하지 못한 코드
        assertEquals(PaymentOperationStatus.UNKNOWN, charge(recurrentId, "ord_renewal0000000000000003").status)
    }

    @Test
    fun `revoking twice is fine - an already expired registration reads as done`() {
        val recurrentId = registered()

        billing.revoke("ck_1", recurrentId)
        billing.revoke("ck_1", recurrentId)

        assertEquals(false, server.recurrents()[recurrentId])
    }

    @Test
    fun `a recurring order is looked up and refunded with the recurrent credentials - the one-time credentials are refused`() {
        val result = charge(registered())
        val paymentId = result.providerPaymentId

        assertEquals(3_900, assertNotNull(billing.lookup(PaymentLookupQuery(paymentId, null, recurring = true))).total.amount)
        assertFailsWith<Exception>("the one-time credentials do not open a recurring payment") { billing.lookup(PaymentLookupQuery(paymentId, null, recurring = false)) }

        val refund = billing.refund(PaymentRefundRequest(paymentId, PaymentAmount(3_900, "KRW"), reason = "test", idempotencyKey = IdempotencyKey("r1"), recurring = true))
        assertEquals(PaymentOperationStatus.REFUNDED, refund.status)
        assertEquals(0, server.paymentOf(paymentId)!!.available)
    }

    @Test
    fun `the full ledger flow - start, window, registration moves no money, the first charge and a renewal pay once each, and a lost renewal answer is settled by the lookup`() {
        val kit = PaymentLedgerKit(extraGateways = listOf(provider))
        val started = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), provider = "naverpay")
        val reserveId = checkNotNull(started.subscription.firstTransactionId)
        assertEquals("NAVERPAY_BILLING_REDIRECT", started.billing?.kind)

        val active = kit.subscriptions.activate("acc_1", started.subscription.id, mapOf("reserveId" to reserveId, "tempReceiptId" to server.buyerRegisters(reserveId)))
        assertEquals(SubscriptionStatus.ACTIVE, active.status)
        assertEquals(1, server.payments().size)
        assertEquals(setOf(true), server.recurrents().values.toSet())

        server.failNext("pay-approval", Fail.LOSE)
        kit.time.set(checkNotNull(kit.store.findSubscription(started.subscription.id)!!.currentPeriodEnd))
        kit.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, started.subscription.id, 1, 3))
        val renewal = kit.store.listSubscriptionOrders(started.subscription.id, 5).first { it.id != PaymentOrderIds.initialOrderId(started.subscription.id) }
        assertEquals(PaymentOrderStatus.CONFIRMING, renewal.status)
        assertNotNull(renewal.providerPaymentId, "the reserved payment id is kept for the lookup")

        kit.advance(Duration.ofMinutes(5))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE, PaymentTaskTypes.ORDER_FULFIL))
        assertEquals(PaymentOrderStatus.PAID, kit.store.findOrder(renewal.id)!!.status)
        assertEquals(2, server.payments().size, "two payments in all, none doubled")
    }

    @Test
    fun `an activate with a reserve id of another subscription is turned away before the seat is claimed`() {
        val kit = PaymentLedgerKit(extraGateways = listOf(provider))
        val started = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), provider = "naverpay")
        val others = checkNotNull(prepare("sub_01HYYYYYYYYYYYYYYYYYYYYYYY").reference)
        val receipt = server.buyerRegisters(others)

        assertFailsWith<dev.sumin.skeleton.common.FieldValidationException> { kit.subscriptions.activate("acc_1", started.subscription.id, mapOf("reserveId" to others, "tempReceiptId" to receipt)) }

        assertEquals(0, server.requests.count { it.path.endsWith("/regist/v1/approval") })
        assertEquals(SubscriptionStatus.PENDING, kit.store.findSubscription(started.subscription.id)!!.status)
    }
}
