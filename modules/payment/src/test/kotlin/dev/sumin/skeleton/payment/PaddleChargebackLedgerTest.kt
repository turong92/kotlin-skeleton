package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.FakePushGateway
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.RecordingChargebackAlert
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Paddle 의 차지백(구매자가 카드사에 이의 제기)은 우리가 시킨 일이 아니라 Paddle 이 자동으로 만든 adjustment 다 - 서명된 웹훅으로 환불 줄에 남는다.
 * 지키는 것: 같은 이의를 두 번 세지 않음(경고 + 차지백) · 웹훅 재전송 · 이긴 분쟁의 되돌림이 한 번만(두 웹훅이 와도) · 되돌림이 먼저 오면 재시도 뒤 짝 · 주문 금액 · 상태가 증거로만 되돌아감 ·
 * 전액이면 구독 끝 + 결제사 해지 · 주인 알림은 주문 id 만.
 */
class PaddleChargebackLedgerTest {
    private val push = FakePushGateway()
    private val alerts = RecordingChargebackAlert()
    private val kit = PaymentLedgerKit(push = push, chargebacks = alerts)
    private val t0: Instant = Instant.parse("2026-10-13T10:00:00Z")
    private val usd = { amount: Long -> PaymentAmount(amount, "USD") }
    private var seq = 0

    private fun paidOrder(account: String = "acc_1", key: String = "o1"): PaymentOrder {
        val order = kit.orders.create(CreateOrderCommand(account, "cheer", "cheer-3000", IdempotencyKey(key), provider = "paddle")).order
        val txn = checkNotNull(kit.order(order.id).providerPaymentId)
        feed(PushedPayment("evt_${++seq}", t0, txn, usd(499), 40, t0, ourOrderId = order.id, items = listOf(PushedItem("pri_cheer-3000", 1))))
        return kit.order(order.id)
    }

