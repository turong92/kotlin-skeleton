package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.http.ExternalHttpException
import dev.sumin.skeleton.payment.testing.FakeStoreGateway
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 앱 스토어 결제사가 장부에 닿는 길의 규칙(인앱결제 K1): 해석의 일시 실패는 재시도 · 거래마다 다른 모드(테스트 결제는 매출에서 빠진다) · 스토어가 과금하는 구독은 서버가 해지 · 변경하지 않는다.
 * 스토어 결제사는 `LIVE` 인데 줄 · 사건이 `TEST` 일 수 있다 - 대시보드 매출은 결제사의 지금 모드(`LIVE`)와 같은 모드의 주문만 센다.
 */
class StoreContractTest {
    private val store = FakeStoreGateway()
    private val kit = PaymentLedgerKit(push = store)
    private val t0: Instant = Instant.parse("2026-10-13T10:00:00Z")
    private val monthLater: Instant = Instant.parse("2026-11-13T10:00:00Z")
    private var seq = 0

    private fun pending(key: String = "k1"): PaymentSubscription = kit.subscriptions.start("acc_1", "basic", IdempotencyKey(key), provider = "appstore").subscription

    private fun payment(txn: String?, at: Instant = t0, mode: PaymentMode? = null, providerSub: String = "psub_1") =
        PushedPayment(
            eventId = "evt_${++seq}", occurredAt = at, providerPaymentId = txn ?: "txn_unknown", total = PaymentAmount(990, "USD"), tax = 90, paidAt = at, ourAccountId = "acc_1",
            providerSubscriptionId = providerSub, periodStart = at, periodEnd = at.plusSeconds(31 * 86400), items = listOf(PushedItem("pri_basic", 1)), mode = mode,
        )

