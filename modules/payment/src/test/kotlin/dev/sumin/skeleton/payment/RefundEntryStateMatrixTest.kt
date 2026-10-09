package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.PaymentRefundStatus.FAILED
import dev.sumin.skeleton.payment.PaymentRefundStatus.PENDING
import dev.sumin.skeleton.payment.PaymentRefundStatus.REJECTED
import dev.sumin.skeleton.payment.PaymentRefundStatus.REQUESTED
import dev.sumin.skeleton.payment.PaymentRefundStatus.SUCCEEDED
import dev.sumin.skeleton.payment.PaymentRefundStatus.WITHDRAWN
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * 스펙 §13.2 "허용되지 않는 전이 전부가 거절된다" 를 **서비스 입구에서** 본다: 환불 입구(`withdraw` · `reject` · `approve` · 정산 작업 · 회수)마다 여섯 환불 상태 전부에서,
 * 그리고 요청 · 직접 환불은 여덟 주문 상태 전부에서 무슨 일이 일어나야 하는지 표로 적고 돈다. 전이표 객체 자체의 시험은 [PaymentRefundTransitionsTest].
 */
class RefundEntryStateMatrixTest {
    private val op = "op_1"

    /** 결제된 주문 하나와, 그 주문의 환불 줄 하나를 [status] 로 직접 놓는다(전이표를 거치지 않는 시험용 배치). 열린 상태면 열린 자리를 차지한다. */
    private fun refundIn(kit: PaymentLedgerKit, status: PaymentRefundStatus): PaymentRefund {
        val order = kit.paid()
        val line = PaymentTestOrders.refund(
            id = "rfd_" + "b".repeat(32), orderId = order.id, status = status, amount = PaymentAmount(1000, "KRW"), dedupeKey = "seed",
            origin = if (status == SUCCEEDED) PaymentRefundOrigin.PROVIDER else PaymentRefundOrigin.USER, providerRefundId = "txn_seed".takeIf { status == SUCCEEDED },
            now = kit.time.now(),
        ).copy(sentAt = kit.time.now().takeIf { status == PENDING }, expectedRemaining = 2000L.takeIf { status == PENDING })
        kit.store.inTransaction { tx ->
            tx.lockOrder(order.id)
            tx.insertRefundIfAbsent(line)
        }
        return line
    }

    @TestFactory
    fun `withdraw and reject succeed only on a requested line and leave every other line and the provider alone`(): List<DynamicTest> =
        PaymentRefundStatus.entries.flatMap { status ->
            listOf<Pair<String, (PaymentLedgerKit, String) -> PaymentRefund>>(
                "withdraw" to { kit, id -> kit.refunds.withdraw("acc_1", id) },
                "reject" to { kit, id -> kit.refunds.reject(op, id) },
            ).map { (name, entrance) ->
                DynamicTest.dynamicTest("$name on $status") {
                    val kit = PaymentLedgerKit()
                    val line = refundIn(kit, status)
                    val events = kit.store.listEvents(line.orderId)
                    if (status == REQUESTED) {
                        assertEquals(if (name == "withdraw") WITHDRAWN else REJECTED, entrance(kit, line.id).status)
                    } else {
                        assertFailsWith<PaymentRefundNotAllowedException> { entrance(kit, line.id) }
                        assertEquals(line, kit.store.findRefund(line.id))
                        assertEquals(events, kit.store.listEvents(line.orderId))
                    }
                    assertEquals(0, kit.gateway.refundCalls.size)
                }
            }
        }