    private fun feed(event: PushedPaymentEvent): WebhookReceipt =
        kit.deliver(push.delivery(event), token = null, provider = "paddle").also {
            kit.runWebhooks()
            // 회수 작업이 구독을 끝내며 결제사 해지 작업을 넣는다 - 한 번 더 돌아야 그 작업이 나간다
            repeat(2) { kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL, PaymentTaskTypes.REFUND_APPLY, PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL)) }
        }

    private fun chargeback(order: PaymentOrder, id: String, amount: Long = 499, warning: Boolean = false, status: PushedRefundStatus = PushedRefundStatus.APPROVED) =
        PushedRefund("evt_${++seq}", t0.plusSeconds(120), PushedRefundKind.CHARGEBACK, id, checkNotNull(order.providerPaymentId), status, usd(amount), warning)

    private fun reversal(order: PaymentOrder, id: String, amount: Long = 499) =
        PushedRefund("evt_${++seq}", t0.plusSeconds(600), PushedRefundKind.CHARGEBACK_REVERSAL, id, checkNotNull(order.providerPaymentId), PushedRefundStatus.APPROVED, usd(amount))

    private fun lines(order: PaymentOrder) = kit.store.listRefunds(order.id)

    @Test
    fun `a chargeback becomes one succeeded provider line with its own reason, takes the app's supply back once and alerts with the order id only`() {
        val order = paidOrder()

        val first = chargeback(order, "adj_cb1", amount = 100)
        feed(first)
        assertEquals(WebhookReceipt.DUPLICATE, feed(first))
        feed(chargeback(order, "adj_cb1", amount = 100)) // 같은 이의를 새 사건 id 로 다시

        val line = lines(order).single()
        assertEquals(PaymentRefundStatus.SUCCEEDED, line.status)
        assertEquals(PaymentRefundOrigin.PROVIDER, line.origin)
        assertEquals(PaymentRefundReason.CHARGEBACK.name, line.reasonCode)
        assertEquals(100, kit.order(order.id).refundedAmount)
        assertEquals(PaymentOrderStatus.PARTIALLY_REFUNDED, kit.order(order.id).status)
        assertEquals(1, kit.listener.refunded.size)
        assertEquals(listOf(order.id), alerts.chargebacks)
    }

    @Test
    fun `a warning and the chargeback of the same amount are one dispute - one line, counted once`() {
        val order = paidOrder()

        feed(chargeback(order, "adj_warn", warning = true))
        feed(chargeback(order, "adj_cb", warning = false))

        assertEquals(1, lines(order).size)
        assertEquals(499, kit.order(order.id).refundedAmount)
        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order.id).status)
        assertEquals(1, kit.listener.refunded.size)
    }

    @Test
    fun `a chargeback of another amount than its warning is a second line and a person is told the numbers disagree`() {
        val order = paidOrder()
        feed(chargeback(order, "adj_warn", amount = 100, warning = true))

        feed(chargeback(order, "adj_cb", amount = 150))

        assertEquals(2, lines(order).size)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
    }

    @Test
    fun `winning the dispute reverses the line once whichever webhook comes - the original turned reversed, the reverse adjustment, or both`() {
        val order = paidOrder()
        feed(chargeback(order, "adj_cb"))
        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order.id).status)

        feed(chargeback(order, "adj_cb", status = PushedRefundStatus.REVERSED))
        feed(reversal(order, "adj_reverse"))

        val line = lines(order).single()
        assertEquals(PaymentRefundStatus.REVERSED, line.status)
        assertEquals(0, kit.order(order.id).refundedAmount)
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(listOf(order.id), alerts.reversals)
        assertEquals(1, kit.store.listEvents(order.id).count { it.type == PaymentEventType.CHARGEBACK_REVERSED })
    }

    @Test
    fun `a reversal of a part of the money leaves the order partly refunded, and a reversal that arrives before its chargeback is retried and pairs after it`() {
        val order = paidOrder()
        feed(chargeback(order, "adj_cb_a", amount = 200))
        feed(chargeback(order, "adj_cb_b", amount = 100))
        assertEquals(300, kit.order(order.id).refundedAmount)

        feed(reversal(order, "adj_rev_a", amount = 200))
        assertEquals(PaymentOrderStatus.PARTIALLY_REFUNDED, kit.order(order.id).status)
        assertEquals(100, kit.order(order.id).refundedAmount)

        // 되돌림이 먼저 왔다: 짝 줄이 없어 처리가 실패로 남고, 차지백이 들어온 뒤 다시 돌아 짝이 맞는다
        val early = reversal(order, "adj_rev_c", amount = 77)
        kit.deliver(push.delivery(early), token = null, provider = "paddle")
        kit.runWebhooks()
        assertEquals(PaymentRefundStatus.SUCCEEDED, lines(order).first { it.amount.amount == 100L }.status)
        feed(chargeback(order, "adj_cb_c", amount = 77))
        kit.advance(Duration.ofHours(2))
        kit.runWebhooks()

        assertEquals(PaymentRefundStatus.REVERSED, lines(order).first { it.amount.amount == 77L }.status)
        assertEquals(100, kit.order(order.id).refundedAmount)
    }

    @Test
    fun `a full chargeback ends the subscription it paid for and cancels the provider's side, and winning does not bring either back`() {
        val pending = kit.subscriptions.start("acc_2", "basic", IdempotencyKey("k2"), provider = "paddle").subscription
        val first = PushedPayment(
            "evt_${++seq}", t0, checkNotNull(pending.firstTransactionId), usd(399), null, t0, ourAccountId = "acc_2", providerSubscriptionId = "psub_9",
            items = listOf(PushedItem("pri_basic", 1)),
        )
        feed(first)
        val order = kit.store.listSubscriptionOrders(pending.id, 1).single()
        assertEquals(SubscriptionStatus.ACTIVE, checkNotNull(kit.store.findSubscription(pending.id)).status)

        feed(PushedRefund("evt_${++seq}", t0.plusSeconds(60), PushedRefundKind.CHARGEBACK, "adj_cbs", checkNotNull(order.providerPaymentId), PushedRefundStatus.APPROVED, usd(399)))

        assertEquals(SubscriptionEndReason.REFUNDED, checkNotNull(kit.store.findSubscription(pending.id)).endReason)
        assertEquals(listOf("psub_9"), push.cancelCalls.map { it.first })

        feed(PushedRefund("evt_${++seq}", t0.plusSeconds(900), PushedRefundKind.CHARGEBACK_REVERSAL, "adj_rvs", checkNotNull(order.providerPaymentId), PushedRefundStatus.APPROVED, usd(399)))
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(SubscriptionStatus.ENDED, checkNotNull(kit.store.findSubscription(pending.id)).status, "the operator decides whether to give the subscription back")
    }

    @Test
    fun `an order goes from refunded back to paid only on the provider's evidence - the order transition table never allows it`() {
        assertFalse(PaymentOrderTransitions.isAllowed(PaymentOrderStatus.REFUNDED, PaymentOrderStatus.PAID))
        assertFalse(PaymentOrderTransitions.isAllowed(PaymentOrderStatus.REFUNDED, PaymentOrderStatus.PARTIALLY_REFUNDED))
        assertFalse(PaymentOrderTransitions.isAllowed(PaymentOrderStatus.PARTIALLY_REFUNDED, PaymentOrderStatus.PAID))
        assertTrue(PaymentOrderTransitions.isAllowedByProviderEvidence(PaymentOrderStatus.REFUNDED, PaymentOrderStatus.PAID))
        assertTrue(PaymentOrderTransitions.isAllowedByProviderEvidence(PaymentOrderStatus.PARTIALLY_REFUNDED, PaymentOrderStatus.PAID))
        assertFailsWith<IllegalStateException> { PaymentOrderTransitions.requireByProviderEvidence(PaymentOrderStatus.CANCELED, PaymentOrderStatus.REFUNDED) }
        // 환불 줄도 마찬가지: 이뤄진 줄만 되돌려진다
        assertTrue(PaymentRefundTransitions.isAllowed(PaymentRefundStatus.SUCCEEDED, PaymentRefundStatus.REVERSED))
        assertFalse(PaymentRefundTransitions.isAllowed(PaymentRefundStatus.PENDING, PaymentRefundStatus.REVERSED))
        assertFalse(PaymentRefundTransitions.isAllowed(PaymentRefundStatus.REVERSED, PaymentRefundStatus.SUCCEEDED))
    }

    @Test
    fun `a chargeback the provider shows only in its transaction reads as a chargeback line when the operator matches the ledger`() {
        val order = paidOrder()
        push.consoleRefund(checkNotNull(order.providerPaymentId), usd(499), status = "approved", chargeback = true)

        kit.refunds.sync("op_1", order.id)

        val line = lines(order).single()
        assertEquals(PaymentRefundReason.CHARGEBACK.name, line.reasonCode)
        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order.id).status)
    }
}
