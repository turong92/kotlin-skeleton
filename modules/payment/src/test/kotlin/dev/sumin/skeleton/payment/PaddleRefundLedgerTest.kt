package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.FakePushGateway.RefundScript
import dev.sumin.skeleton.payment.testing.FakePushGateway
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 서버가 Paddle 에 환불(adjustment)을 시키는 길. Paddle 은 대부분 검토(`pending_approval`) 뒤 승인 · 거절을 **웹훅**으로 알린다 - 장부는 그 서명된 사건으로 줄을 끝낸다.
 * 지키는 것: 이중 환불 없음(검토 중이면 새 환불을 안 보낸다 · 응답이 유실돼도 다시 안 보낸다) · 같은 사건 두 번 · 순서 뒤바뀜에도 한 결과 · 거절 뒤 다시 환불 가능 · 정산이 검토 중 환불을 닫지 않음.
 */
class PaddleRefundLedgerTest {
    private val push = FakePushGateway()
    private val kit = PaymentLedgerKit(push = push)
    private val t0: Instant = Instant.parse("2026-10-13T10:00:00Z")
    private val usd = { amount: Long -> PaymentAmount(amount, "USD") }
    private var seq = 0

    /** 결제까지 끝난 Paddle 단건 주문(거래 id 는 서버가 만든 것, 499 USD). */
    private fun paidOrder(account: String = "acc_1", key: String = "o1"): PaymentOrder {
        val order = kit.orders.create(CreateOrderCommand(account, "cheer", "cheer-3000", IdempotencyKey(key), provider = "paddle")).order
        val txn = checkNotNull(kit.order(order.id).providerPaymentId)
        feed(PushedPayment("evt_${++seq}", t0, txn, usd(499), 40, t0, ourOrderId = order.id, items = listOf(PushedItem("pri_cheer-3000", 1))))
        return kit.order(order.id)
    }

