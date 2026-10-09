package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefundTest {
    private val op = "op_1"

    private fun key(n: String) = IdempotencyKey("k-$n")

    // --- 요청 · 거둠 · 거절 (돈은 움직이지 않는다) ---

    @Test
    fun `a customer request only writes a line - no provider call and no money moves`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val lookupsBefore = kit.gateway.lookupCalls.size

        val requested = kit.refunds.request("acc_1", order.id, "NOT_DELIVERED", 1000, key("1"))

        assertFalse(requested.replayed)
        assertEquals(PaymentRefundStatus.REQUESTED, requested.refund.status)
        assertEquals(PaymentRefundOrigin.USER, requested.refund.origin)
        assertEquals("NOT_DELIVERED", requested.refund.reasonCode)
        assertEquals(order.id, requested.refund.openKey, "it holds the order's open slot")
        assertEquals(0, kit.gateway.refundCalls.size)
        assertEquals(lookupsBefore, kit.gateway.lookupCalls.size)
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(0, kit.order(order.id).refundedAmount)
        assertEquals(PaymentEventType.REFUND_REQUESTED, kit.store.listEvents(order.id).last().type)
    }

    @Test
    fun `the amount defaults to what is left and the summary tells a request from a refund in flight`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()

        val requested = kit.refunds.request("acc_1", order.id, "DUPLICATE", null, key("1")).refund
        val view = kit.refunds.get("acc_1", order.id)

        assertEquals(3000, requested.amount.amount)
        assertEquals(PaymentRefundStatus.REQUESTED, view.summary.openRefundStatus)
        assertTrue(view.summary.refundPending)
        assertEquals(3000, view.summary.refundableAmount, "a request alone does not reduce what can be refunded")
        assertNull(kit.refunds.get("acc_1", kit.paid(key = "key-2", paymentKey = "pay_2").id).summary.openRefundStatus)
    }

    @Test
    fun `a customer picks one of three reason codes - anything else is a 400 and the operator's code is not theirs`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()

        listOf("OPERATOR", "PROVIDER_CONSOLE", "customer_request", "because I want it", "").forEach { code ->
            val e = assertFailsWith<FieldValidationException>(code) { kit.refunds.request("acc_1", order.id, code, 1000, key(code)) }
            assertEquals("reasonCode", e.field)
        }
        listOf("CUSTOMER_REQUEST", "DUPLICATE", "NOT_DELIVERED").forEachIndexed { i, code ->
            kit.refunds.request("acc_1", order.id, code, 100, key("ok$i")).also { kit.refunds.withdraw("acc_1", it.refund.id) }
        }
    }

    @Test
    fun `requests are refused when the owner turned them off, for the wrong order status, for another account and for bad amounts`() {
        val off = PaymentLedgerKit(PaymentProperties(refund = PaymentProperties.Refund(requestsEnabled = false)))
        assertFailsWith<PaymentRefundNotAllowedException> { off.refunds.request("acc_1", off.paid().id, "DUPLICATE", 100, key("1")) }

        val kit = PaymentLedgerKit()
        val unpaid = kit.create().order
        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.request("acc_1", unpaid.id, "DUPLICATE", 100, key("2")) }
        val order = kit.paid(key = "key-2", paymentKey = "pay_2")
        assertFailsWith<PaymentOrderNotFoundException> { kit.refunds.request("acc_2", order.id, "DUPLICATE", 100, key("3")) }
        assertFailsWith<PaymentOrderNotFoundException> { kit.refunds.request("acc_1", "ord_" + "0".repeat(32), "DUPLICATE", 100, key("4")) }
        assertFailsWith<PaymentRefundAmountExceededException> { kit.refunds.request("acc_1", order.id, "DUPLICATE", 3001, key("5")) }
        assertFailsWith<FieldValidationException> { kit.refunds.request("acc_1", order.id, "DUPLICATE", 0, key("6")) }
        assertFailsWith<FieldValidationException> { kit.refunds.request("acc_1", order.id, "DUPLICATE", -5, key("7")) }
        assertEquals(emptyList(), kit.store.listRefunds(order.id))
    }

    @Test
    fun `a second request while one is open is refused and the same key returns the first line`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val first = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, key("1"))

        assertFailsWith<PaymentRefundInProgressException> { kit.refunds.request("acc_1", order.id, "DUPLICATE", 500, key("2")) }
        val again = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, key("1"))

        assertTrue(again.replayed)
        assertEquals(first.refund.id, again.refund.id)
        assertEquals(1, kit.store.listRefunds(order.id).size)
        assertFailsWith<PaymentIdempotencyConflictException> { kit.refunds.request("acc_1", order.id, "DUPLICATE", 999, key("1")) }
        assertFailsWith<PaymentIdempotencyConflictException> { kit.refunds.request("acc_1", order.id, "NOT_DELIVERED", 1000, key("1")) }
    }

    @Test
    fun `withdrawing frees the slot, only the owner may, and only a requested line can be withdrawn`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val line = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, key("1")).refund

        assertFailsWith<PaymentRefundNotFoundException> { kit.refunds.withdraw("acc_2", line.id) }
        assertFailsWith<PaymentRefundNotFoundException> { kit.refunds.withdraw("acc_1", "rfd_" + "0".repeat(32)) }
        val withdrawn = kit.refunds.withdraw("acc_1", line.id)

        assertEquals(PaymentRefundStatus.WITHDRAWN, withdrawn.status)
        assertNull(withdrawn.openKey)
        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.withdraw("acc_1", line.id) }
        kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, key("2")) // the slot is free again
        assertEquals(0, kit.gateway.refundCalls.size)
    }

    @Test
    fun `an operator rejects a request once - it closes the line and frees the slot`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val line = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, key("1")).refund

        val rejected = kit.refunds.reject(op, line.id)

        assertEquals(PaymentRefundStatus.REJECTED, rejected.status)
        assertEquals(op, rejected.decidedBy)
        assertNull(rejected.openKey)
        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.reject(op, line.id) }
        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.approve(op, line.id) }
        assertFailsWith<PaymentRefundNotFoundException> { kit.refunds.reject(op, "rfd_" + "0".repeat(32)) }
        assertEquals(0, kit.gateway.refundCalls.size)
    }

    // --- 승인 · 운영자 환불 ---

    @Test
    fun `approving a request refunds that amount once with the line id as the provider idempotency key`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val line = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, key("1")).refund

        val outcome = kit.refunds.approve(op, line.id)

        assertEquals(PaymentRefundStatus.SUCCEEDED, outcome.refund.status)
        assertEquals(op, outcome.refund.decidedBy)
        assertEquals("txn_1", outcome.refund.providerRefundId)
        assertNull(outcome.refund.openKey)
        assertEquals(PaymentOrderStatus.PARTIALLY_REFUNDED, outcome.order.status)
        assertEquals(1000, outcome.order.refundedAmount)
        val call = kit.gateway.refundCalls.single()
        assertEquals(IdempotencyKey(line.id), call.idempotencyKey)
        assertEquals(1000, call.amount.amount)
        assertEquals(PaymentRefundReason.DUPLICATE.providerText, call.reason, "a fixed sentence, never the customer's words")
        assertEquals(1, kit.listener.refunded.size, "the app's refund hook ran once")
        assertEquals(
            listOf(PaymentEventType.REFUND_REQUESTED, PaymentEventType.REFUND_SENT, PaymentEventType.REFUND_SUCCEEDED, PaymentEventType.CONVERGED, PaymentEventType.REFUND_APPLIED),
            kit.store.listEvents(order.id).filter { it.refundId != null }.map { it.type },
        )
    }

    @Test
    fun `an operator can refund without a request, in full by default, and an erased account's order too`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(order.id)!!.copy(accountErasedAt = kit.time.now(), cardIssuer = null, cardLast4 = null)) }

        val outcome = kit.refunds.refund(op, order.id, "OPERATOR", null, key("1"))

        assertEquals(PaymentRefundOrigin.OPERATOR, outcome.refund.origin)
        assertEquals(3000, outcome.refund.amount.amount)
        assertEquals(PaymentOrderStatus.REFUNDED, outcome.order.status)
        assertEquals(op, outcome.refund.requestedBy)
    }

    @Test
    fun `the sum of partial refunds never exceeds the paid amount`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()

        kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("1"))
        kit.refunds.refund(op, order.id, "OPERATOR", 1500, key("2"))
        assertFailsWith<PaymentRefundAmountExceededException> { kit.refunds.refund(op, order.id, "OPERATOR", 501, key("3")) }
        assertEquals(1, kit.refunds.view(order.id).refunds.count { it.status == PaymentRefundStatus.SUCCEEDED && it.amount.amount == 1500L })
        val last = kit.refunds.refund(op, order.id, "OPERATOR", 500, key("4"))

        assertEquals(PaymentOrderStatus.REFUNDED, last.order.status)
        assertEquals(3000, last.order.refundedAmount)
        assertEquals(3000, kit.gateway.payment("pay_1")!!.refundedTotal)
        assertEquals(3, kit.gateway.refundCalls.size, "the refused one never reached the provider")
        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.refund(op, order.id, "OPERATOR", 1, key("5")) }
    }

    @Test
    fun `a request larger than what is refundable by approval time is refused and stays requested`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val line = kit.refunds.request("acc_1", order.id, "DUPLICATE", 3000, key("1")).refund
        kit.gateway.consoleCancel("pay_1", 1000) // 관리 화면에서 일부를 먼저 취소했다

        assertFailsWith<PaymentRefundAmountExceededException> { kit.refunds.approve(op, line.id) }

        assertEquals(PaymentRefundStatus.REQUESTED, kit.store.findRefund(line.id)!!.status)
        assertEquals(0, kit.gateway.refundCalls.size)
        assertEquals(1000, kit.order(order.id).refundedAmount, "the pre-check still recorded the console cancel")
    }

    @Test
    fun `while a request is open an operator's direct refund is refused as in progress`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, key("1"))

        assertFailsWith<PaymentRefundInProgressException> { kit.refunds.refund(op, order.id, "OPERATOR", 500, key("2")) }

        assertEquals(0, kit.gateway.refundCalls.size)
        assertEquals(1, kit.store.listRefunds(order.id).size)
    }

    @Test
    fun `an operator reason code is one of four and a customer's words are not accepted`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()

        assertFailsWith<FieldValidationException> { kit.refunds.refund(op, order.id, "PROVIDER_CONSOLE", 100, key("1")) }
        assertFailsWith<FieldValidationException> { kit.refunds.refund(op, order.id, "refund please", 100, key("2")) }
        listOf("CUSTOMER_REQUEST", "DUPLICATE", "NOT_DELIVERED", "OPERATOR").forEachIndexed { i, code -> kit.refunds.refund(op, order.id, code, 100, key("ok$i")) }
        assertEquals(4, kit.gateway.refundCalls.size)
    }

    // --- 같은 키 · 거절 · 모름 ---

    @Test
    fun `the same key returns the refund line as it is now and never calls the provider again - even a failed one`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.Decline())
        assertFailsWith<PaymentRefundDeclinedException> { kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("1")) }

        val again = kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("1"))

        assertTrue(again.replayed)
        assertEquals(PaymentRefundStatus.FAILED, again.refund.status)
        assertEquals(1, kit.gateway.refundCalls.size)
        assertEquals(1, kit.store.listRefunds(order.id).size)
        assertFailsWith<PaymentIdempotencyConflictException> { kit.refunds.refund(op, order.id, "OPERATOR", 2000, key("1")) }
    }

    @Test
    fun `a definitive decline closes the line as failed with the provider's code, classifies it, and the order can be refunded again`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.Decline("NOT_ALLOWED_PARTIAL_REFUND"))

        val e = assertFailsWith<PaymentRefundDeclinedException> { kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("1")) }

        assertEquals(RefundDeclineCategory.PARTIAL_NOT_ALLOWED, e.category)
        assertEquals("NOT_ALLOWED_PARTIAL_REFUND", e.providerCode)
        val failed = kit.store.listRefunds(order.id).single()
        assertEquals(PaymentRefundStatus.FAILED, failed.status)
        assertEquals("NOT_ALLOWED_PARTIAL_REFUND", failed.failureCode)
        assertNull(failed.openKey)
        assertEquals(0, kit.order(order.id).refundedAmount)
        assertEquals(1, kit.tasks.pending(PaymentTaskTypes.REFUND_RECONCILE).size, "the settle job is still queued and will find nothing to do")
        val retry = kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("2"))
        assertEquals(PaymentRefundStatus.SUCCEEDED, retry.refund.status)
        assertNotEquals(failed.id, retry.refund.id, "a new line, a new provider key")
        assertNotEquals(kit.gateway.refundCalls[0].idempotencyKey, kit.gateway.refundCalls[1].idempotencyKey)
    }

    @Test
    fun `an approved request that the provider declines ends failed - it does not go back to requested`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val line = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, key("1")).refund
        kit.gateway.scriptRefund(RefundScript.Decline("EXCEED_MAX_REFUND_DUE"))

        val e = assertFailsWith<PaymentRefundDeclinedException> { kit.refunds.approve(op, line.id) }

        assertEquals(RefundDeclineCategory.PERIOD_EXPIRED, e.category)
        assertEquals(PaymentRefundStatus.FAILED, kit.store.findRefund(line.id)!!.status)
        assertEquals(false, kit.refunds.view(order.id).summary.refundPending)
        assertEquals(3000, kit.refunds.view(order.id).summary.refundableAmount)
    }

    @Test
    fun `a decline code outside the provider's list is classified as other`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.Decline("SOMETHING_NEW"))

        assertEquals(RefundDeclineCategory.OTHER, assertFailsWith<PaymentRefundDeclinedException> { kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("1")) }.category)
    }

    @Test
    fun `an unknown outcome leaves the line pending, sends nothing again and queues the settle job after the settle delay`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.TimeoutNotApplied)

        val outcome = kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("1"))

        assertEquals(PaymentRefundStatus.PENDING, outcome.refund.status)
        assertEquals(order.id, outcome.refund.openKey)
        assertEquals(1, kit.gateway.refundCalls.size)
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status, "the money state is untouched until the provider is asked")
        assertEquals(PaymentRefundStatus.PENDING, kit.refunds.view(order.id).summary.openRefundStatus)
        assertEquals(2000, kit.refunds.view(order.id).summary.refundableAmount, "a refund in flight is not refundable again")
        val task = kit.tasks.pending(PaymentTaskTypes.REFUND_RECONCILE).single()
        assertEquals(outcome.refund.id, task.task.subjectId)
        assertEquals(kit.time.now().plus(kit.properties.refund.settleDelay), task.task.runAt)
        assertFailsWith<PaymentRefundInProgressException> { kit.refunds.refund(op, order.id, "OPERATOR", 500, key("2")) }
    }

    @Test
    fun `a result for another amount is never marked succeeded - the order is flagged and the line stays pending`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.ApproveOtherAmount(PaymentAmount(500, "KRW")))

        val outcome = kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("1"))

        assertEquals(PaymentRefundStatus.PENDING, outcome.refund.status)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(0, kit.order(order.id).refundedAmount)
        assertEquals(0, kit.listener.refunded.size)
    }

    @Test
    fun `a result with a wrong remaining amount is not trusted either`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.ApproveWrongRemaining(PaymentAmount(1, "KRW")))

        val outcome = kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("1"))

        assertEquals(PaymentRefundStatus.PENDING, outcome.refund.status)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
    }

    @Test
    fun `a success without the cancel's transaction key stays pending - without it the cancel could be recorded twice`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.ApproveWithoutTransactionKey)

        val outcome = kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("1"))

        assertEquals(PaymentRefundStatus.PENDING, outcome.refund.status)
        assertNull(kit.order(order.id).reviewReason, "nothing disagrees - the answer was only incomplete")
        assertEquals(1, kit.gateway.refundCalls.size)
    }

    // --- 환불 전 확인 · 모드 · 락 밖 호출 ---

    @Test
    fun `when the pre-refund lookup fails nothing is sent and no line is left open - a direct refund leaves no line, an approval leaves the request as it was`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.failLookups(2)

        assertFailsWith<PaymentRefundNotSentException> { kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("1")) }
        assertEquals(emptyList(), kit.store.listRefunds(order.id))

        val line = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, key("2")).refund
        kit.gateway.failLookups(1)
        assertFailsWith<PaymentRefundNotSentException> { kit.refunds.approve(op, line.id) }

        assertEquals(PaymentRefundStatus.REQUESTED, kit.store.findRefund(line.id)!!.status)
        assertEquals(0, kit.gateway.refundCalls.size)
        assertEquals(0, kit.tasks.all(PaymentTaskTypes.REFUND_RECONCILE).size)
    }

    @Test
    fun `the pre-refund lookup records a cancel the ledger did not know before the refund amount is judged`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.consoleCancel("pay_1", 2500)

        assertFailsWith<PaymentRefundAmountExceededException> { kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("1")) }
        val outcome = kit.refunds.refund(op, order.id, "OPERATOR", 500, key("2"))

        assertEquals(PaymentOrderStatus.REFUNDED, outcome.order.status)
        assertEquals(setOf(PaymentRefundOrigin.PROVIDER, PaymentRefundOrigin.OPERATOR), kit.store.listRefunds(order.id).map { it.origin }.toSet())
        assertEquals(3000, outcome.order.refundedAmount)
        assertNull(outcome.order.reviewReason)
    }

    @Test
    fun `an order created under another key mode is refused before the provider is asked anything`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val lookupsBefore = kit.gateway.lookupCalls.size
        kit.gateway.mode = PaymentMode.LIVE

        assertFailsWith<PaymentModeMismatchException> { kit.refunds.refund(op, order.id, "OPERATOR", 1000, key("1")) }
        val line = kit.gateway.run { mode = PaymentMode.TEST; kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, key("2")).refund }
        kit.gateway.mode = PaymentMode.LIVE
        assertFailsWith<PaymentModeMismatchException> { kit.refunds.approve(op, line.id) }

        assertEquals(0, kit.gateway.refundCalls.size)
        assertEquals(lookupsBefore, kit.gateway.lookupCalls.size)
    }

    @Test
    fun `the provider is called outside every ledger transaction, and entering from inside one is refused`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val line = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, key("1")).refund

        kit.refunds.approve(op, line.id)
        kit.refunds.refund(op, order.id, "OPERATOR", 500, key("2"))
        kit.refunds.sync(op, order.id)

        assertEquals(emptyList(), kit.gateway.callsInsideTransaction)
        kit.store.inTransaction {
            assertFailsWith<IllegalStateException> { kit.refunds.refund(op, order.id, "OPERATOR", 100, key("3")) }
            assertFailsWith<IllegalStateException> { kit.refunds.approve(op, line.id) }
            assertFailsWith<IllegalStateException> { kit.refunds.sync(op, order.id) }
            assertFailsWith<IllegalStateException> { kit.refundReconcile.handle(PaymentTaskRun(PaymentTaskTypes.REFUND_RECONCILE, line.id, 1, 12)) }
        }
    }

    @Test
    fun `the same request approved twice sends one refund and the second is refused`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val line = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, key("1")).refund

        kit.refunds.approve(op, line.id)

        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.approve(op, line.id) }
        assertEquals(1, kit.gateway.refundCalls.size)
    }
}
