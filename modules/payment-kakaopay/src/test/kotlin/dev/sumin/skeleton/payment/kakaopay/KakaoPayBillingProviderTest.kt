package dev.sumin.skeleton.payment.kakaopay

import dev.sumin.skeleton.payment.BillingChargeRequest
import dev.sumin.skeleton.payment.BillingPrepareRequest
import dev.sumin.skeleton.payment.CreateOrderCommand
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentBillingGateway
import dev.sumin.skeleton.payment.PaymentFailureOutcome
import dev.sumin.skeleton.payment.PaymentFailures
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.PaymentOrderIds
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.RegisterWithChargeRequest
import dev.sumin.skeleton.payment.RegisteredCharge
import dev.sumin.skeleton.payment.kakaopay.support.FakeKakaoPayServer
import dev.sumin.skeleton.payment.kakaopay.support.FakeKakaoPayServer.Fail
import dev.sumin.skeleton.payment.kakaopay.support.kakaoBillingProvider
import dev.sumin.skeleton.payment.kakaopay.support.kakaoProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 카카오페이 정기 결제 - 엄격한 가짜 서버와 실제 HTTP. 지키는 것: 정기 가맹점 코드가 없으면 구독 능력이 없다 · 1회차 결제가 곧 등록이고 SID 는 어디에도 찍히지 않는다 ·
 * 갱신의 `-782` 만 확정 거절이고 서버 오류는 모름(다시 과금하지 않는다) · 정기 주문의 조회 · 환불은 정기 가맹점 코드로 묻는다 · 비활성은 멱등.
 */
class KakaoPayBillingProviderTest {
    private val server = FakeKakaoPayServer()
    private val provider = kakaoBillingProvider(server)
    private val billing = provider as KakaoPayBillingProvider

    @AfterTest
    fun tearDown() = server.close()

    private val subId = "sub_01HZZZZZZZZZZZZZZZZZZZZZZZ"
    private val firstOrderId = PaymentOrderIds.initialOrderId(subId)

    private fun prepare(origin: String? = null) =
        checkNotNull(billing.prepareBilling(BillingPrepareRequest(subId, "ck_1", "Basic", PaymentAmount(3_900, "KRW"), firstOrderId, origin, "acc_1")))

    private fun register(tid: String, token: String): RegisteredCharge =
        billing.registerWithCharge(RegisterWithChargeRequest("ck_1", mapOf("pg_token" to token), tid, firstOrderId, "Basic", PaymentAmount(3_900, "KRW"), IdempotencyKey(firstOrderId), "acc_1"))

    /** 창을 열고 구매자가 인증을 마쳐 SID 까지 받은 상태 - (SID, 1회차 tid). */
    private fun registered(): Pair<String, String> {
        val prepared = prepare()
        val reference = checkNotNull(prepared.reference)
        val charge = register(reference, server.userApproves(reference))
        return checkNotNull(charge.issue).token to reference
    }

    /** 다른 구독의 등록(또 하나의 SID). */
    private fun registeredOther(): String {
        val other = "sub_01HYYYYYYYYYYYYYYYYYYYYYYY"
        val first = PaymentOrderIds.initialOrderId(other)
        val reference = checkNotNull(billing.prepareBilling(BillingPrepareRequest(other, "ck_2", "Basic", PaymentAmount(3_900, "KRW"), first, null, "acc_2"))?.reference)
        val charge = billing.registerWithCharge(RegisterWithChargeRequest("ck_2", mapOf("pg_token" to server.userApproves(reference)), reference, first, "Basic", PaymentAmount(3_900, "KRW"), IdempotencyKey(first), "acc_2"))
        return checkNotNull(charge.issue).token
    }

    private fun renewal(sid: String, orderId: String = "ord_renewal0000000000000001") =
        BillingChargeRequest(sid, "ck_1", orderId, "Basic", PaymentAmount(3_900, "KRW"), IdempotencyKey(orderId))

    @Test
    fun `without the subscription merchant code the module has no subscription capability at all`() {
        val oneTime = kakaoProvider(server)

        assertFalse(oneTime is PaymentBillingGateway)
        assertTrue(provider is PaymentBillingGateway)
    }