    private fun feed(event: PushedPaymentEvent): WebhookReceipt =
        kit.deliver(push.delivery(event), token = null, provider = "paddle").also { kit.runWebhooks(); kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL, PaymentTaskTypes.REFUND_APPLY)) }

    private fun adjustment(order: PaymentOrder, id: String, status: PushedRefundStatus, amount: Long = 499, event: String = "evt_${++seq}") =
        PushedRefund(event, t0.plusSeconds(60), PushedRefundKind.REFUND, id, checkNotNull(order.providerPaymentId), status, usd(amount))

    private fun lines(order: PaymentOrder) = kit.store.listRefunds(order.id)

    private fun refundNow(order: PaymentOrder, key: String = "r1", amount: Long? = null) = kit.refunds.refund("op_1", order.id, "OPERATOR", amount, IdempotencyKey(key))

    @Test
    fun `a refund Paddle holds for review stays pending with its number, and the approval webhook ends it once`() {
        val order = paidOrder()
        push.refundScript = RefundScript.PENDING_APPROVAL

        val outcome = refundNow(order)

        assertEquals(PaymentRefundStatus.PENDING, outcome.refund.status)
        val sent = lines(order).single()
        assertEquals("adj_s1", sent.providerRefundId)
        assertEquals(RefundSettlement.PROVIDER_PENDING, sent.failureCode)

        val approved = adjustment(order, "adj_s1", PushedRefundStatus.APPROVED)
        feed(approved)
        assertEquals(WebhookReceipt.DUPLICATE, feed(approved))
        feed(adjustment(order, "adj_s1", PushedRefundStatus.APPROVED)) // 같은 결정을 새 사건 id 로 다시

        assertEquals(PaymentRefundStatus.SUCCEEDED, lines(order).single().status)
        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order.id).status)
        assertEquals(499, kit.order(order.id).refundedAmount)
        assertEquals(1, kit.listener.refunded.size, "the app took the refund back once")
        assertEquals(1, push.refundCalls.size)
    }

    @Test
    fun `a refund Paddle rejects ends as failed and the order can be refunded again`() {
        val order = paidOrder()
        push.refundScript = RefundScript.PENDING_APPROVAL
        refundNow(order)

        push.decide(checkNotNull(order.providerPaymentId), "adj_s1", "rejected")
        feed(adjustment(order, "adj_s1", PushedRefundStatus.REJECTED))

        val line = lines(order).single()
        assertEquals(PaymentRefundStatus.FAILED, line.status)
        assertEquals("PROVIDER_REJECTED", line.failureCode)
        assertEquals(0, kit.order(order.id).refundedAmount)
        assertTrue(kit.listener.refunded.isEmpty())

        push.refundScript = RefundScript.APPROVED
        assertEquals(PaymentRefundStatus.SUCCEEDED, refundNow(order, key = "r2").refund.status)
    }

    @Test
    fun `a decision that arrives twice changes nothing, and an approval after a rejection makes the provider's word the ledger's - once`() {
        val order = paidOrder()
        push.refundScript = RefundScript.PENDING_APPROVAL
        refundNow(order)
        feed(adjustment(order, "adj_s1", PushedRefundStatus.REJECTED))
        assertEquals(PaymentRefundStatus.FAILED, lines(order).single().status)

        // 거절됐다던 줄에 같은 번호 · 같은 금액의 승인이 왔다 - 돈이 나갔으니 장부가 결제사 말대로 고친다
        feed(adjustment(order, "adj_s1", PushedRefundStatus.APPROVED))
        feed(adjustment(order, "adj_s1", PushedRefundStatus.APPROVED)) // 새 사건 id 로 다시
        feed(adjustment(order, "adj_s1", PushedRefundStatus.REJECTED))

        val line = lines(order).single()
        assertEquals(PaymentRefundStatus.SUCCEEDED, line.status)
        assertNull(line.failureCode)
        assertEquals(499, kit.order(order.id).refundedAmount)
        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order.id).status)
        assertEquals(1, kit.listener.refunded.size, "the app took the refund back once")
        assertEquals(1, kit.store.listEvents(order.id).count { it.refundId == line.id && it.fromStatus == "FAILED" && it.toStatus == "SUCCEEDED" })
    }

    @Test
    fun `an approval that cannot be booked - the amount differs or the total would pass what was paid - leaves the closed line closed and a person is told`() {
        val order = paidOrder()
        push.refundScript = RefundScript.PENDING_APPROVAL
        refundNow(order, amount = 100)
        feed(adjustment(order, "adj_s1", PushedRefundStatus.REJECTED, amount = 100))

        feed(adjustment(order, "adj_s1", PushedRefundStatus.APPROVED, amount = 200)) // 다른 금액

        assertEquals(PaymentRefundStatus.FAILED, lines(order).single().status)
        assertEquals(0, kit.order(order.id).refundedAmount)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(RefundSettlement.LATE_RESULT, lines(order).single().failureCode)
    }

    @Test
    fun `matching with the provider revives a closed line whose number the provider lists as approved - once, however often the operator matches`() {
        val order = paidOrder()
        push.refundScript = RefundScript.PENDING_APPROVAL
        refundNow(order, amount = 100)
        feed(adjustment(order, "adj_s1", PushedRefundStatus.REJECTED, amount = 100))
        assertEquals(PaymentRefundStatus.FAILED, lines(order).single().status)
        push.decide(checkNotNull(order.providerPaymentId), "adj_s1", "approved")

        kit.refunds.sync("op_1", order.id)
        kit.refunds.sync("op_1", order.id)

        val line = lines(order).single()
        assertEquals(PaymentRefundStatus.SUCCEEDED, line.status)
        assertEquals(100, kit.order(order.id).refundedAmount)
        assertEquals(PaymentOrderStatus.PARTIALLY_REFUNDED, kit.order(order.id).status)
        assertNull(kit.order(order.id).reviewReason)
    }

    @Test
    fun `a closed line is not revived past the paid amount - the order is flagged and the total stays`() {
        val order = paidOrder()
        push.refundScript = RefundScript.PENDING_APPROVAL
        refundNow(order, amount = 300)
        feed(adjustment(order, "adj_s1", PushedRefundStatus.REJECTED, amount = 300))
        push.decide(checkNotNull(order.providerPaymentId), "adj_s1", "rejected")
        push.refundScript = RefundScript.APPROVED
        refundNow(order, key = "r2", amount = 400)
        assertEquals(400, kit.order(order.id).refundedAmount)

        feed(adjustment(order, "adj_s1", PushedRefundStatus.APPROVED, amount = 300)) // 400 + 300 > 499

        assertEquals(PaymentRefundStatus.FAILED, lines(order).first { it.providerRefundId == "adj_s1" }.status)
        assertEquals(400, kit.order(order.id).refundedAmount)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
    }

    @Test
    fun `a review Paddle holds for days is not retried to death or flagged as a mismatch, and its approval later leaves the order refundable`() {
        val order = paidOrder()
        push.refundScript = RefundScript.PENDING_APPROVAL
        refundNow(order, amount = 100)

        repeat(14) {
            kit.advance(Duration.ofHours(2))
            kit.runTasks(setOf(PaymentTaskTypes.REFUND_RECONCILE))
        }

        assertNull(kit.order(order.id).reviewReason, "the provider is still looking - nothing for a person yet")
        assertTrue(kit.tasks.dead(PaymentTaskTypes.REFUND_RECONCILE).isEmpty())
        assertTrue(kit.review.flagged.isEmpty())
        push.decide(checkNotNull(order.providerPaymentId), "adj_s1", "approved")
        feed(adjustment(order, "adj_s1", PushedRefundStatus.APPROVED, amount = 100))
        assertEquals(PaymentRefundStatus.SUCCEEDED, lines(order).single().status)
        push.refundScript = RefundScript.APPROVED
        assertEquals(PaymentRefundStatus.SUCCEEDED, refundNow(order, key = "r2", amount = 100).refund.status)
    }

    @Test
    fun `a refund call whose answer was lost is paired with the created webhook and is never sent again`() {
        val order = paidOrder()
        push.refundScript = RefundScript.UNKNOWN_BUT_PENDING

        val unknown = refundNow(order)
        assertEquals(PaymentRefundStatus.PENDING, unknown.refund.status)
        assertNull(lines(order).single().providerRefundId, "the answer never came, so there is no number")

        feed(adjustment(order, "adj_s1", PushedRefundStatus.PENDING_APPROVAL))
        assertEquals("adj_s1", lines(order).single().providerRefundId)
        feed(adjustment(order, "adj_s1", PushedRefundStatus.APPROVED))

        assertEquals(1, lines(order).size, "the same refund, not a second line")
        assertEquals(PaymentRefundStatus.SUCCEEDED, lines(order).single().status)
        assertEquals(499, kit.order(order.id).refundedAmount)
        assertEquals(1, push.refundCalls.size)
    }

    @Test
    fun `the approval arriving before the created event still ends the same line once`() {
        val order = paidOrder()
        push.refundScript = RefundScript.UNKNOWN_BUT_PENDING
        refundNow(order)

        feed(adjustment(order, "adj_s1", PushedRefundStatus.APPROVED))
        feed(adjustment(order, "adj_s1", PushedRefundStatus.PENDING_APPROVAL)) // 늦게 온 접수 알림

        assertEquals(1, lines(order).size)
        assertEquals(PaymentRefundStatus.SUCCEEDED, lines(order).single().status)
        assertEquals(499, kit.order(order.id).refundedAmount)
    }

    @Test
    fun `a refund made in the Paddle dashboard becomes a provider line once`() {
        val order = paidOrder()

        feed(adjustment(order, "adj_console", PushedRefundStatus.APPROVED, amount = 100))
        feed(adjustment(order, "adj_console", PushedRefundStatus.APPROVED, amount = 100))

        val line = lines(order).single()
        assertEquals(PaymentRefundOrigin.PROVIDER, line.origin)
        assertEquals(PaymentRefundReason.PROVIDER_CONSOLE.name, line.reasonCode)
        assertEquals(100, kit.order(order.id).refundedAmount)
        assertEquals(PaymentOrderStatus.PARTIALLY_REFUNDED, kit.order(order.id).status)
    }

    @Test
    fun `an approval that does not fit the order is flagged for a person and changes no amount`() {
        val order = paidOrder()

        feed(adjustment(order, "adj_big", PushedRefundStatus.APPROVED, amount = 600))

        assertEquals(0, kit.order(order.id).refundedAmount)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        assertTrue(lines(order).isEmpty())
    }

    @Test
    fun `while Paddle holds a refund for review no second refund is sent`() {
        val order = paidOrder()
        push.refundScript = RefundScript.PENDING_APPROVAL
        refundNow(order, amount = 100)
        val calls = push.refundCalls.size

        assertFailsWith<PaymentRefundInProgressException> { refundNow(order, key = "r2", amount = 100) }

        assertEquals(calls, push.refundCalls.size)
    }

    @Test
    fun `a review Paddle still holds is not closed as not applied, even by the operator's match after the timeout`() {
        val order = paidOrder()
        push.refundScript = RefundScript.PENDING_APPROVAL
        refundNow(order)
        kit.advance(Duration.ofDays(3))

        kit.refunds.sync("op_1", order.id)
        kit.runTasks(setOf(PaymentTaskTypes.REFUND_RECONCILE))

        assertEquals(PaymentRefundStatus.PENDING, lines(order).single().status)

        push.decide(checkNotNull(order.providerPaymentId), "adj_s1", "approved")
        kit.refunds.sync("op_1", order.id) // 웹훅을 놓쳐도 맞추기가 이뤄진 환불을 줄에 닫는다
        assertEquals(PaymentRefundStatus.SUCCEEDED, lines(order).single().status)
        assertEquals(499, kit.order(order.id).refundedAmount)
    }

    @Test
    fun `a refund sent while another one is held for review at Paddle is not sent at all`() {
        val order = paidOrder()
        push.consoleRefund(checkNotNull(order.providerPaymentId), usd(100), status = "pending_approval")

        assertFailsWith<PaymentRefundInProgressException> { refundNow(order, amount = 100) }

        assertTrue(push.refundCalls.isEmpty())
    }

    @Test
    fun `a refund call with an unknown outcome is never sent again`() {
        val order = paidOrder()
        push.refundScript = RefundScript.UNKNOWN_NOT_APPLIED

        val first = refundNow(order)
        val replay = refundNow(order)

        assertEquals(PaymentRefundStatus.PENDING, first.refund.status)
        assertTrue(replay.replayed)
        assertEquals(1, push.refundCalls.size)
    }
}