    @TestFactory
    fun `approve acts only on a requested line - any other status is refused before the provider is asked anything`(): List<DynamicTest> =
        PaymentRefundStatus.entries.map { status ->
            DynamicTest.dynamicTest("approve on $status") {
                val kit = PaymentLedgerKit()
                val line = refundIn(kit, status)
                val lookups = kit.gateway.lookupCalls.size
                if (status == REQUESTED) {
                    assertEquals(SUCCEEDED, kit.refunds.approve(op, line.id).refund.status)
                    assertEquals(1, kit.gateway.refundCalls.size)
                } else {
                    assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.approve(op, line.id) }
                    assertEquals(line, kit.store.findRefund(line.id))
                    assertEquals(lookups, kit.gateway.lookupCalls.size)
                    assertEquals(0, kit.gateway.refundCalls.size)
                }
            }
        }

    @TestFactory
    fun `the settle job acts only on a pending line`(): List<DynamicTest> =
        PaymentRefundStatus.entries.map { status ->
            DynamicTest.dynamicTest("settle job on $status") {
                val kit = PaymentLedgerKit()
                val line = refundIn(kit, status)
                kit.advance(Duration.ofMinutes(3))
                val lookups = kit.gateway.lookupCalls.size

                kit.refundReconcile.handle(PaymentTaskRun(PaymentTaskTypes.REFUND_RECONCILE, line.id, 1, 12))

                if (status == PENDING) {
                    assertEquals(FAILED, kit.store.findRefund(line.id)!!.status, "nothing applied at the provider")
                    assertEquals(lookups + 1, kit.gateway.lookupCalls.size)
                } else {
                    assertEquals(line, kit.store.findRefund(line.id))
                    assertEquals(lookups, kit.gateway.lookupCalls.size)
                }
            }
        }

    @TestFactory
    fun `the apply job calls the app only for a succeeded line that was not applied`(): List<DynamicTest> =
        PaymentRefundStatus.entries.map { status ->
            DynamicTest.dynamicTest("apply job on $status") {
                val kit = PaymentLedgerKit()
                val line = refundIn(kit, status)

                kit.refundApply.handle(PaymentTaskRun(PaymentTaskTypes.REFUND_APPLY, line.id, 1, 5))
                kit.refundApply.handle(PaymentTaskRun(PaymentTaskTypes.REFUND_APPLY, line.id, 2, 5))

                assertEquals(if (status == SUCCEEDED) 1 else 0, kit.listener.refunded.size)
                assertEquals(status == SUCCEEDED, kit.store.findRefund(line.id)!!.appliedAt != null)
            }
        }

    @TestFactory
    fun `a request or a direct refund is possible only for a paid or partly refunded order`(): List<DynamicTest> =
        PaymentOrderStatus.entries.flatMap { status ->
            val allowed = status == PaymentOrderStatus.PAID || status == PaymentOrderStatus.PARTIALLY_REFUNDED
            listOf<Pair<String, (PaymentLedgerKit, PaymentOrder) -> Any>>(
                "request" to { kit, order -> kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, IdempotencyKey("k-1")) },
                "direct refund" to { kit, order -> kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1")) },
            ).map { (name, entrance) ->
                DynamicTest.dynamicTest("$name on an order that is $status") {
                    val kit = PaymentLedgerKit()
                    val created = kit.createAuthenticated()
                    kit.gateway.settle("pay_1")
                    kit.store.inTransaction { tx ->
                        tx.updateOrder(
                            tx.lockOrder(created.id)!!.copy(
                                status = status, providerPaymentId = if (status == PaymentOrderStatus.PENDING) null else "pay_1",
                                paidAmount = created.amount.amount.takeIf { status.isPaidFamily }, refundedAmount = if (status == PaymentOrderStatus.REFUNDED) created.amount.amount else 0,
                            ),
                        )
                    }
                    val before = kit.order(created.id)
                    if (status == PaymentOrderStatus.REFUNDED) kit.gateway.consoleCancel("pay_1", 3000)

                    if (allowed) {
                        entrance(kit, before)
                        assertTrue(kit.store.listRefunds(created.id).isNotEmpty())
                    } else {
                        assertFailsWith<PaymentRefundNotAllowedException> { entrance(kit, before) }
                        assertEquals(emptyList(), kit.store.listRefunds(created.id))
                        assertEquals(0, kit.gateway.refundCalls.size)
                    }
                }
            }
        }
}