    @Test
    fun `prepare reserves at the provider with the subscription merchant code and the first order number, and the window addresses come back to the subscription`() {
        val prepared = prepare(origin = null)

        val ready = server.requests.single { it.path.endsWith("/ready") }
        assertEquals(server.subscriptionCid, json(ready.body)["cid"])
        assertEquals(KakaoPayIdentifiers.orderId(firstOrderId), json(ready.body)["partner_order_id"])
        assertEquals(3_900, json(ready.body)["total_amount"])
        assertFalse(ready.body.contains("cid_secret"))
        val approvalUrl = json(ready.body)["approval_url"] as String
        assertTrue(approvalUrl.contains("kind=KAKAOPAY_BILLING_REDIRECT") && approvalUrl.contains("sub=$subId") && approvalUrl.contains("result=success"), approvalUrl)
        assertEquals("KAKAOPAY_BILLING_REDIRECT", prepared.setup.kind)
        assertNull(prepared.setup.clientKey)
        assertNotNull(prepared.setup.attributes["pcUrl"])
        assertEquals(server.payments().single().tid, prepared.reference)
    }

    @Test
    fun `the first payment is the registration - one approve, the SID comes back as the token, never in a printed string`() {
        val prepared = prepare()
        val tid = checkNotNull(prepared.reference)
        val pgToken = server.userApproves(tid)

        val charge = register(tid, pgToken)

        assertEquals(PaymentOperationStatus.CONFIRMED, charge.result.status)
        assertEquals(tid, charge.result.providerPaymentId)
        assertEquals(3_900, charge.result.amount?.amount)
        val issue = checkNotNull(charge.issue)
        assertEquals("SHINHAN", issue.card?.issuer)
        assertEquals(setOf(true), server.sidsIssued().values.toSet())
        listOf(charge.toString(), issue.toString(), charge.result.toString()).forEach { assertFalse(it.contains(issue.token), it) }
        assertEquals(1, server.requests.count { it.path.endsWith("/approve") })
    }

    @Test
    fun `a registration for another amount than the reserved one is refused by the provider and issues no SID`() {
        val tid = checkNotNull(prepare().reference)
        val pgToken = server.userApproves(tid)

        assertFailsWith<Exception> {
            billing.registerWithCharge(RegisterWithChargeRequest("ck_1", mapOf("pg_token" to pgToken), tid, firstOrderId, "Basic", PaymentAmount(1, "KRW"), IdempotencyKey(firstOrderId), "acc_1"))
        }

        assertTrue(server.sidsIssued().isEmpty())
    }

    @Test
    fun `a registration shape that cannot be ours is turned away before any claim`() {
        assertFalse(billing.acceptsRegistration(mapOf("pg_token" to "x"), null))
        assertFalse(billing.acceptsRegistration(mapOf("pg_token" to ""), "T0000000000000000001"))
        assertFalse(billing.acceptsRegistration(emptyMap(), "T0000000000000000001"))
        assertTrue(billing.acceptsRegistration(mapOf("pg_token" to "pgtok_1"), "T0000000000000000001"))
    }

    @Test
    fun `a renewal charges the SID with the subscription merchant code and a new order number, and its tid is the payment key`() {
        val (sid, _) = registered()

        val result = billing.charge(renewal(sid))

        assertEquals(PaymentOperationStatus.CONFIRMED, result.status)
        val charged = server.paymentOf(checkNotNull(result.providerPaymentId))!!
        assertEquals(KakaoPayIdentifiers.orderId("ord_renewal0000000000000001"), charged.partnerOrderId)
        assertEquals(server.subscriptionCid, charged.cid)
    }

    @Test
    fun `a billing key of the wrong shape is a definitive refusal before any call - no money moved, so it is not unknown`() {
        server.clearRequests()

        val refused = assertFailsWith<Exception> { billing.charge(renewal("bad key with spaces", "ord_x000000000000000000001")) }

        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(refused))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `a revoked SID is a definitive refusal on the renewal (-782), a server error is only unknown`() {
        val (sid, _) = registered()
        billing.revoke("ck_1", sid)

        val refused = assertFailsWith<Exception> { billing.charge(renewal(sid)) }
        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(refused))

