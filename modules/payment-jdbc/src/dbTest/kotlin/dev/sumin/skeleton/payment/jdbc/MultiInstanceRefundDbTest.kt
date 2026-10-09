package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentRefundAmountExceededException
import dev.sumin.skeleton.payment.PaymentRefundInProgressException
import dev.sumin.skeleton.payment.PaymentRefundNotAllowedException
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskTypes
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **여러 대 시험 — 환불 쪽**(B10). 운영자 둘 · 셋이 서로 다른 백엔드에서 같은 주문을 환불한다. 선점은 주문 줄 락과 환불 줄의 유니크 키가 한다 — 자바 락이 아니다.
 * 결제사(`refundCalls`)는 바깥에 하나뿐이라 셋이 함께 본다.
 */
class MultiInstanceRefundDbTest {
    @BeforeTest
    fun clean() = PaymentDb.clean()

    private fun refundJobs(type: String): Long = PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to type)

    @Test
    fun `the same direct refund pressed on three instances with one idempotency key leaves one line one provider call and one recovery`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS) { round ->
                val order = c.paid("k$round", "pay_$round")
                c.provider.duringRefund = { Thread.sleep(80) } // 이긴 쪽이 결제사에 있는 동안 나머지가 선점 락에 줄을 선다

                val results = c.raceAcross(4) { inst, _ -> inst.refunds.refund("op_1", order.id, "OPERATOR", 1000, IdempotencyKey("same-$round")) }

                assertTrue(results.all { it.second.isSuccess }, "round $round: " + results.mapNotNull { it.second.exceptionOrNull() })
                assertEquals(1, results.map { it.second.getOrThrow().refund.id }.toSet().size, "round $round: everyone got the same line")
                assertEquals(1, c.provider.refundCalls.size, "round $round: the provider was asked once")
                assertEquals(1, PaymentDb.count("select count(*) from payment_refunds"), "round $round")
                assertEquals(1, refundJobs(PaymentTaskTypes.REFUND_RECONCILE), "round $round: one settle job")
                assertEquals(1000, c.instances[0].order(order.id).refundedAmount, "round $round: counted once")
                // 정산 작업과 회수 작업을 모든 백엔드의 워커가 동시에 본다 — 회수(onRefunded)는 합쳐서 한 번이다
                c.drainWorkers()
                val lineId = results.first().second.getOrThrow().refund.id
                race(c.size * 2) { i ->
                    c.instances[i % c.size].applier.tryApply(lineId)
                    c.instances[i % c.size].refundApply.handle(PaymentTaskRun(PaymentTaskTypes.REFUND_APPLY, lineId, 1, 5))
                }.forEach { it.getOrThrow() }
                assertEquals(1, c.listenersRefunded, "round $round: the app's refund hook ran once, summed over all instances")
                assertEquals(1000, c.instances[1].order(order.id).refundedAmount, "round $round")
                assertEquals(1000, c.provider.payment("pay_$round")!!.refundedTotal, "round $round")
            }
        }
    }

    @Test
    fun `two operators on different instances refunding the whole order with different keys send one refund and never exceed the paid amount`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS) { round ->
                val order = c.paid("k$round", "pay_$round")
                c.provider.duringRefund = { Thread.sleep(80) }

                // 백엔드마다 운영자 한 명(자기 키), 같은 운영자의 연타는 같은 키
                val results = c.raceAcross(4) { inst, _ ->
                    val n = c.instances.indexOf(inst)
                    inst.refunds.refund("op_$n", order.id, "OPERATOR", null, IdempotencyKey("whole-$n-$round"))
                }

                val ok = results.filter { it.second.isSuccess }
                val failed = results.mapNotNull { it.second.exceptionOrNull() }
                assertEquals(1, ok.map { it.second.getOrThrow().refund.id }.toSet().size, "round $round: one refund line won")
                assertEquals(1, ok.map { it.first }.toSet().size, "round $round: all successes came from the winner's instance")
                assertTrue(
                    failed.all { it is PaymentRefundInProgressException || it is PaymentRefundNotAllowedException || it is PaymentRefundAmountExceededException },
                    "round $round: $failed",
                )
                assertEquals(1, c.provider.refundCalls.size, "round $round: the provider was asked once")
                val lines = c.instances[2].store.listRefunds(order.id)
                assertEquals(1, lines.count { it.status == PaymentRefundStatus.SUCCEEDED }, "round $round")
                val order2 = c.instances[2].order(order.id)
                assertTrue(order2.refundedAmount <= 3000, "round $round: ${order2.refundedAmount}")
                assertEquals(lines.filter { it.status == PaymentRefundStatus.SUCCEEDED }.sumOf { it.amount.amount }, order2.refundedAmount, "round $round")
                assertEquals(order2.refundedAmount, c.provider.payment("pay_$round")!!.refundedTotal, "round $round: the provider and the ledger agree")
            }
        }
    }

    @Test
    fun `one refund request approved by an operator on every instance sends one refund and the rest are refused`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS) { round ->
                val order = c.paid("k$round", "pay_$round")
                val request = c.instances[0].refunds.request("acc_1", order.id, "DUPLICATE", 1000, IdempotencyKey("req-$round")).refund
                c.provider.duringRefund = { Thread.sleep(80) }

                val results = c.raceAcross(4) { inst, _ -> inst.refunds.approve("op_${inst.name}", request.id) }

                assertEquals(1, results.count { it.second.isSuccess }, "round $round: " + results.mapNotNull { it.second.exceptionOrNull() })
                val losers = results.mapNotNull { it.second.exceptionOrNull() }
                assertEquals(results.size - 1, losers.size)
                assertTrue(losers.all { it is PaymentRefundNotAllowedException }, "round $round: $losers")
                assertEquals(1, c.provider.refundCalls.size, "round $round: the provider was asked once")
                assertEquals(1, PaymentDb.count("select count(*) from payment_refunds"), "round $round")
                assertEquals(PaymentRefundStatus.SUCCEEDED, c.instances[1].store.findRefund(request.id)!!.status)
                assertEquals(1000, c.instances[2].order(order.id).refundedAmount, "round $round")
            }
        }
    }

    private companion object {
        const val ROUNDS = 25
    }
}