    private fun feed(event: PushedPaymentEvent): WebhookReceipt =
        kit.deliver(store.delivery(event), token = null, provider = "appstore").also { kit.runWebhooks(); kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL)) }

    private fun sub(id: String) = checkNotNull(kit.store.findSubscription(id))

    private fun rows() = kit.store.listWebhookEvents(null, null, 100)

    /** 스토어 줄 하나(첫 결제가 켠 `ACTIVE`). [mode] 가 `TEST` 면 샌드박스 구매로 만든 줄이다. */
    private fun active(mode: PaymentMode = PaymentMode.LIVE): PaymentSubscription {
        val started = pending()
        if (mode != started.providerMode) kit.store.inTransaction { tx -> tx.updateSubscription(checkNotNull(tx.lockSubscription(started.id)).copy(providerMode = mode)) }
        feed(payment(started.firstTransactionId, mode = mode))
        return sub(started.id).also { assertEquals(SubscriptionStatus.ACTIVE, it.status) }
    }

    private fun providerUnavailable() = PaymentProviderException(
        PaymentProviderError("appstore", "unavailable", null, ProviderTrace("appstore"), 503, retryable = true),
        "fake-store", "GET", URI.create("http://store.invalid/purchases"),
    )

    @Test
    fun `a store call that fails while reading an event retries the inbox row - the row is not dead and the event lands once the store answers`() {
        val started = pending()
        store.interpretFailure = providerUnavailable()

        kit.deliver(store.delivery(payment(started.firstTransactionId)), token = null, provider = "appstore")
        kit.runWebhooks()

        assertEquals(PaymentWebhookStatus.FAILED, rows().single().status)
        assertEquals(SubscriptionStatus.PENDING, sub(started.id).status)

        store.interpretFailure = null
        kit.advance(Duration.ofHours(1))
        kit.runWebhooks()

        assertEquals(PaymentWebhookStatus.PROCESSED, rows().single().status)
        assertEquals(SubscriptionStatus.ACTIVE, sub(started.id).status)
    }

    @Test
    fun `any external http failure while reading an event is a retry too - only an unreadable payload is dead`() {
        val started = pending()
        store.interpretFailure = ExternalHttpException("timeout", "fake-store", "GET", URI.create("http://store.invalid/purchases"), null, true, errorCode = PaymentErrorCode.PROVIDER_ERROR)
        kit.deliver(store.delivery(payment(started.firstTransactionId)), token = null, provider = "appstore")
        kit.runWebhooks()
        assertEquals(PaymentWebhookStatus.FAILED, rows().single().status)

        store.interpretFailure = IllegalArgumentException("unreadable")
        kit.advance(Duration.ofHours(1))
        kit.runWebhooks()
        assertEquals(PaymentWebhookStatus.DEAD, rows().single().status)
    }

    @Test
    fun `a test purchase on a test line is applied and the order is a test order - the live store provider does not turn it into a live one`() {
        val line = active(PaymentMode.TEST)

        feed(payment("txn_renewal", at = monthLater, mode = PaymentMode.TEST))

        val renewal = kit.store.listSubscriptionOrders(line.id, 5).first { it.providerPaymentId == "txn_renewal" }
        assertEquals(PaymentMode.TEST, renewal.providerMode, "the dashboard counts only orders of the provider's mode (LIVE) - this one stays out of the revenue")
        assertEquals(PaymentMode.LIVE, store.mode)
        assertEquals(PaymentOrderStatus.PAID, renewal.status)
    }

    @Test
    fun `a live payment for a test line (or the other way round) is not applied - the line keeps its mode`() {
        val line = active(PaymentMode.TEST)

        feed(payment("txn_renewal", at = monthLater, mode = PaymentMode.LIVE))

        val ignored = rows().single { it.status == PaymentWebhookStatus.IGNORED }
        assertEquals(PaymentPushedEvents.MODE_MISMATCH, ignored.lastError)
        assertEquals(PaymentMode.TEST, sub(line.id).providerMode)
    }

    @Test
    fun `a state event and a refund that carry the test mode reach a test line`() {
        val line = active(PaymentMode.TEST)
        val paid = kit.store.listSubscriptionOrders(line.id, 5).single()

        feed(PushedSubscription("evt_${++seq}", monthLater, "psub_1", PushedSubscriptionStatus.CANCELING, periodStart = t0, periodEnd = monthLater, mode = PaymentMode.TEST))
        feed(PushedRefund("evt_${++seq}", monthLater, PushedRefundKind.REFUND, "re_1", paid.providerPaymentId!!, PushedRefundStatus.APPROVED, PaymentAmount(990, "USD"), mode = PaymentMode.TEST))

        assertEquals(SubscriptionStatus.CANCELING, sub(line.id).status)
        assertEquals(PaymentRefundStatus.SUCCEEDED, kit.store.listRefunds(paid.id).single().status)
        assertTrue(rows().all { it.status == PaymentWebhookStatus.PROCESSED }, rows().joinToString { "${it.status}:${it.lastError}" })
    }

    @Test
    fun `a store refund that is reversed turns its refund line to reversed and does not bring back the supply or the subscription`() {
        val line = active()
        val paid = kit.store.listSubscriptionOrders(line.id, 5).single()
        val refund = { status: PushedRefundStatus -> PushedRefund("evt_${++seq}", monthLater, PushedRefundKind.REFUND, "refund-txn", paid.providerPaymentId!!, status, PaymentAmount(990, "USD")) }
        feed(refund(PushedRefundStatus.APPROVED))
        kit.runTasks(setOf(PaymentTaskTypes.REFUND_APPLY, PaymentTaskTypes.ORDER_FULFIL))
        assertEquals(PaymentRefundStatus.SUCCEEDED, kit.store.listRefunds(paid.id).single().status)
        assertEquals(SubscriptionStatus.ENDED, sub(line.id).status)

        feed(refund(PushedRefundStatus.REVERSED))

        assertEquals(PaymentRefundStatus.REVERSED, kit.store.listRefunds(paid.id).single().status)
        assertEquals(PaymentOrderStatus.PAID, kit.order(paid.id).status)
        assertEquals(0, kit.order(paid.id).refundedAmount)
        assertEquals(SubscriptionStatus.ENDED, sub(line.id).status, "the subscription is not brought back by a reversal")
        assertEquals(1, kit.listener.refunded.size, "the supply is not given back either")
    }

    @Test
    fun `canceling, canceling now or changing the plan of a line the store bills is a 409 and the line and the provider stay untouched`() {
        val line = active()

        assertFailsWith<PaymentSubscriptionManagedByStoreException> { kit.subscriptions.cancel("acc_1", line.id) }
        assertFailsWith<PaymentSubscriptionManagedByStoreException> { kit.cancellations.cancelNow("acc_1", line.id) }
        assertFailsWith<PaymentSubscriptionManagedByStoreException> { kit.planChanges.preview("acc_1", line.id, "plus") }
        assertFailsWith<PaymentSubscriptionManagedByStoreException> { kit.planChanges.change("acc_1", line.id, "plus", 0, IdempotencyKey("c1")) }

        assertEquals(SubscriptionStatus.ACTIVE, sub(line.id).status)
        assertEquals(emptyList(), store.cancelCalls.toList(), "a store line is never canceled through the provider capability")
    }

    @Test
    fun `ending a store line for an erased account asks nobody to cancel it and leaves no dead job`() {
        val line = active()

        kit.store.inTransaction { tx -> kit.lifecycle.end(tx, checkNotNull(tx.lockSubscription(line.id)), SubscriptionEndReason.ACCOUNT_ERASED) }
        kit.runTasks(setOf(PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL))

        assertEquals(emptyList(), store.cancelCalls.toList())
        assertEquals(emptyList(), kit.tasks.dead(PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL))
        assertNotNull(sub(line.id).endedAt)
    }
}