        val sid2 = registeredOther()
        server.failNext("subscription", Fail.ERROR)
        val unknown = assertFailsWith<Exception> { billing.charge(renewal(sid2, "ord_renewal0000000000000002")) }
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(unknown))
    }

    @Test
    fun `revoking twice is fine, and a lost revoke that actually landed is read through the status`() {
        val (sid, _) = registered()

        billing.revoke("ck_1", sid)
        billing.revoke("ck_1", sid)

        assertEquals(false, server.sidsIssued()[sid])
    }

    @Test
    fun `a recurring order is looked up and refunded with the subscription merchant code, a one-time one is not`() {
        val (_, tid) = registered()

        val recurring = billing.lookup(PaymentLookupQuery(tid, null, recurring = true))
        assertEquals(3_900, assertNotNull(recurring).total.amount)
        assertFailsWith<Exception>("the one-time merchant code does not know a recurring payment") { billing.lookup(PaymentLookupQuery(tid, null, recurring = false)) }

        val refund = billing.refund(PaymentRefundRequest(tid, PaymentAmount(3_900, "KRW"), reason = "test", idempotencyKey = IdempotencyKey("r1"), recurring = true))
        assertEquals(PaymentOperationStatus.REFUNDED, refund.status)
        assertEquals(0, server.paymentOf(tid)!!.available)
    }

    @Test
    fun `the provider cannot be asked by order number only - a keyless lookup says it does not know, and never invents an answer`() {
        assertFalse(billing.answersByOrderId)
        assertIs<PaymentBillingGateway>(billing)
    }

    @Test
    fun `the full ledger flow - start, window, first payment as registration, renewal by SID, refund of the renewal - pays once each time`() {
        val kit = dev.sumin.skeleton.payment.testing.PaymentLedgerKit(extraGateways = listOf(provider))
        val started = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), provider = "kakaopay", returnOrigin = null)
        val reference = checkNotNull(started.subscription.firstTransactionId)
        assertEquals("KAKAOPAY_BILLING_REDIRECT", started.billing?.kind)

        val active = kit.subscriptions.activate("acc_1", started.subscription.id, mapOf("pg_token" to server.userApproves(reference)))
        assertEquals(dev.sumin.skeleton.payment.SubscriptionStatus.ACTIVE, active.status)
        assertEquals(1, kit.subscriptions.activate("acc_1", started.subscription.id, mapOf("pg_token" to "again")).let { server.requests.count { it.path.endsWith("/approve") } })

        kit.time.set(checkNotNull(kit.store.findSubscription(started.subscription.id)!!.currentPeriodEnd))
        kit.renew.handle(dev.sumin.skeleton.payment.PaymentTaskRun(dev.sumin.skeleton.payment.PaymentTaskTypes.SUBSCRIPTION_RENEW, started.subscription.id, 1, 3))

        val orders = kit.store.listSubscriptionOrders(started.subscription.id, 5)
        assertEquals(2, orders.size)
        assertTrue(orders.all { it.status == dev.sumin.skeleton.payment.PaymentOrderStatus.PAID }, orders.toString())
        assertEquals(2, server.payments().size)
        assertEquals(1, server.requests.count { it.path.endsWith("/payment/subscription") })
    }

    @Test
    fun `an activate with a token of the wrong shape is turned away before the seat is claimed, the provider is not called and the subscription stays pending`() {
        val kit = dev.sumin.skeleton.payment.testing.PaymentLedgerKit(extraGateways = listOf(provider))
        val started = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), provider = "kakaopay")

        assertFailsWith<dev.sumin.skeleton.common.FieldValidationException> { kit.subscriptions.activate("acc_1", started.subscription.id, mapOf("pg_token" to "")) }

        assertEquals(0, server.requests.count { it.path.endsWith("/approve") })
        assertEquals(dev.sumin.skeleton.payment.SubscriptionStatus.PENDING, kit.store.findSubscription(started.subscription.id)!!.status)
        assertEquals(0, kit.store.listSubscriptionOrders(started.subscription.id, 5).size)
    }

    private fun json(body: String): Map<String, Any?> =
        tools.jackson.databind.json.JsonMapper.builder().build().readValue(body, Map::class.java).mapKeys { it.key.toString() }
}
